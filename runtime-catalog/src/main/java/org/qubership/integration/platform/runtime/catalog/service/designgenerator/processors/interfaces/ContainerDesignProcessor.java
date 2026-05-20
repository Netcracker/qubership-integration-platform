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

package org.qubership.integration.platform.runtime.catalog.service.designgenerator.processors.interfaces;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.designgenerator.SequenceDiagramBuilder;

import java.util.Comparator;
import java.util.function.Predicate;

public interface ContainerDesignProcessor extends DesignProcessor {

    /**
     * Comparator which defines children elements order.
     * You can use the following structure inside the implementation:
     * <code>Map{@literal <}element_type, priority_number{@literal >}</code>
     */
    Comparator<ChainElement> getComparator();

    Predicate<ChainElement> getChildrenFilter();

    boolean isContainerWithRestrictions();

    void processChildAfter(String refChainId, SequenceDiagramBuilder builder, ChainElement element, ChainElement child);

    void processChildBefore(String refChainId, SequenceDiagramBuilder builder, ChainElement element, ChainElement child);
}
