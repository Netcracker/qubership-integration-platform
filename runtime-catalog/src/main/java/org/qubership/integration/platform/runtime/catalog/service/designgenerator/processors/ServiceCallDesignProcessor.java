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

package org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors;

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;
import org.qubership.integration.platform.runtime.catalog.service.SystemEnvironmentsGenerator;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.SequenceDiagramBuilder;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.DesignProcessor;
import org.qubership.integration.platform.runtime.catalog.util.DiagramBuilderEscapeUtil;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.*;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.*;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.*;

@Component
public class ServiceCallDesignProcessor implements DesignProcessor {

    private final SystemRepository systemRepository;
    private final SystemEnvironmentsGenerator systemEnvironmentsGenerator;

    @Autowired
    public ServiceCallDesignProcessor(SystemRepository systemRepository, SystemEnvironmentsGenerator systemEnvironmentsGenerator) {
        this.systemRepository = systemRepository;
        this.systemEnvironmentsGenerator = systemEnvironmentsGenerator;
    }

    @Override
    public Set<String> supportedElementTypes() {
        return Set.of("service-call");
    }

    @Override
    public String getExternalParticipantId(ChainElement element) {
        IntegrationSystem system = getSystem(element);
        return system == null ? null
                : DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(system.getId());
    }

    @Override
    public String getExternalParticipantName(ChainElement element) {
        IntegrationSystem system = getSystem(element);
        return system == null ? null : ("Service: " + system.getName());
    }

    private IntegrationSystem getSystem(ChainElement element) {
        Map<String, Object> properties = element.getProperties();
        return properties.containsKey(SYSTEM_ID)
                ? systemRepository.findById((String) properties.get(SYSTEM_ID))
                        .orElseThrow(() -> new RuntimeException(
                                SystemService.SYSTEM_WITH_ID_NOT_FOUND_MESSAGE + properties.get(SYSTEM_ID)))
                : null;
    }

    @Override
    public void processBefore(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {
        Map<String, Object> properties = element.getProperties();
        Map<String, Object> before = (Map<String, Object>) properties.get(BEFORE);
        List<Map<String, Object>> afterList = (List<Map<String, Object>>) properties.get(AFTER);
        String externalParticipantId = getExternalParticipantId(element);
        String protocol = (String) properties.get(OPERATION_PROTOCOL_TYPE_PROP);

        if (externalParticipantId != null) {
            builder.append(START_GROUP, element.getName()); // plantuml
            builder.append(START_COLORED_GROUP, GROUP_BG_RGB[0], GROUP_BG_RGB[1], GROUP_BG_RGB[2],
                    refChainId, element.getName()); // mermaid

            if (before != null && before.containsKey(TYPE)) {
                builder.append(LINE_WITH_ARROW_SOLID_RIGHT, refChainId, refChainId,
                        "Prepare request (" + before.get(TYPE).toString() + ")");
            }

            String lineMessage = buildPathMessage(properties, protocol);

            builder.append(LINE_WITH_ARROW_SOLID_RIGHT,
                    refChainId,
                    externalParticipantId,
                    lineMessage);

            if (OPERATION_PROTOCOL_TYPE_HTTP.equals(protocol)
                    || OPERATION_PROTOCOL_TYPE_GRAPHQL.equals(protocol)) {
                builder.append(ACTIVATE, externalParticipantId);
                builder.append(LINE_WITH_ARROW_DOTTED_RIGHT,
                        externalParticipantId,
                        refChainId,
                        DEFAULT_RESPONSE_TITLE);
                builder.append(DEACTIVATE, externalParticipantId);
            }

            if (afterList != null && !afterList.isEmpty()) {
                boolean atLeastOneHandler = false;
                for (Map<String, Object> after : afterList) {
                    if (after != null && after.containsKey(LABEL) && after.containsKey(TYPE)) {
                        builder.append(atLeastOneHandler ? ELSE : START_ALT,
                                "Code " + after.get(LABEL));
                        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, refChainId, refChainId,
                                "Handle response " + " (" + after.get(TYPE).toString() + ")");
                        atLeastOneHandler = true;
                    }
                }

                if (atLeastOneHandler) {
                    builder.append(END);
                }
            }

            builder.append(END);
        }
    }

    @Override
    public void processAfter(String refChainId, SequenceDiagramBuilder builder,
                             ChainElement element) {

    }

    private String buildPathMessage(Map<String, Object> elementProperties, String protocol) {

        switch (protocol) {
            case OPERATION_PROTOCOL_TYPE_AMQP -> {
                return "Put message to exchange " + elementProperties.getOrDefault(OPERATION_PATH_EXCHANGE, EMPTY_PROPERTY_STUB);
            }
            case OPERATION_PROTOCOL_TYPE_KAFKA -> {
                String systemId = (String) elementProperties.get(SYSTEM_ID);
                if (systemId != null) {
                    List<ServiceEnvironment> environments = systemEnvironmentsGenerator.generateSystemEnvironments(Set.of(systemId));

                    if (!environments.isEmpty()) {
                        ServiceEnvironment env = environments.get(0);
                        Map<String, Object> asyncProperties = ElementUtils.mergeProperties(
                                (Map<String, Object>) elementProperties.getOrDefault(OPERATION_ASYNC_PROPERTIES, Collections.emptyMap()),
                                env.getProperties());

                        switch (env.getSourceType()) {
                            case MANUAL -> {
                                return "Put message to topic " + elementProperties.getOrDefault(OPERATION_PATH_TOPIC, EMPTY_PROPERTY_STUB);
                            }
                            case MAAS_BY_CLASSIFIER -> {
                                return "Put message to topic by classifier " + asyncProperties.getOrDefault(MAAS_CLASSIFIER_NAME_PROP, EMPTY_PROPERTY_STUB);
                            }
                        }
                    }
                } else {
                    return EMPTY_PROPERTY_STUB;
                }
            }
            case OPERATION_PROTOCOL_TYPE_GRAPHQL -> {
                String operationName = (String) elementProperties.get(GQL_OPERATION_NAME_PROP);
                return "GraphQL request (query/mutation)"
                        + (StringUtils.isEmpty(operationName)
                                ? ""
                                : (", operation: " + operationName));
            }
        }

        Object method = elementProperties.get(OPERATION_METHOD);
        Object path = elementProperties.get(OPERATION_PATH);
        return method + " " + path;
    }
}
