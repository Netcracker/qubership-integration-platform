/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.consul;

import org.qubership.integration.platform.engine.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineState;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EngineStateReporter extends Thread {

    public static final int REPORT_RETRY_DELAY = 5000;
    public static final int QUEUE_CAPACITY = 128;
    private final ConsulService consulService;
    private final MetricsService metricsService;

    private final BlockingQueue<EngineState> statesQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    @Autowired
    public EngineStateReporter(ConsulService consulService, MetricsService metricsService) {
        this.consulService = consulService;
        this.metricsService = metricsService;
        this.start();
    }

    public void addStateToQueue(EngineState state) {
        if (!statesQueue.offer(state)) {
            log.error("Queue of engine states is full, state is not added");
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                EngineState state = statesQueue.take();
                while (true) {
                    try {
                        consulService.updateEnginesState(state);
                        updateDeploymentMetrics(state);

                        break;
                    } catch (Exception e1) {
                        log.error("Failed to report engine state",  e1);

                        try {
                            Thread.sleep(REPORT_RETRY_DELAY);
                        } catch (InterruptedException e2) {
                            throw new RuntimeException(e2);
                        }
                    }
                }
            } catch (InterruptedException ignored) {}
        }
    }

    private void updateDeploymentMetrics(EngineState state) {
        for (Map.Entry<String, EngineDeployment> deploymentsEntry : state.getDeployments().entrySet()) {
            metricsService.processChainsDeployments(deploymentsEntry.getValue());
        }
    }
}
