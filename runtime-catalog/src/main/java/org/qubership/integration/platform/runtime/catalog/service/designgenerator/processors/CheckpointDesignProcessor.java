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
import org.springframework.stereotype.Component;

import java.util.Set;

import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramConstants.GROUP_BG_RGB;
import static org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType.*;


@Component
public class CheckpointDesignProcessor implements DesignProcessor {

    @Override
    public Set<String> supportedElementTypes() {
        return Set.of("checkpoint");
    }

    @Override
    public String getExternalParticipantId(ChainElement element) {
        return DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(getExternalParticipantName(element));
    }

    @Override
    public String getExternalParticipantName(ChainElement element) {
        return "Unknown user";
    }

    @Override
    public void processBefore(String refChainId, SequenceDiagramBuilder builder,
        ChainElement element) {

        String checkpointTitle =
            element.getName() + " with id " + element.getPropertyAsString("checkpointElementId");
        builder.append(START_GROUP, checkpointTitle); // plantuml
        builder.append(START_COLORED_GROUP, GROUP_BG_RGB[0], GROUP_BG_RGB[1], GROUP_BG_RGB[2],
            refChainId, checkpointTitle); // mermaid

        builder.append(START_ALT, "Trigger");
        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, getExternalParticipantId(element), refChainId, "Request to retry session");
        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, refChainId, refChainId,
            "Load context");
        builder.append(ELSE, "Checkpoint");
        builder.append(LINE_WITH_ARROW_SOLID_RIGHT, refChainId, refChainId,
            "Save context");
        builder.append(END);

        builder.append(END);
    }

    @Override
    public void processAfter(String refChainId, SequenceDiagramBuilder builder,
        ChainElement element) {

    }
}
