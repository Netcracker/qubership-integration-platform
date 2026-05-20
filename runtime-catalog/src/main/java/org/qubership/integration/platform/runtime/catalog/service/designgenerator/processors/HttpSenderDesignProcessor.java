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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.SequenceDiagramBuilder;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.DesignProcessor;
import org.qubership.integration.platform.runtime.catalog.util.DiagramBuilderEscapeUtil;
import org.qubership.integration.platform.runtime.catalog.util.SimpleHttpUriUtils;
import org.springframework.stereotype.Component;

import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.DEFAULT_RESPONSE_TITLE;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.EMPTY_PROPERTY_STUB;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.*;


@Component
public class HttpSenderDesignProcessor implements DesignProcessor {
    @Override
    public Set<String> supportedElementTypes() {
        return Set.of("http-sender");
    }

    @Override
    public String getExternalParticipantId(ChainElement element) {
        return DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(getExternalParticipantName(element));
    }

    @Override
    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public String getExternalParticipantName(ChainElement element) {
        String host = element.getPropertyAsString("uri");
        try {
            host = SimpleHttpUriUtils.extractProtocolAndDomainWithPort(host);
        } catch (Exception ignored) {
        }
        String message = element.getProperty("isExternalCall") == null || (boolean) element.getProperty("isExternalCall")
                ? "External"
                : "Internal";
        return message + " service: " + (host == null ? EMPTY_PROPERTY_STUB : host);
    }

    @Override
    public void processBefore(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {
        String methods = element.getPropertyAsString("httpMethod");
        String path = SimpleHttpUriUtils.extractPathAndQueryFromUri(element.getPropertyAsString("uri"));
        String title = (methods == null ? EMPTY_PROPERTY_STUB : methods) + ", "
                + (path == null ? EMPTY_PROPERTY_STUB : path);
        String externalParticipantId = getExternalParticipantId(element);

        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, refChainId, externalParticipantId, title);
        builder.append(ACTIVATE, externalParticipantId);
        builder.append(LINE_WITH_ARROW_DOTTED_RIGHT, externalParticipantId, refChainId, DEFAULT_RESPONSE_TITLE);
        builder.append(DEACTIVATE, externalParticipantId);
    }

    @Override
    public void processAfter(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {
    }
}
