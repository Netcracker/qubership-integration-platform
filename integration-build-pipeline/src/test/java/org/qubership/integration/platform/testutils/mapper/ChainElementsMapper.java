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

package org.qubership.integration.platform.testutils.mapper;

import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.impl.ElementImpl;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.testutils.dto.ElementDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.util.*;

@TestComponent
public class ChainElementsMapper {
    private final ServiceEnvironmentMapper serviceEnvironmentMapper;

    @Autowired
    public ChainElementsMapper(ServiceEnvironmentMapper serviceEnvironmentMapper) {
        this.serviceEnvironmentMapper = serviceEnvironmentMapper;
    }

    public Element toEntity(ElementDTO elementDTO) {
        ElementImpl element = new ElementImpl();
        element.setId(elementDTO.getId());
        element.setOriginalId(elementDTO.getOriginalId());
        element.setName(elementDTO.getName());
        element.setDescription(elementDTO.getDescription());
        element.setType(elementDTO.getType());
        element.setProperties(elementDTO.getProperties());
        element.setChildren(Optional.ofNullable(elementDTO.getChildren())
                .orElse(Collections.emptyList())
                .stream()
                .map(this::toEntity)
                .map(e -> ElementBuilder.createNew().from(e).parent(element).build())
                .toList());
        element.setServiceEnvironment(serviceEnvironmentMapper.toEntity(elementDTO.getServiceEnvironment()));
        return element;
    }
}
