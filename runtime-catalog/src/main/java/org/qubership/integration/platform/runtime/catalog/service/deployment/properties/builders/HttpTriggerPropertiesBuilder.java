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
import org.qubership.integration.platform.catalog.consul.ConfigurationPropertiesConstants;
import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.deployment.properties.ElementPropertiesBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.qubership.integration.platform.catalog.consul.ConfigurationPropertiesConstants.*;

@Slf4j
@Component
public class HttpTriggerPropertiesBuilder implements ElementPropertiesBuilder {
    private final ElementService elementService;

    @Autowired
    public HttpTriggerPropertiesBuilder(ElementService elementService) {
        this.elementService = elementService;
    }

    @Override
    public boolean applicableTo(ChainElement element) {
        String type = element.getType();
        return HTTP_TRIGGER_ELEMENT.equals(type);
    }

    @Override
    public Map<String, String> build(ChainElement element) {
        Map<String, String> returnProperties = new HashMap<>();
        Map<String, Object> elementProperties = element.getProperties();
        if (CHAIN_CALL_PROPERTY_OPTION.equals(elementProperties.get(HTTP_TRIGGER_FAILURE_HANDLER_ACTION))) {
            returnProperties.putAll(ChainCall2PropertiesBuilder.getChainCallProperties(
                    this.elementService,
                    (Map<String, Object>) elementProperties.getOrDefault(HTTP_TRIGGER_FAILURE_HANDLER_CHAIN_CALL_CONTAINER, Collections.emptyMap())));
            returnProperties.put(ACTUAL_CHAIN_OVERRIDE_STEP_NAME_FIELD, HTTP_TRIGGER_CHAIN_CALL_STEP_NAME);
        }
        returnProperties.put(ConfigurationPropertiesConstants.METHOD, element.getPropertyAsString(CamelOptions.HTTP_METHOD_RESTRICT));
        boolean isImplementedService = CamelOptions.SYSTEM_TYPE_IMPLEMENTED.equals(element.getPropertyAsString(CamelOptions.SYSTEM_TYPE));
        String path = isImplementedService
            ? element.getPropertyAsString(CamelOptions.OPERATION_PATH)
            : element.getPropertyAsString(CamelOptions.CONTEXT_PATH);
        returnProperties.put(ConfigurationPropertiesConstants.PATH, path);
        return returnProperties;
    }
}
