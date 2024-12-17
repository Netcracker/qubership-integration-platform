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

package org.qubership.integration.platform.designtime.catalog.scheduler;

import org.qubership.integration.platform.catalog.consul.ConsulService;
import org.qubership.integration.platform.catalog.consul.exception.KVNotFoundException;
import org.qubership.integration.platform.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.catalog.service.ActionsLogService;
import org.qubership.integration.platform.designtime.catalog.service.ChainRuntimePropertiesService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.transaction.Transactional;
import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class TasksScheduler {
    private final ActionsLogService actionsLogService;
    private final ConsulService consulService;
    private final ChainRuntimePropertiesService chainRuntimePropertiesService;

    @Value("${qip.actions-log.cleanup.interval}")
    private String actionLogInterval;

    @Autowired
    public TasksScheduler(ActionsLogService actionsLogService,
                          ConsulService consulService,
                          ChainRuntimePropertiesService chainRuntimePropertiesService) {
        this.actionsLogService = actionsLogService;
        this.consulService = consulService;
        this.chainRuntimePropertiesService = chainRuntimePropertiesService;
    }

    @Scheduled(cron = "${qip.actions-log.cleanup.cron}")
    @Transactional
    public void cleanupActionsLog() {
        actionsLogService.deleteAllOldRecordsByInterval(actionLogInterval);
        log.info("Remove old records from actions log table");
    }

    @Scheduled(fixedDelay = 1000)
    public void checkRuntimeDeploymentProperties() {
        try {
            Pair<Boolean, Map<String, DeploymentRuntimeProperties>> response = consulService.waitForChainRuntimeConfig();
            if (response.getLeft()) { // changes detected
                chainRuntimePropertiesService.updateCache(response.getRight());
            }
        } catch (KVNotFoundException kvnfe) {
            log.debug("Runtime deployments properties KV is empty. {}", kvnfe.getMessage());
            chainRuntimePropertiesService.updateCache(Collections.emptyMap());
        } catch (Exception e) {
            log.error("Failed to get runtime deployments properties from consul", e);
            consulService.rollbackChainsRuntimeConfigLastIndex();
        }
    }
}
