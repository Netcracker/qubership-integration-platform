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

import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;

import java.util.Arrays;
import java.util.List;

public class ElementFilter implements FilterApplier {
    @Override
    public List<Chain> apply(List<Chain> chains, List<FilterRequestDTO> filters) {
        List<FilterRequestDTO> elementFilters = filters.stream()
                .filter(filter -> filter.getFeature() == FilterFeature.ELEMENT)
                .toList();
        if (elementFilters.isEmpty()) {
            return chains;
        }
        return chains.stream()
                .filter(chain -> elementFilters.stream()
                        .allMatch(filter -> getPredicate(filter, chain.getElements())))
                .toList();
    }

    private boolean getPredicate(FilterRequestDTO filter, List<ChainElement> elements) {
        return switch (filter.getCondition()) {
            case IN ->
                    elements.stream().anyMatch(element -> Arrays.asList(filter.getValue().split(",")).contains(element.getType()));
            case NOT_IN ->
                    elements.stream().noneMatch(element -> Arrays.asList(filter.getValue().split(",")).contains(element.getType()));
            default -> throw new IllegalStateException("Unexpected filter value: " + filter.getCondition());
        };
    }
}
