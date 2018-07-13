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

import org.openkilda.floodlight.command.PendingCommandSubmitter;
import org.openkilda.floodlight.command.Command;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.utils.CommandContextFactory;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.threadpool.IThreadPoolService;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CommandProcessorService implements IFloodlightService {
    private static final int FUTURE_COMPLETE_CHECK_INTERVAL = 200;

    private final CommandContextFactory commandContextFactory;

    private ExecutorService executor = Executors.newCachedThreadPool();

    private LinkedList<ProcessorTask> tasks = new LinkedList<>();

    public CommandProcessorService(CommandContextFactory commandContextFactory) {
        this.commandContextFactory = commandContextFactory;
    }

    public void init(FloodlightModuleContext moduleContext) {
        scheduleFutureCheckTrigger(moduleContext.getServiceImpl(IThreadPoolService.class).getScheduledExecutor());
    }

    public void process(Command command) {
        processLazy(command);
        pendingCheckTrigger();
    }

    public void process(List<Command> commands) {
        for (Command entry : commands) {
            this.processLazy(entry);
        }
        pendingCheckTrigger();
    }

    public void processLazy(Command command) {
        Future<Command> successor = executor.submit(command);
        synchronized (this) {
            tasks.addLast(new ProcessorTask(command, successor));
        }
    }

    public synchronized void submitPending(List<ProcessorTask> pending) {
        tasks.addAll(pending);
    }

    public synchronized void submitPending(Command initiator, Future<Command> successor) {
        tasks.add(new ProcessorTask(initiator, successor));
    }

    private void pendingCheckTrigger() {
        LinkedList<ProcessorTask> commands = rotatePendingCommands();
        if (commands.size() == 0) {
            return;
        }

        CommandContext context = commandContextFactory.produce();
        PendingCommandSubmitter checkCommands = new PendingCommandSubmitter(context, this, commands);
        processLazy(checkCommands);
    }

    private synchronized LinkedList<ProcessorTask> rotatePendingCommands() {
        LinkedList<ProcessorTask> current = tasks;
        tasks = new LinkedList<>();
        return current;
    }

    private void scheduleFutureCheckTrigger(ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                pendingCheckTrigger();
            }
        }, FUTURE_COMPLETE_CHECK_INTERVAL, FUTURE_COMPLETE_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public static final class ProcessorTask {
        public final Command initiator;
        public final Future<Command> pendingSuccessor;

        public ProcessorTask(Command initiator, Future<Command> pendingSuccessor) {
            this.initiator = initiator;
            this.pendingSuccessor = pendingSuccessor;
        }
    }
}
