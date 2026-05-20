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

package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.runtime.catalog.consul.ConfigurationPropertiesConstants.CHAIN_CALL_2_ELEMENT;


@Slf4j
@Component
public class ChainCall2PropertiesBuilder implements ElementPropertiesBuilder {
    private final ElementService elementService;

    @Autowired
    public ChainCall2PropertiesBuilder(ElementService elementService) {
        this.elementService = elementService;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return CHAIN_CALL_2_ELEMENT.equals(type);
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        return getChainCallProperties(this.elementService, element.getProperties());
    }

    public static Map<String, String> getChainCallProperties(ElementService elementService, Map<String, Object> chainCallPropertiesContainer) {
        Map<String, String> properties = new HashMap<>();
        Optional<ChainElement> originalElement = elementService.findByIdOptional(
                chainCallPropertiesContainer.getOrDefault(ConfigurationPropertiesConstants.ELEMENT_ID, "").toString());
        originalElement.ifPresent(chainElement ->
                properties.put(ConfigurationPropertiesConstants.ACTUAL_ELEMENT_CHAIN_ID, chainElement.getChain().getId()));
        return properties;
    }
}
