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
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.engine.consul.KVNotFoundException;
import org.qubership.integration.platform.engine.consul.updates.UpdateGetterHelper;
import org.qubership.integration.platform.engine.model.ChainRuntimeProperties;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;
import org.qubership.integration.platform.engine.service.CheckpointSessionService;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.contextstorage.ContextStorageService;
import org.qubership.integration.platform.engine.service.debugger.ChainRuntimePropertiesService;

import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class TasksScheduler {
    @Inject
    VariablesService variableService;

    @Inject
    CheckpointSessionService checkpointSessionService;

    @Inject
    @Named("librariesUpdateGetter")
    UpdateGetterHelper<List<CompiledLibraryUpdate>> librariesUpdateGetter;

    @Inject
    @Named("chainRuntimePropertiesUpdateGetter")
    UpdateGetterHelper<Map<String, ChainRuntimeProperties>> chainRuntimePropertiesUpdateGetter;

    @Inject
    ChainRuntimePropertiesService chainRuntimePropertiesService;

    @ConfigProperty(name = "qip.sessions.checkpoints.cleanup.interval")
    String checkpointsInterval;

    @Inject
    ContextStorageService contextStorageService;

    @Scheduled(
            every = "PT2.5S",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class,
            executeWith = Scheduled.SIMPLE
    )
    public void refreshCommonVariables() {
        variableService.refreshCommonVariables();
    }

    @Scheduled(
            every = "5s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class,
            executeWith = Scheduled.SIMPLE
    )
    public void refreshSecuredVariables() {
        variableService.refreshSecuredVariables();
    }

    @Scheduled(
            cron = "${qip.sessions.checkpoints.cleanup.cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class,
            executeWith = Scheduled.SIMPLE
    )
    public void cleanupCheckpointSessions() {
        checkpointSessionService.deleteOldRecordsByInterval(checkpointsInterval);
        log.info("Scheduled checkpoints cleanup completed");
    }

    @Scheduled(
            cron = "${qip.context-service.cleanup.cron}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class,
            executeWith = Scheduled.SIMPLE
    )
    public void cleanupContextStorage() {
        contextStorageService.deleteOldRecords();
        log.info("Scheduled context record cleanup completed");
    }

    @Scheduled(
            every = "1s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class,
            executeWith = Scheduled.SIMPLE
    )
    public void checkRuntimeDeploymentProperties() {
        try {
            chainRuntimePropertiesUpdateGetter.checkForUpdates(
                    chainRuntimePropertiesService::updateRuntimeProperties);
        } catch (KVNotFoundException e) {
            log.debug("Chain runtime properties KV is empty. {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get chain runtime properties from consul", e);
        }
    }
}
