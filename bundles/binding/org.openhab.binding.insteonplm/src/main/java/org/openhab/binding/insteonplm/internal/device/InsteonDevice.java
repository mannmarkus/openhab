/**
 * Copyright (c) 2010-2013, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.insteonplm.internal.device;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map.Entry;
import org.openhab.binding.insteonplm.internal.driver.Driver;
import org.openhab.binding.insteonplm.internal.message.FieldException;
import org.openhab.binding.insteonplm.internal.message.Msg;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * The InsteonDevice class holds known per-device state of a single Insteon device,
 * including the address, what port(modem) to reach it on etc.
 * Note that some Insteon devices de facto consist of two devices (let's say
 * a relay and a sensor), but operate under the same address. Such devices will
 * be represented just by a single InsteonDevice. Their different personalities
 * will then be represented by DeviceFeatures.
 * 
 * @author Bernd Pfrommer
 * @since 1.5.0
 */
public class InsteonDevice {
	private InsteonAddress				m_address		= new InsteonAddress();
	private ArrayList<String>			m_ports			= new ArrayList<String>();
	private ArrayList<DeviceDescriptor> m_descriptors = new ArrayList<DeviceDescriptor>();
	private	 ArrayList<Msg>				m_linkRecords	= new ArrayList<Msg>();
	private long						m_pollInterval	= -1L; // in milliseconds
	private Driver						m_driver		= null;
	private HashMap<String, DeviceFeature> 	m_features	= new HashMap<String, DeviceFeature>();
	private	 InitStatus					m_initStatus	= InitStatus.UNINITIALIZED;
	private String						m_productKey	= null;
	private Long						m_lastTimePolled = 0L;
	private Long						m_lastMsgReceived = 0L;
	private boolean					m_isModem		= false;
	private boolean					m_hasRespondedToQuery = false;
	private boolean					m_descriptorsHaveChanged = false;
	private boolean					m_needsQuerying = false;
	private boolean					m_isInItemsFile = false;
	private	 Deque<QEntry>				m_requestQueue  = new LinkedList<QEntry>();
	
	public static enum InitStatus {
		UNINITIALIZED,
		INITIALIZED
	}
	private static final Logger logger = LoggerFactory.getLogger(InsteonDevice.class);
	
	public InsteonDevice(InsteonAddress ia, Driver d) {
		m_address	= ia;
		m_driver	= d;
		m_lastMsgReceived = System.currentTimeMillis();
	}
	public void addPort(String p) {
		if (p == null) return;
		if (!m_ports.contains(p)) {
			m_ports.add(p);
		}
	}
	public void addLinkRecord(Msg m) { m_linkRecords.add(m); }
	public boolean hasLinkRecords() { return !m_linkRecords.isEmpty(); }
	public boolean hasRespondedToQuery() { return m_hasRespondedToQuery; }
	public boolean hasValidDescriptors() { return !m_descriptors.isEmpty(); }
	public boolean needsQuerying() { return m_needsQuerying; }
	public void clearDescriptors() { m_descriptors.clear(); }
	public void addFeature(String name, DeviceFeature f) {
		f.setDevice(this);
		synchronized (m_features) {
			m_features.put(name, f);
		}
	}
	public void removeFeature(DeviceFeature f) {
		f.setDevice(null);
		synchronized (m_features) {
			m_features.remove(f);
		}
	}
	
	private void clearFeatures() {
		synchronized (m_features) {
			m_features.clear();
		}
	}

	public boolean hasProductKey() {	return m_productKey != null;	}
	public String getProductKey() 	{	return m_productKey; }
	
	public boolean isReferenced() {
		for (DeviceFeature f: m_features.values()) {
			if (f.hasListeners()) return true;
		}
		return false;
	}
	public String getPort() throws IOException {
		if (m_ports.isEmpty()) throw new IOException("no ports configured for instrument " + getAddress());
		return (m_ports.iterator().next());
	}
	public DeviceFeature 		getFeature(String f) { 	return m_features.get(f);	}
	public HashMap<String, DeviceFeature> getFeatures() { return m_features; }
	public InsteonAddress		getAddress() 		{ return (m_address);	}
	public InitStatus			getInitStatus()		{ return m_initStatus; }
	public Driver				getDriver()			{ return m_driver; }
	public boolean 				hasValidPorts()		{ return (!m_ports.isEmpty());	}
	public boolean				isInitialized() 	{ return m_initStatus == InitStatus.INITIALIZED; }
	public boolean				hasProductKey(String key) {
		return m_productKey != null && m_productKey.equals(key); }
	public boolean				descriptorsHaveChanged() { return m_descriptorsHaveChanged; }
	public boolean				hasValidPollingInterval() {
		return (m_pollInterval > 0);
	}
	public long				getPollInterval()	{ return m_pollInterval; }
	public boolean				isModem()	 		{ return m_isModem; }
	public boolean				isInItemsFile()		{ return m_isInItemsFile; }
	
