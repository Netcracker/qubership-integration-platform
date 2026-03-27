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

package org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.converter;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.dds.TemplateChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.OperationService;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.ddsgenerator.elements.ElementTemplateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ServiceCallDDSConverter extends ElementDDSConverter {
    private static final Map<String, String> TYPE_MAPPING = Map.of(
            CamelNames.SERVICE_CALL_COMPONENT, "Service Call"
    );

    private static final Map<String, String> HANDLING_TYPE_MAPPING = Map.of(
            CamelNames.MAPPER_2, "mapper"
    );

    private final ElementTemplateUtils elementTemplateUtils;
    private final SystemService systemService;
    private final SystemModelService systemModelService;
    private final OperationService operationService;

    @Autowired
    public ServiceCallDDSConverter(ElementTemplateUtils elementTemplateUtils, SystemService systemService, SystemModelService systemModelService, OperationService operationService) {
        this.elementTemplateUtils = elementTemplateUtils;
        this.systemService = systemService;
        this.systemModelService = systemModelService;
        this.operationService = operationService;
    }

    @Override
    protected Map<String, String> getTypeMapping() {
        return TYPE_MAPPING;
    }

    @Override
    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public TemplateChainElement convert(ChainElement element) {
        Map<String, Object> elementProps = element.getProperties();
        Map<String, Object> elementTemplateProps = new HashMap<>();

        TemplateChainElement.TemplateChainElementBuilder builder = getBuilder(element);
        builder.properties(elementTemplateProps);

        List<Map<String, Object>> errorHandlingValues = new ArrayList<>();
        if (elementProps.get(CamelOptions.AFTER) instanceof Collection<?> after) {
            for (Object item : after) {
                Map<String, Object> itemMap = (Map<String, Object>) item;
                if (itemMap.containsKey(CamelOptions.RESPONSE_CODE)) {
                    String type = (String) itemMap.getOrDefault(CamelOptions.TYPE, null);
                    if (StringUtils.isNotEmpty(type) && HANDLING_TYPE_MAPPING.containsKey(type)) {
                        type = HANDLING_TYPE_MAPPING.get(type);
                    }
                    Map<String, Object> hm = new HashMap<>();
                    hm.put("type", StringUtils.capitalize(type));
                    hm.put("responseCode", itemMap.get("label"));
                    errorHandlingValues.add(hm);
                }
            }
        }
        elementTemplateProps.put("errorHandling", errorHandlingValues);

        Object authorizationConfiguration = elementProps.get(CamelOptions.AUTHORIZATION_CONFIGURATION);
        Map<String, Object> authProps = new HashMap<>();
        authProps.put("type", authorizationConfiguration instanceof Map<?, ?> authConfig
                ? StringUtils.capitalize((String) authConfig.get("type")) : null);
        elementTemplateProps.put("authorization", authProps);

        try {
            String integrationSystemId = (String) elementProps.get(CamelOptions.SYSTEM_ID);
            String integrationSpecificationId = (String) elementProps.get(CamelOptions.SPECIFICATION_ID);
            String integrationOperationId = (String) elementProps.get(CamelOptions.OPERATION_ID);

            if (integrationSystemId != null && integrationSpecificationId != null && integrationOperationId != null) {
                IntegrationSystem system = systemService.findById(integrationSystemId);
                SystemModel spec = systemModelService.getSystemModel(integrationSpecificationId);
                Operation operation = operationService.getOperation(integrationOperationId);

                elementTemplateProps.put("serviceName", system.getName());
                elementTemplateProps.put("specificationName", spec.getName());
                elementTemplateProps.put("operationName", operation.getName());
                elementTemplateProps.put("operationType", operation.getMethod());
            }

            elementTemplateUtils.addJSONSchemas(elementProps, elementTemplateProps);
        } catch (EntityNotFoundException ignored) { }

        return builder.build();
    }
}
