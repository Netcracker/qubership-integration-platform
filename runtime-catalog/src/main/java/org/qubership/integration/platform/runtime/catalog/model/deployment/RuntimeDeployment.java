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

package org.qubership.integration.platform.runtime.catalog.model.deployment;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.catalog.model.deployment.engine.EngineDeployment;

import java.util.HashMap;
import java.util.Map;

@Data
@Slf4j
public class RuntimeDeployment {

    private String deploymentId;

    private String serviceName;

    // <engine_host, state>
    private final Map<String, EngineDeployment> states = new HashMap<>();

    public RuntimeDeployment(String deploymentId) {
        this.deploymentId = deploymentId;
    }
}
