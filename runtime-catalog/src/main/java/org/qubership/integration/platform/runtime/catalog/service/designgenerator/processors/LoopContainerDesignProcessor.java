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

import org.qubership.integration.platform.runtime.catalog.model.designgenerator.DiagramOperationType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.SequenceDiagramBuilder;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces.ContainerDesignProcessor;
import org.qubership.integration.platform.runtime.catalog.util.DiagramBuilderEscapeUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.comparator.Comparators;

import java.util.Comparator;
import java.util.Set;
import java.util.function.Predicate;

@Component
public class LoopContainerDesignProcessor implements ContainerDesignProcessor {

    @Override
    public Comparator<ChainElement> getComparator() {
        return Comparators.comparable();
    }

    @Override
    public Predicate<ChainElement> getChildrenFilter() {
        return element -> element.getInputDependencies().isEmpty();
    }

    @Override
    public boolean isContainerWithRestrictions() {
        return false;
    }

    @Override
    public Set<String> supportedElementTypes() {
        return Set.of("loop-2");
    }

    @Override
    public String getExternalParticipantId(ChainElement element) {
        return null;
    }

    @Override
    public String getExternalParticipantName(ChainElement element) {
        return null;
    }

    @Override
    public void processBefore(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {
        if ((element instanceof ContainerChainElement containerElement) && !containerElement.getElements().isEmpty()) {
            builder.append(
                    DiagramOperationType.START_LOOP,
                    DiagramBuilderEscapeUtil.substituteProperties(refChainId, element, "##{expression}")
            );
        }
    }

    @Override
    public void processAfter(String refChainId, SequenceDiagramBuilder builder, ChainElement element) {
        if ((element instanceof ContainerChainElement containerElement) && !containerElement.getElements().isEmpty()) {
            builder.append(DiagramOperationType.END);
        }
    }

    @Override
    public void processChildAfter(String refChainId, SequenceDiagramBuilder builder, ChainElement element, ChainElement child) {
        // do nothing
    }

    @Override
    public void processChildBefore(String refChainId, SequenceDiagramBuilder builder, ChainElement element, ChainElement child) {
        // do nothing
    }
}
