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

package org.qubership.integration.platform.runtime.catalog.service.migration.element;

import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * Used to migrate elements that have multiple dependencies.
 *
 * @since 2023.2
 */
@Component
public class ReusedElementMigration extends ElementMigration {

    @Autowired
    protected ReusedElementMigration(LibraryElementsService libraryService) {
        super(libraryService, MigrationContext.REUSED_ELEMENT, MigrationContext.REUSE_ELEMENT_TYPE);
    }

    @Override
    public boolean canBeMigrated(ChainElement chainElement, MigrationContext context) {
        return true;
    }

    /**
     * Moves elements that have multiple input dependencies into a newly created reuse element.
     * Creates an element of type reuse-reference with reference to the reuse element.
     *
     * @param chainElement element with multiple input dependencies
     * @param context migration context
     * @return element of type reuse-reference with a reference to reuse element
     */
    @Transactional
    @Override
    public ChainElement migrate(ChainElement chainElement, MigrationContext context) {
        String originalElementToId = chainElement.getId();
        ContainerChainElement reuseElement;
        if (context.getReuseElements().containsKey(originalElementToId)) {
            reuseElement = context.getReuseElements().get(originalElementToId);
        } else {
            reuseElement = createReuseElement(chainElement);
            context.addReuseElement(originalElementToId, reuseElement);
            chainElement.getChain().addElement(reuseElement);
            chainElement.getChain().getElements().remove(chainElement);

            ElementMigration elementMigration = context.getElementMigration(chainElement.getType());

            // the second condition is necessary to prevent an infinite loop in case
            // there is a cyclic dependency in a chain
            if (elementMigration != null && !context.isElementMigrationInProgress(chainElement)) {
                chainElement = elementMigration.migrate(chainElement, context);
            }
            reuseElement.addChildElement(chainElement);

            Queue<ChainElement> outputElements = new LinkedList<>();
            outputElements.offer(chainElement);
            while (!outputElements.isEmpty()) {
                ChainElement outputElement = outputElements.poll();
                for (Dependency outputDependency : new ArrayList<>(outputElement.getOutputDependencies())) {
                    ChainElement outputElementTo = outputDependency.getElementTo();
                    removeElementFromParentGroupIfRequired(outputElementTo, context);
                    if (outputElementTo.getInputDependencies().size() > 1
                            || context.getReuseElements().containsKey(outputElementTo.getId())
                    ) {
                        ChainElement outputReferenceElement = replaceReusedElementWithReference(outputElement, outputElementTo, context);
                        reuseElement.addChildElement(outputReferenceElement);
                    } else {
                        ElementMigration outputElementMigration = context.getElementMigration(outputElementTo.getType());
                        // the second condition is necessary to prevent an infinite loop in case
                        // there is a cyclic dependency in a chain
                        if (outputElementMigration != null && !context.isElementMigrationInProgress(outputElementTo)) {
                            outputElementTo = outputElementMigration.migrate(outputElementTo, context);
                        }
                        reuseElement.addChildElement(outputElementTo);
                        outputElementTo.getChain().getElements().remove(outputElementTo);
                        outputElements.offer(outputElementTo);
                    }
                }
            }
        }


        return createReferenceElement(reuseElement);
    }

    protected ContainerChainElement createReuseElement(ChainElement startElement) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(getNewElementType());
        return ContainerChainElement.builder()
                .type(descriptor.getName())
                .name(startElement.getName())
                .chain(startElement.getChain())
                .build();
    }

    protected ChainElement createReferenceElement(ChainElement reuseElement) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(MigrationContext.REUSE_REFERENCE_ELEMENT_TYPE);
        return ChainElement.builder()
                .type(descriptor.getName())
                .name(descriptor.getTitle())
                .chain(reuseElement.getChain())
                .properties(Map.of(MigrationContext.REUSE_ELEMENT_ID, reuseElement.getId()))
                .build();
    }
}
