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

package org.openkilda.wfm.topology.ping.bolt;

import org.openkilda.messaging.Utils;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.AbstractException;
import org.openkilda.wfm.error.JsonEncodeException;
import org.openkilda.wfm.error.PipelineException;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

public class SpeakerEncoder extends Abstract {
    public static final String BOLT_ID = ComponentId.SPEAKER_ENCODER.toString();

    public static final String FIELD_ID_PAYLOAD = "payload";

    public static final Fields STREAM_FIELDS = new Fields(
            FieldNameBasedTupleToKafkaMapper.BOLT_KEY, FieldNameBasedTupleToKafkaMapper.BOLT_MESSAGE);

    @Override
    protected void handleInput(Tuple input) throws AbstractException {
        CommandData payload = pullPayload(input);
        CommandMessage message = wrap(pullContext(input), payload);
        String json = encode(message);

        getOutput().emit(new Values(null, json));
    }

    private CommandData pullPayload(Tuple input) throws PipelineException {
        CommandData value;
        try {
            value = (CommandData) input.getValueByField(FIELD_ID_PAYLOAD);
        } catch (ClassCastException e) {
            throw new PipelineException(this, input, FIELD_ID_PAYLOAD, e.toString());
        }
        return value;
    }

    private CommandMessage wrap(CommandContext commandContext, CommandData payload) {
        return new CommandMessage(payload, System.currentTimeMillis(), commandContext.getCorrelationId());
    }

    private String encode(CommandMessage message) throws JsonEncodeException {
        String value;
        try {
            value = Utils.MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new JsonEncodeException(message, e);
        }
        return value;
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputManager) {
        outputManager.declare(STREAM_FIELDS);
    }
}
