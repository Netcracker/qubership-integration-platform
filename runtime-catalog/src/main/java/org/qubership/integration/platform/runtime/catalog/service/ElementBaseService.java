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

import jakarta.persistence.EntityNotFoundException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ElementBaseService {

    protected static final String CHAIN_ELEMENT_WITH_ID_NOT_FOUND_MESSAGE = "Can't find chain element with id: ";

    protected final ElementRepository elementRepository;

    @Autowired
    public ElementBaseService(ElementRepository elementRepository) {
        this.elementRepository = elementRepository;
    }

    public ChainElement findById(String id) {
        return elementRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(CHAIN_ELEMENT_WITH_ID_NOT_FOUND_MESSAGE + id));
    }

    public <T extends ChainElement> T findById(String id, Class<T> elementClass) {
        ChainElement element = findById(id);
        if (elementClass.isAssignableFrom(element.getClass())) {
            return elementClass.cast(element);
        }
        return null;
    }

    public Optional<ChainElement> findByIdOptional(String id) {
        return elementRepository.findById(id);
    }

    public void delete(ChainElement element) {
        elementRepository.delete(element);
    }

    public boolean isSystemUsedByElement(String systemId) {
        return elementRepository.exists((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder
                                .function(
                                        "jsonb_extract_path_text",
                                        String.class,
                                        root.<String>get("properties"),
                                        builder.literal(CamelOptions.SYSTEM_ID)
                                ),
                        systemId)
        ));
    }

    public boolean isSpecificationGroupUsedByElement(String specificationGroupId) {
        return elementRepository.exists((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder
                                .function(
                                        "jsonb_extract_path_text",
                                        String.class,
                                        root.<String>get("properties"),
                                        builder.literal(CamelOptions.SPECIFICATION_GROUP_ID)
                                ),
                        specificationGroupId)
        ));
    }

    public boolean isSystemModelUsedByElement(String modelId) {
        return elementRepository.exists((root, query, builder) -> builder.and(
                builder.isNotNull(root.get("chain")),
                builder.equal(builder
                                .function(
                                        "jsonb_extract_path_text",
                                        String.class,
                                        root.<String>get("properties"),
                                        builder.literal(CamelOptions.MODEL_ID)
                                ),
                        modelId)
        ));
    }
}
