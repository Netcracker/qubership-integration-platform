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

import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.catalog.consul.ConfigurationPropertiesConstants.*;

@Component
public class CommonPropertiesBuilder implements ElementPropertiesBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        return true;
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        Map<String, String> properties = new HashMap<>();
        properties.put(ELEMENT_NAME, element.getName());
        properties.put(ELEMENT_TYPE, element.getType());
        properties.put(ELEMENT_ID, element.getOriginalId());
        if (isAsyncSplitElement(element)) {
            properties.put(WIRE_TAP_ID, getWireTapId(element));
        }
        return properties;
    }

    public String getWireTapId(ChainElement element) {
        return element.getInputDependencies().stream()
                .map(dependency -> dependency.getElementFrom().getId())
                .collect(Collectors.joining(","));
    }

    public boolean isAsyncSplitElement(ChainElement element) {
        for (Dependency dependency : element.getInputDependencies()) {
            ChainElement previousElement = dependency.getElementFrom();
            if (ASYNC_SPLIT_ELEMENT.equals(previousElement.getType())) {
                return true;
            }
        }
        return false;
    }

}
