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

package org.qubership.integration.platform.runtime.catalog.model.mapper.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.integration.platform.runtime.catalog.model.dto.chain.ChainElementDTO;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;

import java.util.List;
import javax.annotation.Nullable;

@Mapper(componentModel = "spring")
public abstract class ChainElementMapper {

    @Mapping(target = "originalId", expression = "java(extractOriginalId(element))")
    @Mapping(target = "parentElementId", expression = "java(extractOriginalId(element.getParent()))")
    @Mapping(target = "childrenOriginalIds", expression = "java(extractChildrenIds(element))")
    abstract ChainElementDTO asDto(ChainElement element);

    abstract List<ChainElementDTO> asDtoList(List<ChainElement> elements);

    @Nullable
    protected List<String> extractChildrenIds(ChainElement element) {
        if (!(element instanceof ContainerChainElement containerElement)) {
            return null;
        }

        return containerElement.getElements().stream()
                .map(this::extractOriginalId)
                .toList();
    }

    @Nullable
    protected String extractOriginalId(@Nullable ChainElement element) {
        return element != null ? MapperUtils.extractOriginalId(element) : null;
    }
}
