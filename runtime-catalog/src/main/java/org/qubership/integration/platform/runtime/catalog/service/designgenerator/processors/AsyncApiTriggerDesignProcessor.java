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
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.EMPTY_PROPERTY_STUB;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.LINE_WITH_ARROW_SOLID_RIGHT;


@Component
public class AsyncApiTriggerDesignProcessor implements DesignProcessor {

    private final SystemRepository systemRepository;
    private final SystemEnvironmentsGenerator systemEnvironmentsGenerator;

    @Autowired
    public AsyncApiTriggerDesignProcessor(SystemRepository systemRepository, SystemEnvironmentsGenerator systemEnvironmentsGenerator) {
        this.systemRepository = systemRepository;
        this.systemEnvironmentsGenerator = systemEnvironmentsGenerator;
    }

    @Override
    public Set<String> supportedElementTypes() {
        return Set.of("async-api-trigger");
    }

    @Override
    public String getExternalParticipantId(ChainElement element) {
        IntegrationSystem system = getSystem(element);
        return system == null ? null : DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(system.getId());
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
        String externalParticipantId = getExternalParticipantId(element);

        String path = buildPathMessage(properties);

        if (externalParticipantId != null) {
            builder.append(LINE_WITH_ARROW_SOLID_RIGHT,
                    externalParticipantId,
                    refChainId,
                    "Pull message " + path);

            DiagramBuilderEscapeUtil.buildValidateRequest(refChainId, builder, properties);
        }
    }

    @Override
    public void processAfter(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {

    }

    /**
     * Build topic/queue/exchange/classifier message from properties and env
     *
     * @param elementProperties element properties
     * @return path
     */
    private String buildPathMessage(Map<String, Object> elementProperties) {
        String operationProtocol = (String) elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP);

        String systemId = (String) elementProperties.get(SYSTEM_ID);
        if (systemId != null) {
            List<ServiceEnvironment> environments = systemEnvironmentsGenerator.generateSystemEnvironments(Set.of(systemId));

            if (!environments.isEmpty()) {
                if (operationProtocol != null) {
                    ServiceEnvironment env = environments.get(0);
                    Map<String, Object> asyncProperties = ElementUtils.mergeProperties(
                            (Map<String, Object>) elementProperties.getOrDefault(OPERATION_ASYNC_PROPERTIES, Collections.emptyMap()),
                            env.getProperties());
                    switch (operationProtocol) {
                        case OPERATION_PROTOCOL_TYPE_AMQP -> {
                            return "from queue " + asyncProperties.getOrDefault(QUEUES, EMPTY_PROPERTY_STUB);
                        }
                        case OPERATION_PROTOCOL_TYPE_KAFKA -> {
                            switch (env.getSourceType()) {
                                case MANUAL -> {
                                    return "from topic " + elementProperties.getOrDefault(OPERATION_PATH_TOPIC, EMPTY_PROPERTY_STUB);
                                }
                                case MAAS_BY_CLASSIFIER -> {
                                    return "from topic by classifier " + asyncProperties.getOrDefault(MAAS_CLASSIFIER_NAME_PROP, EMPTY_PROPERTY_STUB);
                                }
                            }
                        }
                    }
                }
            }
        } else {
            return EMPTY_PROPERTY_STUB;
        }
        return (String) elementProperties.get(OPERATION_PATH);
    }
}