	public void setIsModem(boolean f)  			{ m_isModem = f; }
	public void setIsInItemsFile(boolean f)			{ m_isInItemsFile = f; }
	public void setInitStatus(InitStatus status)	{ m_initStatus = status; }
	public void setDescriptorsHaveChanged(boolean f) { m_descriptorsHaveChanged = f; }
	public void setNeedsQuerying(boolean f) 		{ m_needsQuerying = f; }
	
	public void addDescriptor(DeviceDescriptor desc) {
		if (!m_descriptors.contains(desc)) 	{
			m_descriptors.add(desc);
			setDescriptorsHaveChanged(true);
		}
	}
	
	public long getPollOverDueTime() {
		return (m_lastTimePolled - m_lastMsgReceived);
	}
	public String getDescriptorsAsString() {
		String s = "";
		for (DeviceDescriptor d: m_descriptors) {
			s += d.toShortString();
		}
		return s;
	}
	
	public void setProductKey(String pk) { m_productKey = pk; }
	public void setPollInterval(long pi) {
		logger.debug("setting poll interval for {} to {} ", m_address, pi);
		if (pi > 0) m_pollInterval = pi;
	}
	/**
	 * This method is called when product information has been received
	 * through a query. It attempts to repair the sometimes broken or
	 * incomplete information that was obtained from the modem's link
	 * records.
	 * 
	 * @param prodKey The product key received. Since most devices only
	 *                 report 0 as product key, this argument is currently ignored
	 * @param devCat Insteon device category
	 * @param subCat Insteon device subcategory
	 * @param vers   firmware version
	 */
	public void setProductInfo(int prodKey, int devCat, int subCat, int vers) {
		m_hasRespondedToQuery = true;
		m_productKey = null; // reset product key such that features are instantiated again!
		DeviceDescriptor desc = DeviceDescriptor.s_getDeviceDescriptor(devCat, subCat, vers);
		desc.setVersion(vers);
		logger.debug("{} updated with queried descriptor: {}", this, desc);
		m_descriptors.clear();
		addDescriptor(desc);
		setNeedsQuerying(false);
	}
	
	public void instantiateFeatures() {
		if (m_productKey == null) return;
		logger.debug("calling instantiate features {} ", m_productKey);
		clearFeatures();
		// instantiate features
		for (DeviceDescriptor desc : m_descriptors) {
			HashMap<String,String> features = desc.getSubCat().getProductKeys().get(m_productKey);
			if (features == null) continue;
			for (Entry<String, String> fe : features.entrySet()) {
				logger.debug("instantiating feature {} {}", fe.getKey(), fe.getValue());
				DeviceFeature f = DeviceFeature.s_makeDeviceFeature(fe.getValue());
				if (f == null) {
					logger.error("error in categories.xml: unimplemented feature {}", fe.getValue());
				} else {
					addFeature(fe.getKey(), f);
				}
			}
		}
		if (m_features.isEmpty() && !m_descriptors.isEmpty()) {
			logger.warn("dev {} does not match product key {} ", m_address, m_productKey);
			for (DeviceDescriptor desc : m_descriptors) {
				for (String pk: desc.getSubCat().getProductKeys().keySet()) {
					logger.warn("   cat {} subcat {} has product key {}", desc.getDevCat(), desc.getSubCat(), pk);
				}
			}
		}
	}

	public void processCommand(Driver driver, Command command) {
		logger.debug("processing command {} features: {}", command, m_features.size());
		synchronized(m_features) {
			for (DeviceFeature i : m_features.values()) {
				i.handleCommand(command);
			}
		}
	}
	
