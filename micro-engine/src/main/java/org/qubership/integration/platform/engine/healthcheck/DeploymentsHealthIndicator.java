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

package org.qubership.integration.platform.engine.healthcheck;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;

@Slf4j
@Readiness
@ApplicationScoped
public class DeploymentsHealthIndicator implements HealthCheck {
    private static final String DEPLOYMENTS_HEALTH_INDICATOR_NAME = "Deployments";
    private static final String DEPLOYMENTS_HEALTH_INDICATOR_STATE = "state";

    @Inject
    DeploymentReadinessService deploymentReadinessService;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(DEPLOYMENTS_HEALTH_INDICATOR_NAME);
        if (deploymentReadinessService.isInitialized()) {
            builder.withData(
                    DEPLOYMENTS_HEALTH_INDICATOR_STATE,
                    "Ready to process deployments."
            ).up();
        } else if (deploymentReadinessService.isReadyForDeploy()) {
            builder.withData(
                    DEPLOYMENTS_HEALTH_INDICATOR_STATE,
                    "Deployments is not initialized yet."
            ).down();
        } else {
            builder.withData(
                    DEPLOYMENTS_HEALTH_INDICATOR_STATE,
                    "At least one required event was not received to start deployments processing."
            )
            .down();
        }
        return builder.build();
    }
}
