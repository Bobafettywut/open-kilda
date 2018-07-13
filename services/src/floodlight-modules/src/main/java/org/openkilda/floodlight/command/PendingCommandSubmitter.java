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

package org.openkilda.floodlight.command;

import org.openkilda.floodlight.service.CommandProcessorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class PendingCommandSubmitter extends Command {
    private static Logger log = LoggerFactory.getLogger(PendingCommandSubmitter.class);

    private final CommandProcessorService commandProcessor;
    private final List<CommandProcessorService.ProcessorTask> tasks;

    public PendingCommandSubmitter(
            CommandContext context, CommandProcessorService commandProcessor,
            List<CommandProcessorService.ProcessorTask> tasks) {
        super(context);
        this.commandProcessor = commandProcessor;
        this.tasks = tasks;
    }

    @Override
    public Command call() throws Exception {
        try {
            int count;
            do {
                count = tasks.size();
                lookupAndSubmit();
            } while (count != tasks.size());
        } catch (InterruptedException e) {
            Thread thread = Thread.currentThread();
            thread.interrupt();

            log.warn("{} in thread {} have been interrupted", getClass(), thread.getName());
        }

        commandProcessor.submitPending(tasks);

        return null;
    }

    private void lookupAndSubmit() throws InterruptedException {
        for (Iterator<CommandProcessorService.ProcessorTask> iterator = tasks.iterator(); iterator.hasNext(); ) {
            CommandProcessorService.ProcessorTask entry = iterator.next();

            Command command = consumeResult(entry);
            if (command != null) {
                iterator.remove();

                commandProcessor.processLazy(command);
            }
        }
    }

    private Command consumeResult(CommandProcessorService.ProcessorTask task) throws InterruptedException {
        if (! task.pendingSuccessor.isDone()) {
            return null;
        }

        Command successor;
        try {
            successor = task.pendingSuccessor.get();
        } catch (ExecutionException e) {
            successor = task.initiator.exceptional(e);
        }
        return successor;
    }
}
