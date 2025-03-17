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

import org.qubership.integration.platform.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.qubership.integration.platform.catalog.consul.ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_COUNT;
import static org.qubership.integration.platform.catalog.consul.ConfigurationPropertiesConstants.SERVICE_CALL_RETRY_DELAY;

@Component
public class ServiceCallPropertiesBuilder implements ElementPropertiesBuilder {
    @Override
    public boolean applicableTo(ChainElement element) {
        return CamelNames.SERVICE_CALL_COMPONENT.equals(element.getType());
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        Map<String, String> propertiesMap = new HashMap<>();
        Stream.of(
                SERVICE_CALL_RETRY_COUNT,
                SERVICE_CALL_RETRY_DELAY
        ).forEach(property -> propertiesMap.put(property, element.getPropertyAsString(property)));
        return propertiesMap;
    }
}
