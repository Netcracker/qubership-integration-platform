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

import org.apache.commons.lang3.math.NumberUtils;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.runtime.catalog.util.DistinctByKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Extensible class for migrating an old restricted (container with element type restriction)
 * container-type element to a new version.
 * Used for the following element types:
 * <li>try-catch-finally</li>
 * <li>choice</li>
 * <li>circuit-breaker</li>
 * <li>split</li>
 * <li>split-async</li>
 *
 * @since 2023.2
 */
public abstract class RestrictedContainerMigration extends ElementMigration {

    protected RestrictedContainerMigration(
            LibraryElementsService libraryService,
            String oldElementType,
            String newElementType
    ) {
        super(libraryService, oldElementType, newElementType);
    }

    @Override
    public boolean canBeMigrated(ChainElement chainElement, MigrationContext context) {
        if (context.isElementChecked(chainElement)) {
            return true;
        }

        if (!(chainElement instanceof ContainerChainElement)) {
            return false;
        }

        context.getCheckedElementIds().add(chainElement.getId());

        return ((ContainerChainElement) chainElement).getElements().stream()
                .filter(element -> !isElementDeprecated(element.getType()))
                .allMatch(element -> Optional.ofNullable(context.getElementMigration(element.getType()))
                        .map(elementMigration -> elementMigration.canBeMigrated(element, context))
                        .orElse(false));
    }

    @Override
    public ChainElement migrate(ChainElement chainElement, MigrationContext context) {
        context.getInProgressElementIds().add(chainElement.getId());

        ContainerChainElement containerElement = (ContainerChainElement) chainElement;
        List<ChainElement> newChildren = new LinkedList<>();
        List<ChainElement> deprecatedChildren = new LinkedList<>();
        for (ChainElement child : containerElement.getElements()) {
            if (isElementDeprecated(child.getType())) {
                deprecatedChildren.add(child);
                continue;
            }
            ElementMigration elementMigration = context.getElementMigration(child.getType());
            if (elementMigration != null) {
                newChildren.add(elementMigration.migrate(child, context));
            }
        }

        for (ChainElement deprecatedChild : deprecatedChildren) {
            migrateDeprecatedChild(containerElement, deprecatedChild, context);
            context.addElementToDelete(deprecatedChild);
        }

        containerElement.getElements().clear();
        containerElement.setType(getNewElementType());
        containerElement.addChildrenElements(newChildren);
        containerElement = postMigration(containerElement, context);

        context.getInProgressElementIds().remove(chainElement.getId());

        migrateNextElements(containerElement, context);

        return containerElement;
    }

    protected boolean isElementDeprecated(String elementType) {
        return false;
    }

    protected ContainerChainElement postMigration(ContainerChainElement containerElement, MigrationContext context) {
        return containerElement;
    }

    /**
     * Removes deprecated child element, that is not present in the new version of container element.
     *
     * @param parentElement restricted container element
     * @param deprecatedChild deprecated child to remove
     * @param context migration context
     */
    protected void migrateDeprecatedChild(
            ContainerChainElement parentElement,
            ChainElement deprecatedChild,
            MigrationContext context
    ) {
        List<ChainElement> parentOutputElements = parentElement.getOutputDependencies().stream()
                .peek(dependency -> dependency.getElementTo().getInputDependencies().remove(dependency))
                .peek(context::addDependencyToDelete)
                .map(Dependency::getElementTo)
                .toList();
        parentElement.getOutputDependencies().clear();

        for (Dependency outputDependency : deprecatedChild.getOutputDependencies()) {
            ChainElement elementTo = outputDependency.getElementTo();
            Dependency newDependency = Dependency.of(parentElement, elementTo);
            parentElement.addOutputDependency(newDependency);
            elementTo.getInputDependencies().remove(outputDependency);
            elementTo.addInputDependency(newDependency);
        }

        extractBranchEndElements(deprecatedChild).stream()
                .filter(DistinctByKey.newInstance(ChainElement::getId))
                .forEach(endElement -> {
                    for (ChainElement parentOutputElement : parentOutputElements) {
                        Dependency newDependency = Dependency.of(endElement, parentOutputElement);
                        endElement.addOutputDependency(newDependency);
                        parentOutputElement.addInputDependency(newDependency);
                    }
                });
    }

    private List<ChainElement> extractBranchEndElements(ChainElement chainElement) {
        List<ChainElement> branchEndElements = new ArrayList<>();
        for (Dependency outputDependency : chainElement.getOutputDependencies()) {
            ChainElement elementTo = outputDependency.getElementTo();
            if (elementTo.getOutputDependencies().isEmpty()) {
                branchEndElements.add(elementTo);
                continue;
            }

            branchEndElements.addAll(extractBranchEndElements(elementTo));
        }
        return branchEndElements;
    }


    @Component
    public static class TryCatchFinallyMigration extends RestrictedContainerMigration {

