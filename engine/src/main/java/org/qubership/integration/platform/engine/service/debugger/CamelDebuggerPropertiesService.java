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

package org.qubership.integration.platform.engine.service.debugger;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class CamelDebuggerPropertiesService {

    // <deployment_id, debugger_props(deployment & chain)>
    private final Map<String, AtomicReference<CamelDebuggerProperties>> deployPropertiesCache =
        new ConcurrentHashMap<>();

    // <chain_id, debugger_props(deployment & chain)>
    private final AtomicReference<Map<String, DeploymentRuntimeProperties>> runtimePropertiesCacheRef =
        new AtomicReference<>(Collections.emptyMap());

    public CamelDebuggerProperties getActualProperties(String deploymentId) {
        return getOrCreateDeploymentPair(deploymentId).get();
    }

    private AtomicReference<CamelDebuggerProperties> getOrCreateDeploymentPair(String deploymentId) {
        return deployPropertiesCache
            .computeIfAbsent(deploymentId, k -> new AtomicReference<>(null));
    }

    public CamelDebuggerProperties getProperties(Exchange exchange, String deploymentId) {
        CamelDebuggerProperties deployProperties = getActualProperties(deploymentId);
        DeploymentRuntimeProperties runtimeProperties = exchange.getProperty(
            Properties.DEPLOYMENT_RUNTIME_PROPERTIES_MAP_PROP, DeploymentRuntimeProperties.class);
        if (runtimeProperties == null) {
            exchange.setProperty(
                Properties.DEPLOYMENT_RUNTIME_PROPERTIES_MAP_PROP,
                deployProperties.getActualRuntimeProperties());
        }
        return deployProperties;
    }

    public void mergeWithRuntimeProperties(CamelDebuggerProperties deployProperties) throws RuntimePropertiesException {
        String deploymentId = deployProperties.getDeploymentInfo().getDeploymentId();

        getOrCreateDeploymentPair(deploymentId).set(deployProperties
            .toBuilder()
            .runtimePropertiesCacheRef(runtimePropertiesCacheRef)
            .build());
    }

    public void updateRuntimeProperties(Map<String, DeploymentRuntimeProperties> propertiesMap) {
        runtimePropertiesCacheRef.set(propertiesMap);
    }

    public void removeDeployProperties(String deploymentId) {
        deployPropertiesCache.remove(deploymentId);
    }
}
