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

package org.qubership.integration.platform.engine.scheduler;

import org.qubership.integration.platform.engine.consul.ConsulService;
import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;
import org.qubership.integration.platform.engine.consul.KVNotFoundException;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.DeploymentsUpdateService;
import org.qubership.integration.platform.engine.service.IntegrationRuntimeService;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TasksScheduler {

    private final VariablesService variableService;
    private final IntegrationRuntimeService runtimeService;
    private final CheckpointSessionService checkpointSessionService;
    private final DeploymentReadinessService deploymentReadinessService;
    private final ConsulService consulService;
    private final DeploymentsUpdateService deploymentsUpdateService;
    private final Optional<ExternalLibraryService> externalLibraryService;
    private final CamelDebuggerPropertiesService debuggerPropertiesService;

    @Value("${qip.sessions.checkpoints.cleanup.interval}")
    private String checkpointsInterval;

    @Autowired
    public TasksScheduler(VariablesService variableService,
                            IntegrationRuntimeService runtimeService,
                            CheckpointSessionService checkpointSessionService,
                            DeploymentReadinessService deploymentReadinessService,
                            ConsulService consulService,
                            DeploymentsUpdateService deploymentsUpdateService,
                            Optional<ExternalLibraryService> externalLibraryService,
                            CamelDebuggerPropertiesService debuggerPropertiesService) {
        this.variableService = variableService;
        this.runtimeService = runtimeService;
        this.checkpointSessionService = checkpointSessionService;
        this.deploymentReadinessService = deploymentReadinessService;
        this.consulService = consulService;
        this.deploymentsUpdateService = deploymentsUpdateService;
        this.externalLibraryService = externalLibraryService;
        this.debuggerPropertiesService = debuggerPropertiesService;
    }


    @Scheduled(fixedDelay = 2500)
    public void refreshCommonVariables() {
        try {
            Pair<Boolean, Map<String, String>> pair = consulService.waitForCommonVariables();
            if (pair.getLeft()) { // changes detected
                log.debug("Common variables changes detected");
                variableService.updateCommonVariables(pair.getRight());
            }
        } catch (KVNotFoundException kvnfe) {
            log.debug("Common variables KV is empty. {}", kvnfe.getMessage());
            variableService.updateCommonVariables(Collections.emptyMap());
        } catch (Exception e) {
            log.error("Failed to update common variables. {}", e.getMessage());
            consulService.rollbackCommonVariablesLastIndex();
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void refreshSecuredVariables() {
        variableService.refreshSecuredVariables();
    }

    @Scheduled(fixedDelayString = "${qip.deployments.retry-delay}", initialDelayString = "${qip.deployments.retry-delay}")
    public void retryProcessingDeploys() {
        if (deploymentReadinessService.isInitialized()) {
            runtimeService.retryProcessingDeploys();
        }
    }

    @Scheduled(cron = "${qip.sessions.checkpoints.cleanup.cron}")
    public void cleanupCheckpointSessions() {
        checkpointSessionService.deleteOldRecordsByInterval(checkpointsInterval);
        log.info("Scheduled checkpoints cleanup completed");
    }

    @Scheduled(fixedRate = ConsulService.SESSION_RENEW_DELAY)
    public void renewConsulSession() {
        consulService.createOrRenewSession();
    }

    /**
     * Check deployments update in runtime-catalog
     */
    @Scheduled(fixedDelay = 2500)
    public void checkDeploymentUpdates() {
        if (deploymentReadinessService.isReadyForDeploy()) {
            try {
                boolean firstDeploy = !deploymentReadinessService.isInitialized();

                if (firstDeploy) {
                    deploymentsUpdateService.getAndProcess();
                    runtimeService.startAllRoutesOnInit();
                    deploymentReadinessService.setInitialized(true);
                } else {
                    try {
                        // block thread and wait for update (until the timeout is exceeded)
                        Pair<Boolean, Long> response = consulService.waitForDeploymentsUpdate();
                        if (response.getLeft()) { // changes detected
                            deploymentsUpdateService.getAndProcess();
                        }
                    } catch (KVNotFoundException kvnfe) {
                        log.debug("Deployments update KV is empty. {}", kvnfe.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to get or process deployments from runtime catalog: {}", e.getMessage());
                consulService.rollbackDeploymentsStateLastIndex();
            }
        }
    }

    /**
     * Check libraries update
     */
    @Scheduled(fixedDelay = 2500)
    public void checkLibrariesUpdates() {
        if (externalLibraryService.isPresent()) {
            try {
                Pair<Boolean, List<CompiledLibraryUpdate>> response = consulService.waitForLibrariesUpdate();
                if (response.getLeft()) { // changes detected
                    externalLibraryService.get().updateSystemModelLibraries(response.getRight());
                }
            } catch (KVNotFoundException kvnfe) {
                log.warn("Libraries update KV is empty. {}", kvnfe.getMessage());
            } catch (Exception e) {
                log.error("Failed to get libraries update from consul/systems-catalog", e);
                consulService.rollbackLibrariesLastIndex();
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void checkRuntimeDeploymentProperties() {
        try {
            Pair<Boolean, Map<String, DeploymentRuntimeProperties>> response = consulService.waitForChainRuntimeConfig();
            if (response.getLeft()) { // changes detected
                debuggerPropertiesService.updateRuntimeProperties(response.getRight());
            }
        } catch (KVNotFoundException kvnfe) {
            log.debug("Runtime deployments properties KV is empty. {}", kvnfe.getMessage());
            debuggerPropertiesService.updateRuntimeProperties(Collections.emptyMap());
        } catch (Exception e) {
            log.error("Failed to get runtime deployments properties from consul", e);
            consulService.rollbackChainsRuntimeConfigLastIndex();
        }
    }
}
