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

package org.qubership.integration.platform.runtime.catalog.events;

import lombok.Getter;
import org.qubership.integration.platform.catalog.model.deployment.engine.EngineDeployment;
import org.qubership.integration.platform.catalog.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.springframework.context.ApplicationEvent;

@Getter
public class EngineStateUpdateEvent extends ApplicationEvent {
    private final EngineInfo engineInfo;
    private final EngineDeployment engineDeployment;
    private final DeploymentService.LoggingInfo loggingInfo;
    private final String userId;
    private final String tenantId;

    public EngineStateUpdateEvent(Object source, EngineInfo engineInfo, EngineDeployment engineDeployment, DeploymentService.LoggingInfo loggingInfo, String userId, String tenantId) {
        super(source);
        this.engineInfo = engineInfo;
        this.engineDeployment = engineDeployment;
        this.loggingInfo = loggingInfo;
        this.userId = userId;
        this.tenantId = tenantId;
    }
}
