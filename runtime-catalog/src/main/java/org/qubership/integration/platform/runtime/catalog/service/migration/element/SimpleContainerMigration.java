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

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Extensible class for migrating a child element of restricted container element or
 * a container element, that can contain elements of any type.
 * Used for the following element types:
 * <li>try</li>
 * <li>catch</li>
 * <li>finally</li>
 * <li>when</li>
 * <li>otherwise</li>
 * <li>loop</li>
 * <li>circuit-breaker-configuration</li>
 * <li>on-fallback</li>
 * <li>main-split-element</li>
 * <li>split-element</li>
 * <li>async-split-element</li>
 *
 * @since 2023.2
 */
public abstract class SimpleContainerMigration extends ElementMigration {

    protected SimpleContainerMigration(
            LibraryElementsService libraryService,
            String oldElementType,
            String newElementType
    ) {
        super(libraryService, oldElementType, newElementType);
    }

    @Override
    public boolean canBeMigrated(ChainElement chainElement, MigrationContext context) {
        ElementDescriptor descriptor = libraryService.getElementDescriptor(getNewElementType());
        if (descriptor == null) {
            return false;
        }

        return isOutputDependenciesValid(chainElement, context);
    }

    @Override
    public ChainElement migrate(ChainElement chainElement, MigrationContext context) {
        List<ChainElement> children = collectChildren(chainElement, context);
        ContainerChainElement newChainElement = buildContainerFromChainElement(chainElement);
        newChainElement.setType(getNewElementType());
        newChainElement.addChildrenElements(children);

        context.addElementToDelete(chainElement);
        context.addMigratedElement(newChainElement);

        for (Dependency outputDependency : chainElement.getOutputDependencies()) {
            outputDependency.getElementTo().getInputDependencies().removeIf(
                    dependency -> StringUtils.equals(dependency.getElementFrom().getId(), chainElement.getId())
            );
        }

        return newChainElement;
    }

    private boolean isOutputDependenciesValid(ChainElement chainElement, MigrationContext context) {
        for (Dependency outputDependency : chainElement.getOutputDependencies()) {
            ChainElement elementTo = outputDependency.getElementTo();

            ElementMigration elementMigration = context.getElementMigration(elementTo.getType());
            if (elementMigration != null && !elementMigration.canBeMigrated(elementTo, context)) {
                return false;
            }

            if (!isOutputDependenciesValid(elementTo, context)) {
                return false;
            }
        }

        return true;
    }


    @Component
    public static class TryMigration extends SimpleContainerMigration {

        @Autowired
        public TryMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_TRY_TYPE, MigrationContext.NEW_TRY_TYPE);
        }
    }

    @Component
    public static class CatchMigration extends SimpleContainerMigration {

        @Autowired
        public CatchMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_CATCH_TYPE, MigrationContext.NEW_CATCH_TYPE);
        }
    }

    @Component
    public static class FinallyMigration extends SimpleContainerMigration {

        @Autowired
        public FinallyMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_FINALLY_TYPE, MigrationContext.NEW_FINALLY_TYPE);
        }
    }

    @Component
    public static class WhenMigration extends SimpleContainerMigration {

        @Autowired
        public WhenMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_WHEN_TYPE, MigrationContext.NEW_WHEN_TYPE);
        }
    }

    @Component
    public static class OtherwiseMigration extends SimpleContainerMigration {

        @Autowired
        public OtherwiseMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_OTHERWISE_TYPE, MigrationContext.NEW_OTHERWISE_TYPE);
        }
    }

    @Component
    public static class LoopMigration extends SimpleContainerMigration {

        @Autowired
        public LoopMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_LOOP_TYPE, MigrationContext.NEW_LOOP_TYPE);
        }

        @Override
        public boolean canBeMigrated(ChainElement chainElement, MigrationContext context) {
            if (!(chainElement instanceof ContainerChainElement containerElement)) {
                return false;
            }

            return containerElement.getElements().size() == 1
                    && super.canBeMigrated(containerElement.getElements().get(0), context);
        }

        @Override
        public ChainElement migrate(ChainElement chainElement, MigrationContext context) {
            context.getInProgressElementIds().add(chainElement.getId());

            ContainerChainElement loopContainer = (ContainerChainElement) chainElement;
            ChainElement loopExpression = loopContainer.getElements().get(0);
            List<ChainElement> newChildren = collectChildren(loopExpression, context);

            loopContainer.setType(getNewElementType());
            loopContainer.setProperties(loopExpression.getProperties());
            loopContainer.addChildrenElements(newChildren);

            for (Dependency outputDependency : loopExpression.getOutputDependencies()) {
                outputDependency.getElementTo().getInputDependencies()
                        .removeIf(dependency -> StringUtils.equals(dependency.getElementFrom().getId(), loopExpression.getId()));
            }
            loopContainer.removeChildElement(loopExpression);
            context.addElementToDelete(loopExpression);

            context.getInProgressElementIds().remove(chainElement.getId());

            migrateNextElements(loopContainer, context);

            return loopContainer;
        }
    }

    @Component
    public static class CircuitBreakerConfigurationMigration extends SimpleContainerMigration {

        @Autowired
        public CircuitBreakerConfigurationMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_CIRCUIT_BREAKER_CONFIGURATION_TYPE, MigrationContext.NEW_CIRCUIT_BREAKER_CONFIGURATION_TYPE);
        }
    }

    @Component
    public static class OnFallbackMigration extends SimpleContainerMigration {

        @Autowired
        public OnFallbackMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_ON_FALLBACK_TYPE, MigrationContext.NEW_ON_FALLBACK_TYPE);
        }
    }

    @Component
    public static class MainSplitElementMigration extends SimpleContainerMigration {

        @Autowired
        public MainSplitElementMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_MAIN_SPLIT_ELEMENT_TYPE, MigrationContext.NEW_MAIN_SPLIT_ELEMENT_TYPE);
        }
    }

    @Component
    public static class SplitElementMigration extends SimpleContainerMigration {

        @Autowired
        public SplitElementMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_SPLIT_ELEMENT_TYPE, MigrationContext.NEW_SPLIT_ELEMENT_TYPE);
        }
    }

    @Component
    public static class AsyncSplitElementMigration extends SimpleContainerMigration {

        @Autowired
        public AsyncSplitElementMigration(LibraryElementsService libraryService) {
            super(libraryService, MigrationContext.OLD_ASYNC_SPLIT_ELEMENT_TYPE, MigrationContext.NEW_ASYNC_SPLIT_ELEMENT_TYPE);
        }
    }
}
