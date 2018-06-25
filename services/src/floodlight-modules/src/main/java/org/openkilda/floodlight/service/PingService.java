/* Copyright 2018 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.floodlight.service;

import org.openkilda.floodlight.SwitchUtils;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.command.ping.PingResponseCommand;
import org.openkilda.floodlight.error.InvalidSignatureConfigurationException;
import org.openkilda.floodlight.pathverification.PathVerificationService;
import org.openkilda.floodlight.switchmanager.ISwitchManager;
import org.openkilda.floodlight.utils.DataSignature;
import org.openkilda.messaging.model.Ping;

import com.google.common.collect.ImmutableSet;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

public class PingService extends AbstractOfHandler implements IFloodlightService {
    private static Logger log = LoggerFactory.getLogger(PingService.class);

    private static final U64 OF_CATCH_RULE_COOKIE = U64.of(ISwitchManager.VERIFICATION_UNICAST_RULE_COOKIE);
    private static final String NET_L3_ADDRESS = "127.0.0.2";

    private DataSignature signature = null;
    private SwitchUtils switchUtils = null;

    private FloodlightModuleContext moduleContext;

    /**
     * Initialize internal data structures. Called by module that own this service. Called after all dependencies have
     * been loaded.
     */
    public void init(FloodlightModuleContext moduleContext) throws FloodlightModuleException {
        this.moduleContext = moduleContext;

        // FIXME(surabujin): avoid usage foreign module configuration
        Map<String, String> config = moduleContext.getConfigParams(PathVerificationService.class);
        try {
            signature = new DataSignature(config.get("hmac256-secret"));
        } catch (InvalidSignatureConfigurationException e) {
            throw new FloodlightModuleException(String.format("Unable to initialize %s", getClass().getName()), e);
        }

        switchUtils = new SwitchUtils(moduleContext.getServiceImpl(IOFSwitchService.class));
        activateSubscription(moduleContext, OFType.PACKET_IN);
    }

    @Override
    public boolean handle(IOFSwitch sw, OFMessage message, FloodlightContext context) {
        OFPacketIn packet = (OFPacketIn) message;

        Ethernet eth = IFloodlightProviderService.bcStore.get(context, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        byte[] payload;
        boolean mustMatch;
        try {
            if (! matchByCookie(packet)) {
                return false;
            }
            mustMatch = true;
        } catch (UnsupportedOperationException e) {
            log.debug("Unable to match OFPacketIn by cookie, fallback to payload match: {}", e.toString());
            mustMatch = false;
        }

        payload = unwrapData(switchUtils.dpIdToMac(sw.getId()), eth);
        if (payload == null) {
            if (mustMatch) {
                log.error("Got invalid ping package on {}", sw.getId());
            }
            return false;
        }

        CommandContext commandContext = new CommandContext(moduleContext);
        PingResponseCommand command = new PingResponseCommand(commandContext, sw, payload);
        command.execute();

        return true;
    }

    private boolean matchByCookie(OFPacketIn packet) {
        return packet.getCookie() != OF_CATCH_RULE_COOKIE;
    }

    /**
     * Wrap ping data into L2 and L3 network packages.
     */
    public Ethernet wrapData(Ping ping, byte[] payload) {
        Data l7 = new Data(payload);

        UDP l4 = new UDP();
        l4.setPayload(l7);
        l4.setSourcePort(TransportPort.of(PathVerificationService.VERIFICATION_PACKET_UDP_PORT));
        l4.setDestinationPort(TransportPort.of(PathVerificationService.VERIFICATION_PACKET_UDP_PORT));

        IPv4 l3 = new IPv4();
        l3.setPayload(l4);
        l3.setSourceAddress(NET_L3_ADDRESS);
        l3.setDestinationAddress(NET_L3_ADDRESS);

        Ethernet l2 = new Ethernet();
        l2.setPayload(l3);
        l2.setEtherType(EthType.IPv4);

        l2.setSourceMACAddress(switchUtils.dpIdToMac(DatapathId.of(ping.getSource().getSwitchDpId())));
        l2.setDestinationMACAddress(switchUtils.dpIdToMac(DatapathId.of(ping.getDest().getSwitchDpId())));
        if (0 != ping.getSourceVlanId()) {
            l2.setVlanID(ping.getSourceVlanId());
        }

        return l2;
    }

    /**
     * Unpack network package.
     * Verify all particular qualities used during verification package creation time. Return packet payload.
     */
    public byte[] unwrapData(MacAddress targetL2Address, Ethernet packet) {
        if (!packet.getDestinationMACAddress().equals(targetL2Address)) {
            return null;
        }

        if (!(packet.getPayload() instanceof IPv4)) {
            return null;
        }
        IPv4 ip = (IPv4) packet.getPayload();

        if (!NET_L3_ADDRESS.equals(ip.getSourceAddress().toString())) {
            return null;
        }
        if (!NET_L3_ADDRESS.equals(ip.getDestinationAddress().toString())) {
            return null;
        }

        if (!(ip.getPayload() instanceof UDP)) {
            return null;
        }
        UDP udp = (UDP) ip.getPayload();

        if (udp.getSourcePort().getPort() != PathVerificationService.VERIFICATION_PACKET_UDP_PORT) {
            return null;
        }
        if (udp.getDestinationPort().getPort() != PathVerificationService.VERIFICATION_PACKET_UDP_PORT) {
            return null;
        }

        return ((Data) udp.getPayload()).getData();
    }

    public DataSignature getSignature() {
        return signature;
    }

    @Override
    protected Set<String> mustHandleBefore() {
        return ImmutableSet.of("PathVerificationService");
    }
}
