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

package org.openkilda.floodlight.command.ping;

import org.openkilda.floodlight.command.Command;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.kafka.KafkaMessageProducer;
import org.openkilda.floodlight.service.ConfigService;
import org.openkilda.floodlight.service.PingService;
import org.openkilda.messaging.floodlight.response.PingResponse;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.model.Ping;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

abstract class Abstract extends Command {
    protected static Logger logPing = LoggerFactory.getLogger("open-kilda.flows.PING");

    private final KafkaMessageProducer kafkaProducer;
    private final ConfigService configService;
    private final PingService pingService;

    Abstract(CommandContext context) {
        super(context);

        FloodlightModuleContext moduleContext = getContext().getModuleContext();
        configService = moduleContext.getServiceImpl(ConfigService.class);
        pingService = moduleContext.getServiceImpl(PingService.class);
        kafkaProducer = moduleContext.getServiceImpl(KafkaMessageProducer.class);
    }

    void sendErrorResponse(UUID pingId, Ping.Errors errorCode) {
        PingResponse response = new PingResponse(pingId, errorCode);
        sendResponse(response);
    }

    void sendResponse(PingResponse response) {
        String topic = configService.getTopics().getPingTopic();
        InfoMessage message = new InfoMessage(response, System.currentTimeMillis(), getContext().getCorrelationId());
        kafkaProducer.postMessage(topic, message);
    }

    protected PingService getPingService() {
        return pingService;
    }
}
