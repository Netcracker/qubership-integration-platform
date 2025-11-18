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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.create.before;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentUpdate;
import org.qubership.integration.platform.engine.service.deployment.processing.DeploymentProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeRoutesCreated;

import javax.sql.DataSource;

@Slf4j
@ApplicationScoped
@OnBeforeRoutesCreated
@Priority(Integer.MAX_VALUE)
public class SchedulerRequirementsCheckAction implements DeploymentProcessingAction {
    private final DataSource qrtzDataSource;

    @Inject
    public SchedulerRequirementsCheckAction(
        @Named("quartz") DataSource qrtzDataSource
    ) {
        this.qrtzDataSource = qrtzDataSource;
    }

    @Override
    public void execute(
        CamelContext context,
        DeploymentUpdate deploymentUpdate
    ) {
        if (deploymentUpdate.getDeploymentInfo().isContainsSchedulerElements()) {
            checkSchedulerRequirements();
        }
    }

    private void checkSchedulerRequirements() {
        if (!isSchedulerDatabaseReady()) {
            log.warn("Failed to obtain DB connection for scheduler");
            throw new DeploymentRetriableException(
                "Failed to obtain DB connection for scheduler");
        } else {
            log.debug("Scheduler database is ready");
        }
    }

    private boolean isSchedulerDatabaseReady() {
        try (java.sql.Connection conn = qrtzDataSource.getConnection()) {
            return conn != null;
        } catch (Exception e) {
            log.warn("Scheduler database not ready", e);
        }
        return false;
    }
}
