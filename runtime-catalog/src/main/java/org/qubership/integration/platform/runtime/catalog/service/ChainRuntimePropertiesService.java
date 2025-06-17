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

package org.qubership.integration.platform.runtime.catalog.service;


import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.consul.ConsulService;
import org.qubership.integration.platform.runtime.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ChainRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.logging.properties.ChainLoggingPropertiesSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class ChainRuntimePropertiesService {
    public static final String DEFAULT_CONSUL_SETTING_KEY = "default-settings";

    // <chain_id, debugger_props(deployment & chain)>
    private Map<String, DeploymentRuntimeProperties> runtimePropertiesCache = Collections.emptyMap();

    private final ConsulService consulService;
    private final ActionsLogService actionLogger;
    private final ChainRepository chainRepository;

    @Autowired
    public ChainRuntimePropertiesService(ConsulService consulService,
                                         ActionsLogService actionLogger,
                                         ChainRepository chainRepository) {
        this.consulService = consulService;
        this.actionLogger = actionLogger;
        this.chainRepository = chainRepository;
    }


    public void saveRuntimeProperties(String chainId, DeploymentRuntimeProperties request) {
        consulService.updateChainRuntimeConfig(chainId, request);
        logChainAction(chainId, LogOperation.CREATE_OR_UPDATE);
    }

    public void deleteCustomRuntimeProperties(String chainId) {
        consulService.deleteChainRuntimeConfig(chainId);
        logChainAction(chainId, LogOperation.DELETE);
    }

    // <useCustomSettings, properties>
    public ChainLoggingPropertiesSet getRuntimeProperties(String chainId) {
        ChainLoggingPropertiesSet.ChainLoggingPropertiesSetBuilder builder =
                ChainLoggingPropertiesSet.builder()
                        .fallbackDefault(DeploymentRuntimeProperties.getDefaultValues());

        if (chainId != null && runtimePropertiesCache != null) {
            if (runtimePropertiesCache.containsKey(chainId)) {
                builder.custom(runtimePropertiesCache.get(chainId));
            }

            if (runtimePropertiesCache.containsKey(DEFAULT_CONSUL_SETTING_KEY)) {
                builder.consulDefault(runtimePropertiesCache.get(DEFAULT_CONSUL_SETTING_KEY));
            }
        }

        return builder.build();
    }

    public Map<String, DeploymentRuntimeProperties> getRuntimePropertiesCache() {
        if (runtimePropertiesCache != null && !runtimePropertiesCache.isEmpty()) {
            return runtimePropertiesCache;
        }
        return Collections.emptyMap();
    }

    public void updateCache(Map<String, DeploymentRuntimeProperties> propertiesMap) {
        runtimePropertiesCache = propertiesMap;
    }

    private void logChainAction(String chainId, LogOperation operation) {
        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.CHAIN_RUNTIME_PROPERTIES)
                .parentType(EntityType.CHAIN)
                .parentId(chainId)
                .parentName(chainRepository.findById(chainId).map(AbstractEntity::getName).orElse(null))
                .operation(operation)
                .build());
    }
}
