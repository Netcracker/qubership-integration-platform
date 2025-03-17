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

package org.qubership.integration.platform.runtime.catalog.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.catalog.consul.ConsulService;
import org.qubership.integration.platform.catalog.consul.exception.KVNotFoundException;
import org.qubership.integration.platform.catalog.model.deployment.engine.EngineState;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.List;

@Slf4j
@Component
public class TasksScheduler {
    public static final String CHECK_FAILED_MSG = "Failed to check engines state in consul";
    private final ConsulService consulService;
    private final RuntimeDeploymentService runtimeDeploymentService;
    private final ActionsLogService actionsLogService;

    @Value("${qip.actions-log.cleanup.interval}")
    private String actionLogInterval;

    @Autowired
    public TasksScheduler(ConsulService consulService,
                          RuntimeDeploymentService runtimeDeploymentService,
                          ActionsLogService actionsLogService) {
        this.consulService = consulService;
        this.runtimeDeploymentService = runtimeDeploymentService;
        this.actionsLogService = actionsLogService;
    }

    @Scheduled(cron = "${qip.actions-log.cleanup.cron}")
    public void cleanupActionsLog() {
        actionsLogService.deleteAllOldRecordsByInterval(actionLogInterval);

        log.info("Remove old records from actions log table");
    }

    @Scheduled(fixedDelay = 2500)
    public void checkEnginesState() {
        try {
            // lock thread and wait for update (until the timeout is exceeded)
            Pair<Boolean, List<EngineState>> pair = consulService.waitForEnginesStateUpdate();
            if (pair.getLeft()) { // changes detected
                log.debug("Engines state changes detected");
                runtimeDeploymentService.provideEnginesStateUpdate(pair.getRight());
            }
        } catch (KVNotFoundException kvnfe) {
            log.warn("Engines state KV is empty. {}", kvnfe.getMessage());
        } catch (CannotCreateTransactionException ccte) {
            log.error(CHECK_FAILED_MSG + ", {}", ccte.getMessage());
        } catch (ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException) {
                log.warn(CHECK_FAILED_MSG + ", consul unavailable or too small timeout. Error message: {}", rae.getMessage());
            } else {
                log.error(CHECK_FAILED_MSG, rae);
            }
        } catch (Exception e) {
            log.error(CHECK_FAILED_MSG, e);
        }
    }

    /**
     * Check deployments update in runtime-catalog
     */
    @Scheduled(fixedDelay = 2500)
    public void checkDeploymentUpdates() {
        try {
            // block thread and wait for update (until the timeout is exceeded)
            Pair<Boolean, Long> response = consulService.waitForDeploymentsUpdate();
            if (response.getLeft()) { // changes detected
                DeploymentService.clearDeploymentsUpdateCache(response.getRight());
            }
        } catch (KVNotFoundException kvnfe) {
            log.debug("Deployments update KV is empty. {}", kvnfe.getMessage());
        }
    }
}
