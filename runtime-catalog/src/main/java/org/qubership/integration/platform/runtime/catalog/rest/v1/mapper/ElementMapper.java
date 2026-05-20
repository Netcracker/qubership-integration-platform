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

package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.apache.commons.lang3.tuple.Pair;
import org.mapstruct.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.ElementResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.ElementWithChainNameResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.ElementsCodeDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.PatchElementRequest;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.util.MapperUtils;
import org.qubership.integration.platform.runtime.catalog.util.StringTrimmer;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        collectionMappingStrategy = CollectionMappingStrategy.SETTER_PREFERRED,
        uses = {
                MapperUtils.class,
                UserMapper.class,
                StringTrimmer.class
        })
public abstract class ElementMapper {

    @Autowired
    private ElementUtils elementUtils;

    @Mapping(source = "parent.id", target = "parentElementId")
    @Mapping(source = "chain.id", target = "chainId")
    @Mapping(source = "swimlane.id", target = "swimlaneId")
    public abstract void elementToResponse(@MappingTarget ElementResponse target, ChainElement element);

    @Mapping(source = "elements", target = "children")
    @Mapping(source = "parent.id", target = "parentElementId")
    @Mapping(source = "chain.id", target = "chainId")
    @Mapping(source = "swimlane.id", target = "swimlaneId")
    public abstract void containerElementToResponse(@MappingTarget ElementResponse target, ContainerChainElement container);

    public List<ElementWithChainNameResponse> toElementWithChainNameResponses(List<Pair<String, ChainElement>> elementsPairs) {
        return elementsPairs.stream().map(pair -> {
            ElementWithChainNameResponse elementResponse =
                    (ElementWithChainNameResponse) toElementResponse(pair.getRight(), new ElementWithChainNameResponse());
            elementResponse.setChainName(pair.getLeft());
            return elementResponse;
        }).collect(Collectors.toList());
    }

    public ElementResponse toElementResponse(ChainElement element) {
        return toElementResponse(element, new ElementResponse());
    }

    public ElementResponse toElementResponse(ChainElement element, ElementResponse response) {
        if (element instanceof ContainerChainElement) {
            containerElementToResponse(response, (ContainerChainElement) element);
        } else if (element != null) {
            elementToResponse(response, element);
        } else {
            return null;
        }
        response.setMandatoryChecksPassed(elementUtils.areMandatoryPropertiesPresent(element)
                && elementUtils.isMandatoryInnerElementPresent(element));
        return response;
    }

    public abstract List<ElementResponse> toElementResponses(List<ChainElement> elements);

    public abstract Map<String, ElementResponse> toElementResponses(Map<String, ChainElement> elements);

    public List<ElementResponse> toElementResponses(Chain chain) {
        return toElementResponses(chain.getRootElements());
    }

    public abstract void patch(@MappingTarget ChainElement element, PatchElementRequest request);

    public abstract void merge(@MappingTarget ChainElement element, ChainElement request);

    @Mapping(source = "yaml", target = "code")
    public abstract ElementsCodeDTO elementsCodeToDTO(String yaml);
}
