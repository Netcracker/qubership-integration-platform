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
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemRepository;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.SequenceDiagramBuilder;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.DesignProcessor;
import org.qubership.integration.platform.runtime.catalog.util.DiagramBuilderEscapeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.*;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.DEFAULT_RESPONSE_TITLE;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.EMPTY_PROPERTY_STUB;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.LINE_WITH_ARROW_DOTTED_RIGHT;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.LINE_WITH_ARROW_SOLID_RIGHT;

@Component
public class HttpTriggerDesignProcessor implements DesignProcessor {

    private final SystemRepository systemRepository;

    @Autowired
    public HttpTriggerDesignProcessor(SystemRepository systemRepository) {
        this.systemRepository = systemRepository;
    }

    @Override
    public Set<String> supportedElementTypes() {
        return Set.of("http-trigger");
    }

    @Override
    public String getExternalParticipantId(ChainElement element) {
        IntegrationSystem system = getSystem(element);
        String serviceId = isManualSource(element)
                ? getExternalParticipantName(element)
                : (system == null ? null : system.getId());
        return serviceId == null ? null : DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(serviceId);
    }

    @Override
    public String getExternalParticipantName(ChainElement element) {
        Map<String, Object> properties = element.getProperties();
        IntegrationSystem system = getSystem(element);
        boolean isExternal = (boolean) properties.getOrDefault(IS_EXTERNAL_ROUTE, true);
        boolean isPrivate = (boolean) properties.getOrDefault(IS_PRIVATE_ROUTE, false);
        String message = "Unknown " + (
                    isExternal || isPrivate ? "external (via " + getRouteMessage(isExternal, isPrivate) +  " route)" : "internal")
                + " service";

        message = isManualSource(element)
                ? message
                : (system == null ? null : ("Service: " + system.getName()));

        return message;
    }

    private String getRouteMessage(boolean isExternal, boolean isPrivate) {
        if (isExternal && isPrivate) {
            return "external or private";
        }
        if (isExternal) {
            return "external";
        }
        return "private";
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
        String methods = element.getPropertyAsString(HTTP_METHOD_RESTRICT);
        String uri = (String) (isManualSource(element) ? properties.get(CONTEXT_PATH) : properties.get(OPERATION_PATH));
        String title =
                "HTTP request to " + (uri == null ? EMPTY_PROPERTY_STUB : uri)
                        + ", allowed methods=[" + (StringUtils.isBlank(methods) ? "ALL" : methods) + "]";

        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, getExternalParticipantId(element), refChainId, title);

        if (!isManualSource(element)) {
            DiagramBuilderEscapeUtil.buildValidateRequest(refChainId, builder, properties);
        }
    }

    @Override
    public void processAfter(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {
        builder.append(LINE_WITH_ARROW_DOTTED_RIGHT, refChainId, getExternalParticipantId(element), DEFAULT_RESPONSE_TITLE);
    }

    private boolean isManualSource(ChainElement element) {
        String systemType = (String) element.getProperties().get(SYSTEM_TYPE);
        return StringUtils.isEmpty(systemType);
    }
}