	public void doPoll() {
		ArrayList<QEntry> l = new ArrayList<QEntry>();
		synchronized(m_features) {
			for (DeviceFeature i : m_features.values()) {
				if (i.hasListeners()) {
					Msg m = i.makePollMsg();
					if (m != null) l.add(new QEntry(i, m));
				}
			}
		}
		if (l.isEmpty()) return;
		synchronized (m_requestQueue) {
			for (QEntry e : l) {
				m_requestQueue.add(e);
			}
		}
		long now = System.currentTimeMillis();
		RequestQueueManager.instance().addQueue(this, now);
		
		if (!l.isEmpty()) {
			synchronized(m_lastTimePolled) {
				m_lastTimePolled = now;
			}
		}
	}
	void eraseFromModem() {
		try {
			for (Msg lr : m_linkRecords) {
				Msg m = Msg.s_makeMessage("ManageALLLinkRecord");
				m.setByte("controlCode", (byte)0x80);
				m.setByte("recordFlags", (byte)0x00);
				m.setByte("ALLLinkGroup", lr.getByte("ALLLinkGroup"));
				m.setAddress("linkAddress", m_address);
				m.setByte("linkData1", (byte)0x00);
				m.setByte("linkData2", (byte)0x00);
				m.setByte("linkData3", (byte)0x00);
				Driver d = getDriver();
				d.writeMessage(d.getDefaultPort(), m);
				logger.info("wrote erase message: {}", m);
			}
			m_linkRecords.clear();
		} catch (FieldException e) {
			logger.error("field exception: ", e);
		} catch (IOException e) {
			logger.error("i/o exception: ", e);
		}
	}
	
	public void handleMessage(String fromPort, Msg msg, EventPublisher ep) {
		synchronized (m_lastMsgReceived) {
			m_lastMsgReceived = System.currentTimeMillis();
		}
		//logger.debug("device {} got msg {}", m_address, msg);
		synchronized(m_features) {
			for (DeviceFeature f : m_features.values()) {
				if (!f.isStatusFeature()) {
					if (f.handleMessage(msg, fromPort)) {
						break;
					}
				}
			}
			for (DeviceFeature f : m_features.values()) {
				if (f.isStatusFeature()) {
					f.handleMessage(msg, fromPort);
				}
			}
		}
	}
	
	public Msg makeStandardMessage(byte flags, byte cmd1, byte cmd2)
			throws FieldException, IOException {
		Msg m = Msg.s_makeMessage("SendStandardMessage");
		m.setAddress("toAddress", getAddress());
		m.setByte("messageFlags", flags);
		m.setByte("command1", cmd1);
		m.setByte("command2", cmd2);
		return m;
	}

	public Msg makeExtendedMessage(byte flags, byte cmd1, byte cmd2)
			throws FieldException, IOException {
		Msg m = Msg.s_makeMessage("SendExtendedMessage");
		m.setAddress("toAddress", getAddress());
		m.setByte("messageFlags", (byte) (((flags & 0xff) | 0x10) & 0xff));
		m.setByte("command1", cmd1);
		m.setByte("command2", cmd2);
		int checksum = (~(cmd1 + cmd2) + 1) &0xff;
		m.setByte("userData14", (byte)checksum);
		return m;
	}
	
	public long processRequestQueue(long timeNow) {
		synchronized (m_requestQueue) {
			if (m_requestQueue.isEmpty()) {
				return 0L;
			}
			QEntry qe = m_requestQueue.poll();
			qe.getFeature().setQueryStatus(DeviceFeature.QueryStatus.QUERY_PENDING);
			long quietTime = qe.getMsg().getQuietTime();
			qe.getMsg().setQuietTime(500L); // rate limiting downstream:
			try {
				writeMessage(qe.getMsg());
			} catch (IOException e) {
				logger.warn("message write failed!");
			}
			return (timeNow + quietTime);
		}
	}

	public static class QEntry {
		private DeviceFeature	m_feature	= null;
		private Msg 			m_msg		= null;
		public DeviceFeature getFeature() { return m_feature; }
		public Msg getMsg() { return m_msg; }
		QEntry(DeviceFeature f, Msg m) {
			m_feature	= f;
			m_msg		= m;
		}
	}

	private void writeMessage(Msg m) throws IOException {
		m_driver.writeMessage(getPort(), m);
	}

	public void enqueueMessage(Msg m, DeviceFeature f) {
		synchronized (m_requestQueue) {
			m_requestQueue.add(new QEntry(f, m));
		}
		long now = System.currentTimeMillis();
		RequestQueueManager.instance().addQueue(this, now);
	}

	public String getStatusAsString() {
		String s = m_initStatus.toString();
		return s;
	}
	
	public String toString() {
		String s = m_address.toString();
		for (DeviceDescriptor d : m_descriptors) {
			s += ":" + d.toString();
		}
		for (Entry<String, DeviceFeature> f : m_features.entrySet()) {
			s += "|" + f.getKey() + "->" + f.getValue().toString();
		}
		return s;
	}
	
	public static InsteonDevice s_makeDevice(InsteonAddress a, Driver d) {
		// look up by cat/subcat to see what kind of device we need to make
		InsteonDevice dev = new InsteonDevice(a, d);
		return dev;
	}
	
	static int s_toKey(int cat, int subCat) {
		return cat * 256 + subCat;
	}
}
