/* Copyright 2017 Telstra Open Source
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

package org.openkilda.wfm.topology.event;

import org.openkilda.messaging.ServiceType;
import org.openkilda.wfm.ConfigurationException;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.splitter.InfoEventSplitterBolt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.topology.BoltDeclarer;
import org.apache.storm.topology.IStatefulBolt;
import org.apache.storm.topology.TopologyBuilder;

/**
 * OFEventWFMTopology creates the topology to manage these key aspects of OFEvents:
 * <p>
 * (1) Switch UP/DOWN
 * (2) Port UP/DOWN
 * (3) Link UP/DOWN (ISL) - and health manager
 */
public class OFEventWFMTopology extends AbstractTopology {
    /*
     * Progress Tracker - Phase 1: Simple message flow, a little bit of state, re-wire spkr/tpe
     * (1) √ Switch UP - Simple pass through
     * (2) ◊ Switch Down - Simple pass through (LinkBolt will stop Link Discovery / Health)
     * (3) √ Port UP - Simple Pass through (will be picked up by Link bolts, Discovery started)
     * (4) ◊ Port DOWN - Simple Pass through (LinkBolt will stop Link Discovery / Health)
     * (5) ◊ Link UP - this will be a response from the Discovery packet.
     *          ◊ Put message on kilda.wfm.topo.updown
     * (6) ◊ Link DOWN - this will be a response from the Discovery packet
     *          ◊ Put message on kilda.wfm.topo.updown
     * (7) ◊ Re-wire Speaker (use kilda.speaker) and TPE (use kilda.wfm.topo.updown)
     * (8) ◊ Add simple pass through for verification (w/ speaker) & validation (w/ TPE)
     */

    private static Logger logger = LogManager.getLogger(OFEventWFMTopology.class);

    /**
     * This is the primary input topics
     */
    private String[] topics = {
            InfoEventSplitterBolt.I_SWITCH_UPDOWN,
            InfoEventSplitterBolt.I_PORT_UPDOWN,
            InfoEventSplitterBolt.I_ISL_UPDOWN
    };

    public OFEventWFMTopology(LaunchEnvironment env) throws ConfigurationException {
        super(env);
    }

    public StormTopology createTopology() {
        logger.debug("Building Topology - " + this.getClass().getSimpleName());

        initKafka();

        String kafkaOutputTopic = config.getKafkaOutputTopic();
        TopologyBuilder builder = new TopologyBuilder();
        BoltDeclarer kbolt = builder.setBolt(kafkaOutputTopic + "-kafkabolt",
                createKafkaBolt(kafkaOutputTopic), config.getParallelism());

        // The order of bolts should match topics, and Link should be last .. logic below relies on it
        IStatefulBolt[] bolts = {
                new OFESwitchBolt().withOutputStreamId(kafkaOutputTopic),
                new OFEPortBolt().withOutputStreamId(kafkaOutputTopic),
                new OFELinkBolt(config)
        };

        // tbolt will save the setBolt() results; will be useed to add switch/port to link
        BoltDeclarer[] tbolt = new BoltDeclarer[bolts.length];

        for (int i = 0; i < topics.length; i++) {
            String topic = topics[i];
            String spoutName = topic + "-spout";
            String boltName = topic + "-bolt";

            builder.setSpout(spoutName, createKafkaSpout(topic, getTopologyName()));

            // NB: with shuffleGrouping, we can't maintain state .. would need to parse first
            //      just to pull out switchID.
            tbolt[i] = builder.setBolt(boltName, bolts[i], config.getParallelism()).shuffleGrouping(spoutName);
            kbolt.shuffleGrouping(boltName, kafkaOutputTopic);
        }

        // now hookup switch and port to the link bolt so that it can take appropriate action
        tbolt[2].shuffleGrouping(topics[0] + "-bolt", kafkaOutputTopic)
                .shuffleGrouping(topics[1] + "-bolt", kafkaOutputTopic);

        // finally, one more bolt, to write the ISL Discovery requests
        String discoTopic = config.getKafkaInputTopic();
        builder.setBolt("ISL_Discovery-kafkabolt", createKafkaBolt(discoTopic), config.getParallelism())
                .shuffleGrouping(topics[2] + "-bolt", discoTopic);

        createHealthCheckHandler(builder, ServiceType.WFM_TOPOLOGY.getId());

        return builder.createTopology();
    }

    /*
     * Progress Tracker - Phase 2: Speaker / TPE Integration; Cache Coherency Checks; Flapping
     * (1) ◊ - Interact with Speaker (network element is / isn't there)
     * (2) ◊ - Interact with TPE (graph element is / isn't there)
     * (3) ◊ - Validate the Topology periodically - switches, ports, links
     *          - health checks should validate the known universe; what about missing stuff?
     * (4) ◊ - See if flapping happens .. define window and if there are greater than 4 up/downs?
     * (5) ◊ -
     * (6) ◊ -
     * (7) ◊ -
     * (8) ◊ -
     * (9) ◊ -
     */

    /*
     * Progress Tracker - Phase 3: ?
     * (1) ◊ -
     * (2) ◊ -
     * (3) ◊ -
     * (4) ◊ -
     * (5) ◊ -
     * (6) ◊ -
     * (7) ◊ -
     * (8) ◊ -
     * (9) ◊ -
     */

    private void initKafka() {
        checkAndCreateTopic(config.getKafkaOutputTopic());
        checkAndCreateTopic(config.getKafkaInputTopic());
        for (String topic : topics) {
            checkAndCreateTopic(topic);
        }
    }

    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new OFEventWFMTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}