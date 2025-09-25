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

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;
import org.qubership.integration.platform.engine.consul.KVNotFoundException;
import org.qubership.integration.platform.engine.consul.updates.UpdateGetterHelper;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.DeploymentsUpdateService;
import org.qubership.integration.platform.engine.service.IntegrationRuntimeService;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.contextstorage.ContextStorageService;
import org.qubership.integration.platform.engine.service.debugger.CamelDebuggerPropertiesService;
import org.qubership.integration.platform.engine.service.externallibrary.ExternalLibraryService;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@ApplicationScoped
public class TasksScheduler {
    @Inject
    VariablesService variableService;

    @Inject
    IntegrationRuntimeService runtimeService;

    @Inject
    CheckpointSessionService checkpointSessionService;

    @Inject
    DeploymentReadinessService deploymentReadinessService;

    @Inject
    @Named("deploymentUpdateGetter")
    UpdateGetterHelper<Long> deploymentUpdateGetter;

    @Inject
    @Named("librariesUpdateGetter")
    UpdateGetterHelper<List<CompiledLibraryUpdate>> librariesUpdateGetter;

    @Inject
    @Named("chainRuntimePropertiesUpdateGetter")
    UpdateGetterHelper<Map<String, DeploymentRuntimeProperties>> chainRuntimePropertiesUpdateGetter;

    @Inject
    @Named("commonVariablesUpdateGetter")
    UpdateGetterHelper<Map<String, String>> commonVariablesUpdateGetter;

    @Inject
    DeploymentsUpdateService deploymentsUpdateService;

    @Inject
    Instance<ExternalLibraryService> externalLibraryService;

    @Inject
    CamelDebuggerPropertiesService debuggerPropertiesService;

    @ConfigProperty(name = "qip.sessions.checkpoints.cleanup.interval")
    String checkpointsInterval;

    @Inject
    ContextStorageService contextStorageService;

    @Scheduled(
            every = "PT2.5S",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void refreshCommonVariables() {
        try {
            commonVariablesUpdateGetter.checkForUpdates(changes -> {
                log.debug("Common variables changes detected");
                variableService.updateCommonVariables(changes);
            });
        } catch (KVNotFoundException e) {
            log.debug("Common variables KV is empty. {}", e.getMessage());
            variableService.updateCommonVariables(Collections.emptyMap());
        } catch (Exception e) {
            log.error("Failed to update common variables. {}", e.getMessage());
        }
    }

    @Scheduled(
            every = "5s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void refreshSecuredVariables() {
        variableService.refreshSecuredVariables();
    }

    @Scheduled(
            every = "${qip.deployments.retry-delay}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void retryProcessingDeploys() {
        if (deploymentReadinessService.isInitialized()) {
            runtimeService.retryProcessingDeploys();
        }
    }

    @Scheduled(
            cron = "${qip.sessions.checkpoints.cleanup.cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void cleanupCheckpointSessions() {
        checkpointSessionService.deleteOldRecordsByInterval(checkpointsInterval);
        log.info("Scheduled checkpoints cleanup completed");
    }

    @Scheduled(
            cron = "${qip.context-service.cleanup.cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void cleanupContextStorage() {
        contextStorageService.deleteOldRecords();
        log.info("Scheduled context record cleanup completed");
    }

    @Scheduled(
            every = "PT2.5S",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void checkDeploymentUpdates() {
        if (!deploymentReadinessService.isReadyForDeploy()) {
            return;
        }

        boolean firstDeploy = !deploymentReadinessService.isInitialized();
        try {
            if (firstDeploy) {
                deploymentsUpdateService.getAndProcess();
                runtimeService.startAllRoutesOnInit();
                deploymentReadinessService.setInitialized(true);
            } else {
                deploymentUpdateGetter.checkForUpdates(changes -> {
                    try {
                        deploymentsUpdateService.getAndProcess();
                    } catch (ExecutionException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (KVNotFoundException e) {
            log.debug("Deployments update KV is empty. {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get or process deployments from runtime catalog: {}", e.getMessage());
        }
    }

    @Scheduled(
            every = "PT2.5S",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void checkLibrariesUpdates() {
        InjectUtil.injectOptional(externalLibraryService).ifPresent(libraryService -> {
            try {
                librariesUpdateGetter.checkForUpdates(libraryService::updateSystemModelLibraries);
            } catch (KVNotFoundException e) {
                log.warn("Libraries update KV is empty. {}", e.getMessage());
            } catch (Exception e) {
                log.error("Failed to get libraries update from consul/systems-catalog", e);
            }
        });
    }

    @Scheduled(
            every = "1s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class
    )
    public void checkRuntimeDeploymentProperties() {
        try {
            chainRuntimePropertiesUpdateGetter.checkForUpdates(
                    debuggerPropertiesService::updateRuntimeProperties);
        } catch (KVNotFoundException e) {
            log.debug("Runtime deployments properties KV is empty. {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get runtime deployments properties from consul", e);
        }
    }
}
