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

/**
 * Custom common repository for managing persisting entities at runtime.
 * Allow to change entity state manually from implemented CRUD repositories
 * in case of complex data manipulation operation
 * to avoid nested entities exceptions
 * and reduce count of manual commit operation to DB
 */

public interface CommonRepository<T> {

    /**
     * Move brand-new entity from transient to managed state.
     * <ul>Generate INSERT SQL statement at flush stage.</ul>
     *
     * @param entity brand-new entity which need to be created in DB
     */
    <S extends T> S persist(S entity);

    /**
     * Move entity to detached state
     * For entity already associated with record in DB
     * in case when it has to be replaced with different version
     * (e.g. during import configuration for chain that exists on environment).
     * <ul>Generate UPDATE SQL statement at flush stage</ul>
     *
     * @param entity entity which need to be replaced with different version
     */
    <S extends T> void detach(S entity);

    /**
     * Move entity to manage state
     * Replace entity already associated with record in DB with different version
     * (e.g. during import configuration for chain that exists on environment).
     * <ul>Generate UPDATE SQL statement at flush stage</ul>
     *
     * @param entity different version of existing entity
     */
    <S extends T> S merge(S entity);

    /**
     * Move entity to removed state
     * For entity that should be deleted
     * (e.g. during merging states of entities collection).
     * <ul>Generate DELETE SQL statement at flush stage</ul>
     *
     * @param entity entity that should be deleted from DB
     */
    <S extends T> void remove(S entity);

    /**
     * Save entity depends on current state (transient for brand-new entity or managed for existing).
     * <ul>Generate INSERT or DELETE SQL statement at flush stage</ul>
     */
    <S extends T> S saveEntity(S entity);

    <S extends T> void clearContext();

    /**
     * Replace current state of entity with new one.
     * <ul>Generate INSERT or UPDATE SQL statement at flush stage</ul>
     *
     * @param currentState entity object retried from DB
     * @param newState entity object created at runtime (e.g. during configuration import)
     */
    <S extends T> void actualizeObjectState(S currentState, S newState);

    /**
     * Replace current state of entities collection with new one.
     * <ul>
     * <li>Generate DELETE for current entities which not exists in new state of collection.</li>
     * <li>Generate UPDATE for matched entities.</li>
     * <li>Generate INSERT for new entities which not exists in current state of collection.</li>
     * </ul>
     * Entities comparing using {@link EntityComparator}.
     *
     * @param currentStates entities collection retried from DB
     * @param newStates entities collection created at runtime (e.g. during configuration import)
     */
    <S extends T> void actualizeCollectionState(Iterable<S> currentStates, Iterable<S> newStates);

    <S extends T> void actualizeCollectionStateWOUpdates(Iterable<S> currentStates, Iterable<S> newStates);

    <S extends T> void actualizeCollectionStateOnlyUpdates(Iterable<S> currentStates, Iterable<S> newStates);
}