        @Autowired
        public TryCatchFinallyMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_TRY_CATCH_FINALLY_TYPE, MigrationContext.NEW_TRY_CATCH_FINALLY_TYPE);
        }

        /**
         * Sets the priority property for elements of type catch.
         *
         * @param containerElement element of type try-catch-finally
         * @param context migration context
         * @return element of type try-catch-finally
         */
        @Override
        protected ContainerChainElement postMigration(ContainerChainElement containerElement, MigrationContext context) {
            List<ChainElement> catchElements = containerElement.getElements().stream()
                    .filter(element -> MigrationContext.NEW_CATCH_TYPE.equals(element.getType()))
                    .sorted((left, right) -> {
                        int leftPriority = NumberUtils.toInt(left.getPropertyAsString(MigrationContext.PRIORITY_NUMBER), 0);
                        int rightPriority = NumberUtils.toInt(right.getPropertyAsString(MigrationContext.PRIORITY_NUMBER), 0);
                        if (leftPriority == rightPriority) {
                            return 0;
                        }
                        return leftPriority > rightPriority ? 1 : -1;
                    })
                    .toList();
            ElementDescriptor newCatchDescriptor = libraryService.getElementDescriptor(MigrationContext.NEW_CATCH_TYPE);
            for (int i = 0; i < catchElements.size(); i++) {
                ChainElement catchElement = catchElements.get(i);
                catchElement.getProperties().remove(MigrationContext.PRIORITY_NUMBER);
                catchElement.getProperties().put(newCatchDescriptor.getPriorityProperty(), i);
            }
            return containerElement;
        }
    }

    @Component
    public static class ChoiceMigration extends RestrictedContainerMigration {

        @Autowired
        public ChoiceMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_CHOICE_TYPE, MigrationContext.NEW_CHOICE_TYPE);
        }

        /**
         * Sets the priority property for elements of type when.
         *
         * @param containerElement element of type choice
         * @param context migration context
         * @return element of type choice
         */
        @Override
        protected ContainerChainElement postMigration(ContainerChainElement containerElement, MigrationContext context) {
            List<ChainElement> whenChildren = containerElement.getElements().stream()
                    .filter(element -> MigrationContext.NEW_WHEN_TYPE.equals(element.getType()))
                    .sorted((left, right) -> {
                        int leftPriority = NumberUtils.toInt(left.getPropertyAsString(MigrationContext.PRIORITY_NUMBER), 0);
                        int rightPriority = NumberUtils.toInt(right.getPropertyAsString(MigrationContext.PRIORITY_NUMBER), 0);
                        if (leftPriority == rightPriority) {
                            return 0;
                        }
                        return leftPriority > rightPriority ? 1 : -1;
                    })
                    .toList();
            ElementDescriptor whenDescriptor = libraryService.getElementDescriptor(MigrationContext.NEW_WHEN_TYPE);
            for (int i = 0; i < whenChildren.size(); i++) {
                ChainElement catchElement = whenChildren.get(i);
                catchElement.getProperties().remove(MigrationContext.PRIORITY_NUMBER);
                whenChildren.get(i).getProperties().put(whenDescriptor.getPriorityProperty(), i);
            }
            return containerElement;
        }
    }

    @Component
    public static class CircuitBreakerMigration extends RestrictedContainerMigration {

        @Autowired
        public CircuitBreakerMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_CIRCUIT_BREAKER_TYPE, MigrationContext.NEW_CIRCUIT_BREAKER_TYPE);
        }
    }

    @Component
    public static class SplitMigration extends RestrictedContainerMigration {

        @Autowired
        public SplitMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_SPLIT_TYPE, MigrationContext.NEW_SPLIT_TYPE);
        }

        @Override
        public boolean canBeMigrated(ChainElement chainElement, MigrationContext context) {
            if (!super.canBeMigrated(chainElement, context)) {
                return false;
            }

            long splitResultCount = ((ContainerChainElement) chainElement).getElements().stream()
                    .filter(element -> isElementDeprecated(element.getType()))
                    .count();
            return splitResultCount <= 1;
        }

        @Override
        protected boolean isElementDeprecated(String elementType) {
            return MigrationContext.OLD_SPLIT_RESULT_TYPE.equals(elementType);
        }
    }

    @Component
    public static class SplitAsyncMigration extends RestrictedContainerMigration {

        @Autowired
        public SplitAsyncMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_SPLIT_ASYNC_TYPE, MigrationContext.NEW_SPLIT_ASYNC_TYPE);
        }

        @Override
        public boolean canBeMigrated(ChainElement chainElement, MigrationContext context) {
            if (!super.canBeMigrated(chainElement, context)) {
                return false;
            }

            long syncSplitCount = ((ContainerChainElement) chainElement).getElements().stream()
                    .filter(element -> isElementDeprecated(element.getType()))
                    .count();
            return syncSplitCount <= 1;
        }

        @Override
        protected boolean isElementDeprecated(String elementType) {
            return MigrationContext.OLD_SYNC_SPLIT_ELEMENT_TYPE.equals(elementType);
        }
    }
}
