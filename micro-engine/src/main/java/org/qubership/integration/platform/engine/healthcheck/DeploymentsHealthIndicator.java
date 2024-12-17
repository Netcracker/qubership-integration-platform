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

import org.qubership.integration.platform.engine.consul.DeploymentReadinessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.availability.ReadinessStateHealthIndicator;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DeploymentsHealthIndicator extends ReadinessStateHealthIndicator {

    private final DeploymentReadinessService deploymentReadinessService;

    @Autowired
    public DeploymentsHealthIndicator(DeploymentReadinessService deploymentReadinessService, ApplicationAvailability availability) {
        super(availability);
        this.deploymentReadinessService = deploymentReadinessService;
    }

    @Override
    protected AvailabilityState getState(ApplicationAvailability applicationAvailability) {
        return deploymentReadinessService.isInitialized() ?
            super.getState(applicationAvailability) :
            ReadinessState.REFUSING_TRAFFIC;
    }
}
