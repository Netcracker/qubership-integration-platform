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

import lombok.Data;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * Contains information about migration process.
 *
 * @since 2023.2
 */
@Data
public class MigrationContext {

    public static final String OLD_TRY_CATCH_FINALLY_TYPE = "try-catch-finally";
    public static final String NEW_TRY_CATCH_FINALLY_TYPE = "try-catch-finally-2";
    public static final String OLD_TRY_TYPE = "try";
    public static final String NEW_TRY_TYPE = "try-2";
    public static final String OLD_CATCH_TYPE = "catch";
    public static final String NEW_CATCH_TYPE = "catch-2";
    public static final String OLD_FINALLY_TYPE = "finally";
    public static final String NEW_FINALLY_TYPE = "finally-2";
    public static final String OLD_CHOICE_TYPE = "choice";
    public static final String NEW_CHOICE_TYPE = "condition";
    public static final String OLD_WHEN_TYPE = "when";
    public static final String NEW_WHEN_TYPE = "if";
    public static final String OLD_OTHERWISE_TYPE = "otherwise";
    public static final String NEW_OTHERWISE_TYPE = "else";
    public static final String OLD_LOOP_TYPE = "loop";
    public static final String NEW_LOOP_TYPE = "loop-2";
    public static final String OLD_LOOP_EXPRESSION_TYPE = "loop-expression";
    public static final String OLD_CIRCUIT_BREAKER_TYPE = "circuit-breaker";
    public static final String NEW_CIRCUIT_BREAKER_TYPE = "circuit-breaker-2";
    public static final String OLD_CIRCUIT_BREAKER_CONFIGURATION_TYPE = "circuit-breaker-configuration";
    public static final String NEW_CIRCUIT_BREAKER_CONFIGURATION_TYPE = "circuit-breaker-configuration-2";
    public static final String OLD_ON_FALLBACK_TYPE = "on-fallback";
    public static final String NEW_ON_FALLBACK_TYPE = "on-fallback-2";
    public static final String OLD_SPLIT_TYPE = "split";
    public static final String NEW_SPLIT_TYPE = "split-2";
    public static final String OLD_MAIN_SPLIT_ELEMENT_TYPE = "main-split-element";
    public static final String NEW_MAIN_SPLIT_ELEMENT_TYPE = "main-split-element-2";
    public static final String OLD_SPLIT_ELEMENT_TYPE = "split-element";
    public static final String NEW_SPLIT_ELEMENT_TYPE = "split-element-2";
    public static final String OLD_SPLIT_RESULT_TYPE = "split-result";
    public static final String OLD_SPLIT_ASYNC_TYPE = "split-async";
    public static final String NEW_SPLIT_ASYNC_TYPE = "split-async-2";
    public static final String OLD_ASYNC_SPLIT_ELEMENT_TYPE = "async-split-element";
    public static final String NEW_ASYNC_SPLIT_ELEMENT_TYPE = "async-split-element-2";
    public static final String OLD_SYNC_SPLIT_ELEMENT_TYPE = "sync-split-element";
    public static final String REUSE_REFERENCE_ELEMENT_TYPE = "reuse-reference";
    public static final String REUSE_ELEMENT_TYPE = "reuse";
    public static final String REUSED_ELEMENT = "reused-element";
    public static final String PRIORITY_NUMBER = "priorityNumber";
    public static final String REUSE_ELEMENT_ID = "reuseElementId";

    private final Map<String, ElementMigration> elementMigrations;

    /**
     * IDs of elements that have already been checked for migration.
     */
    private final Set<String> checkedElementIds = new LinkedHashSet<>();
    /**
     * IDs of elements migration of that is in progress.
     */
    private final Set<String> inProgressElementIds = new LinkedHashSet<>();
    /**
     * IDs of elements that are an input dependency of reuse-reference element
     * that is in progress.
     */
    private final Set<String> inProgressReferenceInputIds = new LinkedHashSet<>();
    private final Map<String, ChainElement> migratedElements = new HashMap<>();
    // <first_child_id, reuse_element>
    private final Map<String, ContainerChainElement> reuseElements = new HashMap<>();
    private final List<ChainElement> elementsToDelete = new ArrayList<>();
    private final List<ChainElement> groupsToDelete = new ArrayList<>();
    private final List<Dependency> dependenciesToDelete = new ArrayList<>();

    @Nullable
    public ElementMigration getElementMigration(String elementType) {
        return elementMigrations.get(elementType);
    }

    public void addMigratedElement(ChainElement migratedElement) {
        migratedElements.put(migratedElement.getId(), migratedElement);
    }

    public void addReuseElement(String firstElementId, ContainerChainElement reuseElement) {
        reuseElements.put(firstElementId, reuseElement);
    }

    public void addElementToDelete(ChainElement element) {
        elementsToDelete.add(element);
    }

    public void addGroupToDelete(ChainElement groupElement) {
        groupsToDelete.add(groupElement);
    }

    public void addDependencyToDelete(Dependency dependency) {
        dependenciesToDelete.add(dependency);
    }

    public boolean isElementChecked(@NonNull ChainElement element) {
        return checkedElementIds.contains(element.getId());
    }

    public boolean isElementMigrationInProgress(@NonNull ChainElement element) {
        return inProgressElementIds.contains(element.getId());
    }

    public boolean isReferenceInputInProgress(@NonNull ChainElement element) {
        return inProgressReferenceInputIds.contains(element.getId());
    }
}
