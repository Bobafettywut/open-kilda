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

package org.openkilda.floodlight.service.batch;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;

import java.util.LinkedList;

class OfBatchSwitchQueue {
    private final DatapathId dpId;
    private final LinkedList<OfBatch> queue = new LinkedList<>();
    private boolean garbage = true;

    public OfBatchSwitchQueue(DatapathId dpId) {
        this.dpId = dpId;
    }

    synchronized public void add(OfBatch batch) {
        queue.addLast(batch);
        garbage = false;
    }

    synchronized public void cleanup() {
        queue.removeIf(OfBatch::isComplete);
    }

    synchronized OfBatch receiveResponse(OFMessage response) {
        OfBatch match = null;
        for (OfBatch entry : queue) {
            if (!entry.receiveResponse(dpId, response)) {
                continue;
            }

            match = entry;
            break;
        }

        garbage = queue.size() == 0;
        return match;
    }

    public boolean isGarbage() {
        return garbage;
    }
}
