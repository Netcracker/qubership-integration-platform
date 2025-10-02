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

package org.qubership.integration.platform.catalog.service;

import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.repository.chain.ChainRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ChainBaseService {

    protected final ChainRepository chainRepository;
    protected final ElementBaseService elementBaseService;

    public ChainBaseService(ChainRepository chainRepository, ElementBaseService elementBaseService) {
        this.chainRepository = chainRepository;
        this.elementBaseService = elementBaseService;
    }

    public boolean isSystemUsedByChain(String systemId) {
        return elementBaseService.isSystemUsedByElement(systemId);
    }

    public boolean isSpecificationGroupUsedByChain(String specificationGroupId) {
        return elementBaseService.isSpecificationGroupUsedByElement(specificationGroupId);
    }

    public boolean isSystemModelUsedByChain(String modelId) {
        return elementBaseService.isSystemModelUsedByElement(modelId);
    }

    public List<Chain> findBySystemId(String systemId) {
        List<ChainElement> elements = elementBaseService.findBySystemId(systemId);
        return getElementsChains(elements);
    }

    protected List<Chain> getElementsChains(List<ChainElement> elements) {
        return elements.stream()
                .map(ChainElement::getChain)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }
}
