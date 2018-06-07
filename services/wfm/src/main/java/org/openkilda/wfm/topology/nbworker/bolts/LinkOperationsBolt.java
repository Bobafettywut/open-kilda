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

package org.openkilda.wfm.topology.nbworker.bolts;

import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.nbtopology.request.BaseRequest;
import org.openkilda.messaging.nbtopology.request.GetLinkPropsRequest;
import org.openkilda.messaging.nbtopology.request.GetLinksRequest;
import org.openkilda.messaging.nbtopology.response.LinkPropsData;
import org.openkilda.pce.provider.Auth;
import org.openkilda.wfm.topology.nbworker.converters.LinksConverter;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class LinkOperationsBolt extends NeoOperationsBolt {

    private static final Logger logger = LoggerFactory.getLogger(LinkOperationsBolt.class);

    public LinkOperationsBolt(Auth neoAuth) {
        super(neoAuth);
    }

    @Override
    List<? extends InfoData> processRequest(Tuple tuple, BaseRequest request, Session session) {
        List<? extends InfoData> result = null;
        if (request instanceof GetLinksRequest) {
            result = getAllLinks(session);
        } else if (request instanceof GetLinkPropsRequest) {
            result = getLinkProps((GetLinkPropsRequest) request, session);
        } else {
            unhandledInput(tuple);
        }

        return result;
    }

    private List<IslInfoData> getAllLinks(Session session) {
        logger.debug("Processing get all links request");
        String q =
                "MATCH (:switch)-[isl:isl]->(:switch) "
                        + "RETURN isl";

        StatementResult queryResults = session.run(q);
        List<IslInfoData> results = queryResults.list()
                .stream()
                .map(record -> record.get("isl"))
                .map(Value::asRelationship)
                .map(LinksConverter::toIslInfoData)
                .collect(Collectors.toList());
        logger.debug("Found {} links in the database", results.size());
        return results;
    }

    private List<LinkPropsData> getLinkProps(GetLinkPropsRequest request, Session session) {
        logger.debug("Processing get link props request");
        String q = String.format(
                "MATCH (props:link_props) "
                        + "WHERE (%1$s IS NULL OR props.src_switch='%1$s') "
                        + "AND (%2$s IS NULL OR props.src_port=%2$s) "
                        + "AND (%3$s IS NULL OR props.dst_switch='%3$s') "
                        + "AND (%4$s IS NULL OR props.dst_port=%4$s) "
                        + "RETURN props",
                request.getSource().getSwitchDpId(), request.getSource().getPortId(),
                request.getDestination().getSwitchDpId(), request.getDestination().getPortId());

        StatementResult queryResults = session.run(q);
        List<LinkPropsData> results = queryResults.list()
                .stream()
                .map(record -> record.get("props"))
                .map(Value::asNode)
                .map(LinksConverter::toLinkPropsData)
                .collect(Collectors.toList());

        logger.debug("Found {} link props in the database", results.size());
        return results;
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("response", "correlationId"));
    }

    @Override
    Logger getLogger() {
        return logger;
    }

}
