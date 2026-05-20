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

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.CONTAINER;
import static org.qubership.integration.platform.runtime.catalog.service.migration.element.MigrationContext.REUSED_ELEMENT;

/**
 * Extensible class for migrating an old container-type element to a new version.
 *
 * @since 2023.2
 */
public abstract class ElementMigration {

    protected final LibraryElementsService libraryService;
    @Getter
    protected final String oldElementType;
    @Getter
    protected final String newElementType;

    protected ElementMigration(LibraryElementsService libraryService, String oldElementType, String newElementType) {
        this.libraryService = libraryService;
        this.oldElementType = oldElementType;
        this.newElementType = newElementType;
    }

    public abstract boolean canBeMigrated(ChainElement chainElement, MigrationContext context);

    public abstract ChainElement migrate(ChainElement chainElement, MigrationContext context);

    /**
     * Checks whether the output dependencies for the current element can be migrated.
     * If not, it is called recursively on the output dependency.
     *
     * @param chainElement current element
     * @param context migration context
     */
    protected void migrateNextElements(ChainElement chainElement, MigrationContext context) {
        for (Dependency outputDependency : chainElement.getOutputDependencies()) {
            ChainElement elementTo = outputDependency.getElementTo();
            Optional.ofNullable(chainElement.getParent())
                    .filter(parent -> !CONTAINER.equals(parent.getType()) && !parent.getElements().contains(elementTo))
                    .ifPresent(parent -> parent.addChildElement(elementTo));

            ElementMigration elementMigration = context.getElementMigration(elementTo.getType());
            if (elementMigration != null) {
                elementMigration.migrate(elementTo, context);
                continue;
            }
            migrateNextElements(elementTo, context);
        }
    }

    /**
     * Collects list of all output dependency elements.
     * <li>If output element has multiple input dependencies, replace it with reuse-reference element;</li>
     * <li>Migrates output element if possible.</li>
     *
     * @param chainElement current element
     * @param context migration context
     * @return list of all output dependency elements
     */
    protected List<ChainElement> collectChildren(ChainElement chainElement, MigrationContext context) {
        List<ChainElement> children = new ArrayList<>();
        for (Dependency outputDependency : new ArrayList<>(chainElement.getOutputDependencies())) {
            ChainElement elementTo = outputDependency.getElementTo();
            if (elementTo.getInputDependencies().size() > 1
                    || context.getReuseElements().containsKey(elementTo.getId())
                    || context.isElementMigrationInProgress(elementTo)
            ) {
                removeElementFromParentGroupIfRequired(elementTo, context);
                children.add(replaceReusedElementWithReference(chainElement, elementTo, context));
                if (elementTo.getInputDependencies().isEmpty()) {
                    continue;
                }
                for (Dependency inputDependency : new ArrayList<>(elementTo.getInputDependencies())) {
                    if (context.isReferenceInputInProgress(inputDependency.getElementFrom())) {
                        continue;
                    }

                    ChainElement referenceElement = replaceReusedElementWithReference(
                            inputDependency.getElementFrom(), inputDependency.getElementTo(), context
                    );
                    elementTo.getChain().addElement(referenceElement);
                }
            } else {
                ElementMigration elementMigration = context.getElementMigration(elementTo.getType());
                if (elementMigration != null) {
                    children.add(elementMigration.migrate(elementTo, context));
                } else {
                    children.add(elementTo);
                }
                children.addAll(collectChildren(elementTo, context));
            }
        }
        children.forEach(child -> removeElementFromParentGroupIfRequired(child, context));
        return children;
    }

    /**
     * Replaces elementTo with an element of type reuse-reference.
     *
     * @param elementFrom input dependency element
     * @param elementTo element with multiple input dependencies
     * @param context migration context
     * @return element of type reuse-reference
     */
    protected ChainElement replaceReusedElementWithReference(
            ChainElement elementFrom,
            ChainElement elementTo,
            MigrationContext context
    ) {
        context.getInProgressReferenceInputIds().add(elementFrom.getId());
        ElementMigration reuseElementMigration = context.getElementMigration(REUSED_ELEMENT);
        if (reuseElementMigration == null) {
            throw new IllegalArgumentException("Reused elements cannot be migrated");
        }

        ChainElement referenceElement = reuseElementMigration.migrate(elementTo, context);
        elementTo.getInputDependencies().removeIf(dependency -> {
            if (StringUtils.equals(dependency.getElementFrom().getId(), elementFrom.getId())) {
                context.addDependencyToDelete(dependency);
                return true;
            }
            return false;
        });
        elementFrom.getOutputDependencies().removeIf(dependency ->
                StringUtils.equals(dependency.getElementTo().getId(), elementTo.getId()));
        Dependency referenceDependency = Dependency.of(elementFrom, referenceElement);
        elementFrom.addOutputDependency(referenceDependency);
        referenceElement.addInputDependency(referenceDependency);

        context.getInProgressReferenceInputIds().remove(elementFrom.getId());
        return referenceElement;
    }

    /**
     * Removes an element from group container if it is in it. If group container is empty,
     * removes it from chain.
     *
     * @param element element, possibly located in a group container
     * @param context migration context
     */
    protected void removeElementFromParentGroupIfRequired(ChainElement element, MigrationContext context) {
        if (element.getParent() != null && CONTAINER.equals(element.getParent().getType())) {
            ContainerChainElement parentGroup = element.getParent();

            removeElementFromParentGroupIfRequired(parentGroup, context);

            parentGroup.removeChildElement(element);
            if (parentGroup.getElements().isEmpty()) {
                context.addGroupToDelete(parentGroup);
                element.getChain().removeElement(parentGroup);
            }
        }
    }

    protected ContainerChainElement buildContainerFromChainElement(ChainElement chainElement) {
        return ContainerChainElement.builder()
                .type(chainElement.getType())
                .name(chainElement.getName())
                .description(chainElement.getDescription())
                .createdBy(chainElement.getCreatedBy())
                .createdWhen(chainElement.getCreatedWhen())
                .modifiedBy(chainElement.getModifiedBy())
                .modifiedWhen(chainElement.getModifiedWhen())
                .originalId(chainElement.getOriginalId())
                .parent(chainElement.getParent())
                .chain(chainElement.getChain())
                .environment(chainElement.getEnvironment())
                .properties(chainElement.getProperties())
                .snapshot(chainElement.getSnapshot())
                .build();
    }
}
