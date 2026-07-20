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

import org.qubership.integration.platform.chain.impl.ChainImpl;
import org.qubership.integration.platform.chain.impl.ConnectionImpl;
import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.testutils.dto.ChainDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.util.*;

@TestComponent
public class ChainMapper {

    private final ChainElementsMapper chainElementsMapper;

    @Autowired
    public ChainMapper(ChainElementsMapper chainElementsMapper) {
        this.chainElementsMapper = chainElementsMapper;
    }

    public Chain toEntity(ChainDTO chainDTO) {
        ChainImpl chain = new ChainImpl();
        chain.setId(chainDTO.getId());
        chain.setName(chainDTO.getName());
        chain.setDescription(chainDTO.getDescription());
        chain.setBusinessDescription(chainDTO.getBusinessDescription());
        chain.setAssumptions(chainDTO.getAssumptions());
        chain.setOutOfScope(chainDTO.getOutOfScope());
        chain.setElements(Optional.ofNullable(chainDTO.getElements())
            .orElse(Collections.emptyList())
            .stream()
            .map(chainElementsMapper::toEntity)
            .toList());
        chain.setConnections(
            Optional.ofNullable(chainDTO.getDependencies())
                .orElse(Collections.emptyList())
                .stream()
                .map(d -> new ConnectionImpl(
                    chain.getElements().stream()
                        .filter(e -> e.getId().equals(d.getFrom()))
                        .findFirst()
                        .orElse(null),
                    chain.getElements().stream()
                        .filter(e -> e.getId().equals(d.getTo()))
                        .findFirst()
                        .orElse(null)))
                .map(Connection.class::cast)
                .toList());
        chain.getElements().forEach(element -> {
            element.getInputConnections().addAll(chain.getConnections().stream()
                .filter(c -> element.equals(c.getTo())).toList());
            element.getOutputConnections().addAll(chain.getConnections().stream()
                .filter(c -> element.equals(c.getFrom())).toList());
        });
        return chain;
    }
}
