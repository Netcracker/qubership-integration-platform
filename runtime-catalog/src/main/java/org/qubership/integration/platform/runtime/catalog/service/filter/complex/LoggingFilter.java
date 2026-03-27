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

package org.qubership.integration.platform.runtime.catalog.service.filter.complex;

import org.qubership.integration.platform.runtime.catalog.model.chain.SessionsLoggingLevel;
import org.qubership.integration.platform.runtime.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.service.ChainRuntimePropertiesService;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LoggingFilter implements FilterApplier {

    private final ChainRuntimePropertiesService chainRuntimePropertiesService;

    public LoggingFilter(ChainRuntimePropertiesService chainRuntimePropertiesService) {
        this.chainRuntimePropertiesService = chainRuntimePropertiesService;
    }

    @Override
    public List<Chain> apply(List<Chain> chains, List<FilterRequestDTO> filters) {
        List<FilterRequestDTO> loggingFilters = filters.stream()
                .filter(filter -> filter.getFeature() == FilterFeature.LOGGING)
                .toList();
        if (loggingFilters.isEmpty()) {
            return chains;
        }
        Map<String, DeploymentRuntimeProperties> runtimePropertiesMap = chainRuntimePropertiesService.getRuntimePropertiesCache();
        return chains.stream()
                .filter(chain -> loggingFilters.stream()
                        .allMatch(filter -> chainMatchLoggingFilter(runtimePropertiesMap, chain, filter)))
                .toList();
    }

    private boolean chainMatchLoggingFilter(Map<String, DeploymentRuntimeProperties> runtimePropertiesMap,
                                            Chain chain, FilterRequestDTO chainFilter) {
        DeploymentRuntimeProperties props = runtimePropertiesMap.get(chain.getId());
        return props != null && getSessionsLoggingLevelPredicate(chainFilter, props.getSessionsLoggingLevel());
    }

    private boolean getSessionsLoggingLevelPredicate(FilterRequestDTO filter, SessionsLoggingLevel loggingLevel) {
        return switch (filter.getCondition()) {
            case IN -> Arrays.stream(filter.getValue().split(","))
                    .anyMatch(value -> SessionsLoggingLevel.valueOf(value.toUpperCase()) == loggingLevel);
            case NOT_IN -> Arrays.stream(filter.getValue().split(","))
                    .noneMatch(value -> SessionsLoggingLevel.valueOf(value.toUpperCase()) == loggingLevel);
            default -> throw new IllegalStateException("Unexpected value: " + filter.getCondition());
        };
    }
}
