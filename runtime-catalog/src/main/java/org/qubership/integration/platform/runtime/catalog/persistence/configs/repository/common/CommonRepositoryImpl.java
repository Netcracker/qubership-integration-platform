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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Repository
class CommonRepositoryImpl<T> implements CommonRepository<T> {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public <S extends T> S persist(S entity) {
        entityManager.persist(entity);
        return entity;
    }

    @Override
    public <S extends T> void detach(S entity) {
        entityManager.detach(entity);
    }

    public <S extends T> void remove(S entity) {
        entityManager.remove(entity);
    }

    @Override
    public <S extends T> S merge(S entity) {
        return entityManager.merge(entity);
    }

    @Override
    public <S extends T> S saveEntity(S entity) {
        if (entityManager.contains(entity)) {
            return merge(entity);
        }
        return persist(entity);
    }

    @Override
    public <S extends T> void clearContext() {
        entityManager.flush();
        entityManager.clear();
    }

    @Override
    public <S extends T> void actualizeObjectState(S currentState, S newState) {
        if (currentState != null) {
            detach(currentState);
            merge(newState);
            return;
        }
        persist(newState);
    }

    @Override
    public <S extends T> void actualizeCollectionState(Iterable<S> currentStates, Iterable<S> newStates) {
        actualizeCollectionStateWOUpdates(currentStates, newStates);
        actualizeCollectionStateOnlyUpdates(currentStates, newStates);
    }

    public <S extends T> void actualizeCollectionStateWOUpdates(Iterable<S> currentStates, Iterable<S> newStates) {
        Supplier<Stream<S>> currentStatesSupplier = () -> StreamSupport.stream(currentStates.spliterator(), false);
        Supplier<Stream<S>> newStatesSupplier = () -> StreamSupport.stream(newStates.spliterator(), false);

        Comparator<S> entityMatcher = getComparator();

        Iterable<S> entitiesToDetach = getFilteredEntities(currentStatesSupplier, newStatesSupplier, entityMatcher, true);
        Iterable<S> entitiesToRemove = getFilteredEntities(currentStatesSupplier, newStatesSupplier, entityMatcher, false);
        Iterable<S> entitiesToPersist = getFilteredEntities(newStatesSupplier, currentStatesSupplier, entityMatcher, false);

        entitiesToDetach.forEach(this::detach);
        entitiesToRemove.forEach(this::remove);
        entitiesToPersist.forEach(this::persist);
    }

    public <S extends T> void actualizeCollectionStateOnlyUpdates(Iterable<S> currentStates, Iterable<S> newStates) {
        Supplier<Stream<S>> currentStatesSupplier = () -> StreamSupport.stream(currentStates.spliterator(), false);
        Supplier<Stream<S>> newStatesSupplier = () -> StreamSupport.stream(newStates.spliterator(), false);

        Comparator<S> entityMatcher = getComparator();

        Iterable<S> entitiesToMerge = getFilteredEntities(newStatesSupplier, currentStatesSupplier, entityMatcher, true);

        entitiesToMerge.forEach(this::merge);
    }

    private <S extends T> Iterable<S> getFilteredEntities(Supplier<Stream<S>> entitiesToBeFiltered, Supplier<Stream<S>> entitiesAsFilter, Comparator<S> filterEntityMatcher, boolean isMatched) {
        Predicate<? super S> filterPredicate = isMatched
                ? (entityToBeFiltered) -> entitiesAsFilter
                        .get()
                        .anyMatch(entityAsFilter -> filterEntityMatcher.compare(entityToBeFiltered, entityAsFilter) == 0)
                : (entityToBeFiltered) -> entitiesAsFilter
                        .get()
                        .noneMatch(entityAsFilter -> filterEntityMatcher.compare(entityToBeFiltered, entityAsFilter) == 0);

        return entitiesToBeFiltered
                .get()
                .filter(filterPredicate)
                .collect(Collectors.toList());
    }

    private EntityComparator getComparator() {
        return new EntityComparator();
    }
}
