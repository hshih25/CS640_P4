package edu.wisc.cs.sdn.apps.loadbalancer;
import java.util.*;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.instruction.OFInstructionGotoTable;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFOXMFieldType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.ArpServer;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import edu.wisc.cs.sdn.apps.l3routing.L3Routing;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.util.MACAddress;

public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
		IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();
	
	private static final byte TCP_FLAG_SYN = 0x02;
	
	private static final short IDLE_TIMEOUT = 20;
	private static final short HARD_TIMEOUT = 30;
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;
    
    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{ 
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        
        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
		/*********************************************************************/
	}
	
/**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override
	public void switchAdded(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Install rules to send:                                      */
		/*       (1) packets from new connections to each virtual load       */
		/*       balancer IP to the controller                               */
		/*       (2) ARP packets to the controller, and                      */
		/*       (3) all other packets to the next rule table in the switch  */
		
		/*********************************************************************/
		
		// rules from accessing host through virtual ip
		ArrayList<OFInstruction> instructions = null;
		
		for (Integer vip : this.instances.keySet()) {
			OFMatch matchCriteria = new OFMatch();
        	matchCriteria.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
        	matchCriteria.setNetworkDestination(vip);
			// make sure : need setNetworkProtocol to TCP ?
			matchCriteria.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
        	OFAction action = new OFActionOutput(OFPort.OFPP_CONTROLLER);
        	// ArrayList<OFAction> actions = new ArrayList<OFAction>();
        	// actions.add(action);
        	OFInstruction instruction = new OFInstructionApplyActions(Arrays.asList(action));
        	// instructions = new ArrayList<OFInstruction>();
        	// instructions.add(instruction);
        	SwitchCommands.installRule(sw, table, (short) (SwitchCommands.DEFAULT_PRIORITY + 1), matchCriteria, Arrays.asList(instruction));

			
		}

		// make sure : add rule for arp request out of for loop?
		OFMatch matchCriteria = new OFMatch();
		matchCriteria.setDataLayerType(OFMatch.ETH_TYPE_ARP);
		// matchCriteria.setNetworkDestination(virIP);
		OFAction action = new OFActionOutput(OFPort.OFPP_CONTROLLER);
		OFInstruction instruction = new OFInstructionApplyActions(Arrays.asList(action));
		SwitchCommands.installRule(sw, this.table, (short)(SwitchCommands.DEFAULT_PRIORITY+1), matchCriteria, Arrays.asList(instruction));

		//rule originally installed by layer3 routing application
		OFMatch blk = new OFMatch();
		instructions = new ArrayList<OFInstruction>();
		instruction = new OFInstructionGotoTable(L3Routing.table);
		instructions.add(instruction);
		SwitchCommands.installRule(sw, table, SwitchCommands.DEFAULT_PRIORITY, blk, instructions);
	}


	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) 
	{
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;
		
		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0, pktIn.getPacketData().length);
		
		/*********************************************************************/
		/* TODO: Send an ARP reply for ARP requests for virtual IPs; for TCP */
		/*       SYNs sent to a virtual IP, select a host and install        */
		/*       connection-specific rules to rewrite IP and MAC addresses;  */
		/*       ignore all other packets                                    */
		
		/*********************************************************************/
		if (ethPkt.getEtherType() == Ethernet.TYPE_ARP)  
			handleArpRequest(sw, ethPkt, pktIn);
		if (ethPkt.getEtherType() == Ethernet.TYPE_IPv4) {
			IPv4 ipv4Pkt = (IPv4) ethPkt.getPayload();
			if (ipv4Pkt.getProtocol() == IPv4.PROTOCOL_TCP) {
				TCP tcpPkt = (TCP) ipv4Pkt.getPayload();
				if (tcpPkt.getFlags() == TCP_FLAG_SYN) 
					handleTcpAction(sw, ipv4Pkt);
			}
		}
		
		// We don't care about other packets
		return Command.CONTINUE;
	}
	

	private void handleArpRequest(IOFSwitch sw, Ethernet ethPkt, OFPacketIn pktIn) {
		ARP arpRequest = (ARP) ethPkt.getPayload();
		//make sure : check opcode
		if (arpRequest.getOpCode() == ARP.OP_REQUEST) { 
			int vip = IPv4.toIPv4Address(arpRequest.getTargetProtocolAddress());
			//if the host for arpRequest is the host with specific virtual IP -> reply
			//otherwise, ignore
			if (this.instances.containsKey(vip)) {
				// reply this packet
				ARP arpReply = new ARP();
				Ethernet ether = new Ethernet();
				ether.setEtherType(Ethernet.TYPE_ARP);
				ether.setDestinationMACAddress(ethPkt.getSourceMACAddress());
				ether.setSourceMACAddress(this.instances.get(vip).getVirtualMAC());

				arpReply.setSenderProtocolAddress(vip);
				arpReply.setSenderHardwareAddress(this.instances.get(vip).getVirtualMAC());
				
				arpReply.setProtocolType(arpRequest.getProtocolType());
				arpReply.setHardwareType(arpRequest.getHardwareType());

				//make sure : use arpRequest's length instead?
				// arpReply.setProtocolAddressLength(arpRequest.getProtocolAddressLength());
				// arpReply.setHardwareAddressLength(arpRequest.getHardwareAddressLength());
				arpReply.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
				arpReply.setProtocolAddressLength((byte) 4);

				arpReply.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress());
				arpReply.setTargetHardwareAddress(arpRequest.getSenderHardwareAddress());

				arpReply.setOpCode(ARP.OP_REPLY);

				ether.setPayload(arpReply);
				SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), ether);
			}	
		}
	}

	private void handleTcpAction(IOFSwitch sw, IPv4 ipv4Pkt) {
		int vDstIP = ipv4Pkt.getDestinationAddress();
		//diff:?????????containskey??????	
		// if (!this.instances.containsKey(vDstIP)) 
		// 	return;
		int vSrcIP = ipv4Pkt.getSourceAddress();
		TCP tcpPkt = (TCP) ipv4Pkt.getPayload();
		short srcPort = tcpPkt.getSourcePort();
		short dstPort = tcpPkt.getDestinationPort();
		//use Round-Robin to get next hop
		int nextIP = this.instances.get(vDstIP).getNextHostIP();

		// handle rules coming in -> from SDN switch to SDN controller (set the destination to SDN controller)
		OFMatch matchCriteria = new OFMatch();
		matchCriteria.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
		matchCriteria.setNetworkSource(vSrcIP);
		matchCriteria.setNetworkDestination(vDstIP);
		matchCriteria.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
		matchCriteria.setTransportSource(srcPort);
        matchCriteria.setTransportDestination(dstPort);
		// need to make sure : order matter?!
		// matchCriteria.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
		
		OFAction nextActionIP = new OFActionSetField(OFOXMFieldType.IPV4_DST, nextIP);
		OFAction nextActionMac = new OFActionSetField(OFOXMFieldType.ETH_DST, getHostMACAddress(nextIP));
		
		ArrayList<OFAction> actions = new ArrayList<OFAction>();
		actions.add(nextActionMac);
		actions.add(nextActionIP);
		
		OFInstruction instruction = new OFInstructionApplyActions(actions);
		OFInstruction defaultInstr = new OFInstructionGotoTable(L3Routing.table);

		ArrayList<OFInstruction> instructions = new ArrayList<OFInstruction>();
		instructions.add(instruction);
		instructions.add(defaultInstr);
		//make sure: hard_timeout -> no_timeout
		SwitchCommands.installRule(sw, table, (short) (SwitchCommands.DEFAULT_PRIORITY + 2), 
				matchCriteria, instructions, SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);				
		
		// handle rules going out -> from SDN controller to SDN switch (set the destination to SDN switch)
		matchCriteria = new OFMatch();
		matchCriteria.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
        matchCriteria.setNetworkSource(nextIP);
        matchCriteria.setNetworkDestination(vSrcIP);
        matchCriteria.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
		//make sure: use method with two args(input TCP protocol)
        matchCriteria.setTransportSource(dstPort);
        matchCriteria.setTransportDestination(srcPort);
		// matchCriteria.setTransportSource(OFMatch.IP_PROTO_TCP, dstPort);
		// matchCriteria.setTransportDestination(OFMatch.IP_PROTO_TCP, srcPort);

		nextActionIP = new OFActionSetField(OFOXMFieldType.IPV4_SRC, vDstIP);
        nextActionMac = new OFActionSetField(OFOXMFieldType.ETH_SRC, this.instances.get(vDstIP).getVirtualMAC());
        

        actions = new ArrayList<OFAction>();
        actions.add(nextActionMac);
        actions.add(nextActionIP);
        
        instruction = new OFInstructionApplyActions(actions);
        defaultInstr = new OFInstructionGotoTable(L3Routing.table);

        instructions = new ArrayList<OFInstruction>();
        instructions.add(instruction);
        instructions.add(defaultInstr);
		//make sure : hard time out -> no time out
        SwitchCommands.installRule(sw, table, (short) (SwitchCommands.DEFAULT_PRIORITY + 2), 
				matchCriteria, instructions, SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);

	}

	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) 
	{
		return (OFType.PACKET_IN == type 
				&& (name.equals(ArpServer.MODULE_NAME) 
					|| name.equals(DeviceManagerImpl.MODULE_NAME))); 
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) 
	{ return false; }
}
