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

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.SequenceDiagramBuilder;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.DesignProcessor;
import org.qubership.integration.platform.runtime.catalog.util.DiagramBuilderEscapeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CHAIN_CALL_ELEMENT_ID;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.DEFAULT_RESPONSE_TITLE;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.*;


@Component
public class ChainCall2DesignProcessor implements DesignProcessor {

    private final ElementService elementService;

    @Autowired
    public ChainCall2DesignProcessor(ElementService elementService) {
        this.elementService = elementService;
    }

    @Override
    public Set<String> supportedElementTypes() {
        return Set.of("chain-call-2");
    }

    @Override
    public String getExternalParticipantId(ChainElement element) {
        return getExternalParticipant(element)
                .map(Chain::getId)
                .map(DiagramBuilderEscapeUtil::removeOrReplaceUnsupportedCharacters)
                .orElseGet(() -> element.getId() + "-external-participant");
    }

    @Override
    public String getExternalParticipantName(ChainElement element) {
        return getExternalParticipant(element)
                .map(Chain::getName)
                .map(name -> "QIP chain: " + name)
                .orElse("Unknown QIP chain");
    }

    @Override
    public void processBefore(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {
        String externalParticipantId = getExternalParticipantId(element);
        Optional<String> triggerId = getChainTriggerId(element);
        String nameOrId = triggerId.flatMap(elementService::findByIdOptional)
                .or(() -> triggerId.flatMap(elementService::findByOriginalId))
                .map(ChainElement::getName)
                .or(() -> triggerId)
                .orElse(null);

        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, refChainId, externalParticipantId, "QIP chain trigger call: " + nameOrId);
        builder.append(ACTIVATE, externalParticipantId);
        builder.append(LINE_WITH_ARROW_DOTTED_RIGHT, externalParticipantId, refChainId, DEFAULT_RESPONSE_TITLE);
        builder.append(DEACTIVATE, externalParticipantId);
    }

    @Override
    public void processAfter(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {

    }

    private Optional<Chain> getExternalParticipant(ChainElement element) {
        Optional<String> triggerId = getChainTriggerId(element);
        Optional<ChainElement> chainElement = triggerId.flatMap(elementService::findByIdOptional)
                .or(() -> triggerId.flatMap(elementService::findByOriginalId));
        return chainElement.map(ChainElement::getChain)
                .or(() -> chainElement.map(ChainElement::getSnapshot).map(Snapshot::getChain));
    }

    private Optional<String> getChainTriggerId(ChainElement element) {
        return Optional.ofNullable(element.getPropertyAsString(CHAIN_CALL_ELEMENT_ID));
    }
}
