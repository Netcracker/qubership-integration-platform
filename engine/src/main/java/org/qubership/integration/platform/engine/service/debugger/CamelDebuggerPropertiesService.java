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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.consul.ConsulService;
import org.qubership.integration.platform.engine.consul.KVNotFoundException;
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
@RequiredArgsConstructor
public class CamelDebuggerPropertiesService {

    private final ConsulService consulService;
    private volatile long deploymentRefreshCount;

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

    public synchronized void refreshRuntimePropertiesForDeployment() {
        try {
            runtimePropertiesCacheRef.set(consulService.readChainRuntimeConfig());
            deploymentRefreshCount++;
        } catch (Exception e) {
            log.warn("Failed to refresh runtime properties before deployment", e);
        }
    }

    public void watchRuntimeProperties() {
        long refreshCount = deploymentRefreshCount;
        try {
            Pair<Boolean, Map<String, DeploymentRuntimeProperties>> update = consulService.waitForChainRuntimeConfig();
            if (Boolean.TRUE.equals(update.getLeft()) && !applyWatchUpdate(refreshCount, update.getRight())) {
                consulService.rollbackChainsRuntimeConfigLastIndex();
            }
        } catch (KVNotFoundException e) {
            log.debug("Runtime deployments properties KV is empty. {}", e.getMessage());
            applyWatchUpdate(refreshCount, Collections.emptyMap());
        } catch (Exception e) {
            log.error("Failed to get runtime deployments properties from consul", e);
            consulService.rollbackChainsRuntimeConfigLastIndex();
        }
    }

    private synchronized boolean applyWatchUpdate(long refreshCount, Map<String, DeploymentRuntimeProperties> properties) {
        // The watch may have read an older snapshot while a deployment refreshed the cache.
        if (refreshCount != deploymentRefreshCount) {
            return false;
        }
        runtimePropertiesCacheRef.set(properties);
        return true;
    }

    public void removeDeployProperties(String deploymentId) {
        deployPropertiesCache.remove(deploymentId);
    }
}
