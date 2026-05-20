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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.common;


import java.util.Comparator;

/**
 * Utility class for perform comparing entities during merging entities collection in persistent context.
 * Entities DTO should implement|inherit {@link Comparable} interface
 * and has implementation of {@link Comparable#compareTo} method according to entity structure specific
 * Using in {@link CommonRepository#actualizeCollectionState}
 */
public class EntityComparator<S extends Comparable<S>> implements Comparator<S> {
    @Override
    public int compare(S o1, S o2) {
       return o1.compareTo(o2);
    }
}

