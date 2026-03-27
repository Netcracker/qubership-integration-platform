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

import org.qubership.integration.platform.runtime.catalog.model.chain.ChainStatus;
import org.qubership.integration.platform.runtime.catalog.model.deployment.engine.ChainRuntimeDeployment;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Deployment;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Predicate;

public class ChainStatusFilters implements FilterApplier {
    private static final String NO_DEPLOYMENTS_CAPTION = "No deployments yet";

    private final RuntimeDeploymentService runtimeDeploymentService;

    public ChainStatusFilters(RuntimeDeploymentService runtimeDeploymentService) {
        this.runtimeDeploymentService = runtimeDeploymentService;
    }

    @Override
    public List<Chain> apply(List<Chain> chains, List<FilterRequestDTO> filters) {
        List<FilterRequestDTO> deploymentStatusFilters = filters.stream()
                .map(filter -> FilterFeature.ENGINES.equals(filter.getFeature())
                               && NO_DEPLOYMENTS_CAPTION.equals(filter.getValue())
                        ? filter.toBuilder()
                        .feature(FilterFeature.STATUS)
                        .value(ChainStatus.DRAFT.name())
                        .build()
                        : filter
                )
                .filter(filter -> FilterFeature.STATUS.equals(filter.getFeature()))
                .toList();
        if (deploymentStatusFilters.isEmpty()) {
            return chains;
        }

        Map<String, Collection<ChainRuntimeDeployment>> runtimeDeployments = runtimeDeploymentService.getChainRuntimeDeployments();
        return chains.stream()
                .filter(chain -> deploymentStatusFilters.stream()
                        .allMatch(filter -> chainMatchesDeploymentStatusFilter(runtimeDeployments, chain, filter)))
                .toList();
    }

    private boolean chainMatchesDeploymentStatusFilter(Map<String, Collection<ChainRuntimeDeployment>> runtimeDeployments,
                                                       Chain chain, FilterRequestDTO filter) {
        Collection<ChainStatus> chainDeploymentStatuses = getChainDeploymentStatuses(runtimeDeployments, chain);
        Predicate<ChainStatus> predicate = getDeploymentStatusPredicate(filter);
        return chainDeploymentStatuses.stream().anyMatch(predicate);
    }

    private Collection<ChainStatus> getChainDeploymentStatuses(Map<String, Collection<ChainRuntimeDeployment>> runtimeDeployments,
                                                                    Chain chain) {
        Collection<Deployment> deployments = chain.getDeployments();
        Collection<ChainRuntimeDeployment> chainRuntimeDeployments =
                runtimeDeployments == null ? null : runtimeDeployments.get(chain.getId());
        if (CollectionUtils.isEmpty(deployments)) {
            return Collections.singletonList(ChainStatus.DRAFT);
        }

        Collection<ChainStatus> result = new ArrayList<>();
        for (Deployment deployment : deployments) {
            String deploymentId = deployment.getId();
            ChainRuntimeDeployment runtimeDeployment = chainRuntimeDeployments == null
                    ? null
                    : chainRuntimeDeployments.stream()
                            .filter(dep -> deploymentId.equals(dep.getDeploymentInfo().getDeploymentId()))
                            .findAny().orElse(null);
            if (runtimeDeployment != null) {
                result.add(ChainStatus.valueOf(runtimeDeployment.getStatus().name()));
            } else {
                result.add(ChainStatus.PROCESSING);
            }
        }

        return result;
    }

    private Predicate<ChainStatus> getDeploymentStatusPredicate(FilterRequestDTO filter) {
        return switch (filter.getCondition()) {
            case IN -> status -> Arrays.stream(filter.getValue().split(","))
                    .anyMatch(value -> ChainStatus.valueOf(value.toUpperCase()) == status);
            case NOT_IN -> status -> Arrays.stream(filter.getValue().split(","))
                    .noneMatch(value -> ChainStatus.valueOf(value.toUpperCase()) == status);
            default -> throw new IllegalStateException("Unexpected value: " + filter.getCondition());
        };
    }
}
