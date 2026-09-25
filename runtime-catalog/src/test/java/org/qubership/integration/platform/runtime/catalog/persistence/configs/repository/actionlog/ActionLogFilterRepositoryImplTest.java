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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.actionlog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.dto.actionlog.ActionLogFilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.model.filter.ActionLogFilterColumn;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.service.filter.FilterConditionPredicateBuilderFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionLogFilterRepositoryImplTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Mock
    private CriteriaQuery<ActionLog> criteriaQuery;

    @Mock
    private Root<ActionLog> root;

    @Mock
    private TypedQuery<ActionLog> typedQuery;

    @Mock
    private Path<Object> actionTimePath;

    @Mock
    private Path<Object> entityIdPath;

    @Mock
    private Path<Object> columnPath;

    @Mock
    private Expression<String> columnText;

    @Mock
    private Expression<String> pattern;

    @Mock
    private Order order;

    @Mock
    private Predicate predicate;

    private ActionLogFilterRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new ActionLogFilterRepositoryImpl();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        ReflectionTestUtils.setField(repository, "filterConditionPredicateBuilderFactory",
                new FilterConditionPredicateBuilderFactory());
    }

    @Test
    @DisplayName("findActionLogsByFilter applies offset and limit")
    void findActionLogsByFilterAppliesOffsetAndLimit() {
        List<ActionLogFilterRequestDTO> filters = Collections.emptyList();
        ActionLog log = ActionLog.builder().id("log-1").build();
        List<ActionLog> expected = List.of(log);

        stubEmptyFilterQuery();

        when(entityManager.createQuery(criteriaQuery)).thenReturn(typedQuery);
        when(typedQuery.setFirstResult(10)).thenReturn(typedQuery);
        when(typedQuery.setMaxResults(50)).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(expected);

        List<ActionLog> result = repository.findActionLogsByFilter(10, 50, filters, null);

        assertThat(result).isEqualTo(expected);
        verify(typedQuery).setFirstResult(10);
        verify(typedQuery).setMaxResults(50);
    }

    @Test
    @DisplayName("findActionLogsByFilter applies entity id filter predicates")
    void findActionLogsByFilterAppliesEntityIdFilter() {
        ActionLogFilterRequestDTO filter = new ActionLogFilterRequestDTO();
        filter.setColumn(ActionLogFilterColumn.ENTITY_ID);
        filter.setCondition(FilterCondition.IS);
        filter.setValue("entity-1");

        ActionLog log = ActionLog.builder().id("log-1").entityId("entity-1").build();

        when(entityManager.getCriteriaBuilder()).thenReturn(criteriaBuilder);
        when(criteriaBuilder.createQuery(ActionLog.class)).thenReturn(criteriaQuery);
        when(criteriaQuery.from(ActionLog.class)).thenReturn(root);
        when(criteriaQuery.select(root)).thenReturn(criteriaQuery);
        when(root.get("entityId")).thenReturn(entityIdPath);
        when(criteriaBuilder.equal(entityIdPath, "entity-1")).thenReturn(predicate);
        when(root.get("actionTime")).thenReturn(actionTimePath);
        when(criteriaBuilder.desc(actionTimePath)).thenReturn(order);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(predicate);
        when(criteriaQuery.where(predicate)).thenReturn(criteriaQuery);
        when(criteriaQuery.orderBy(order)).thenReturn(criteriaQuery);
        when(entityManager.createQuery(criteriaQuery)).thenReturn(typedQuery);
        when(typedQuery.setFirstResult(0)).thenReturn(typedQuery);
        when(typedQuery.setMaxResults(100)).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(List.of(log));

        List<ActionLog> result = repository.findActionLogsByFilter(0, 100, List.of(filter), null);

        assertThat(result).containsExactly(log);
        verify(criteriaBuilder).equal(entityIdPath, "entity-1");
    }

    @Test
    @DisplayName("A value outside the column's enum is rejected instead of escaping as a 500")
    void findActionLogsByFilterRejectsValueOutsideEnum() {
        ActionLogFilterRequestDTO filter = new ActionLogFilterRequestDTO();
        filter.setColumn(ActionLogFilterColumn.ENTITY_TYPE);
        filter.setCondition(FilterCondition.IN);
        filter.setValue("NO_SUCH_TYPE");

        when(entityManager.getCriteriaBuilder()).thenReturn(criteriaBuilder);
        when(criteriaBuilder.createQuery(ActionLog.class)).thenReturn(criteriaQuery);
        when(criteriaQuery.from(ActionLog.class)).thenReturn(root);
        when(root.get("entityType")).thenReturn(entityIdPath);

        assertThatThrownBy(() -> repository.findActionLogsByFilter(0, 100, List.of(filter), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("NO_SUCH_TYPE")
                .hasMessageContaining("CHAIN");
    }

    @Test
    @DisplayName("findActionLogsByFilter matches the search string literally in any searchable column, ignoring case")
    void findActionLogsByFilterMatchesSearchStringInAnyColumn() {
        when(root.get(anyString())).thenReturn(columnPath);
        stubEmptyFilterQuery();
        when(columnPath.get(anyString())).thenReturn(columnPath);
        when(columnPath.as(String.class)).thenReturn(columnText);
        when(criteriaBuilder.lower(columnText)).thenReturn(columnText);
        when(criteriaBuilder.literal("%ALICE\\_1%")).thenReturn(pattern);
        when(criteriaBuilder.lower(pattern)).thenReturn(pattern);
        when(criteriaBuilder.like(columnText, pattern, '\\')).thenReturn(predicate);
        when(criteriaBuilder.or(any(Predicate[].class))).thenReturn(predicate);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(predicate);
        when(criteriaQuery.where(predicate)).thenReturn(criteriaQuery);
        when(entityManager.createQuery(criteriaQuery)).thenReturn(typedQuery);
        when(typedQuery.setFirstResult(0)).thenReturn(typedQuery);
        when(typedQuery.setMaxResults(100)).thenReturn(typedQuery);

        repository.findActionLogsByFilter(0, 100, Collections.emptyList(), "ALICE_1");

        verify(criteriaBuilder, times(10)).like(columnText, pattern, '\\');
        verify(criteriaBuilder, times(10)).lower(pattern);
        verify(columnPath).get("username");
        verify(columnPath).get("id");
        for (String column : List.of("operation", "requestId", "entityType", "entityName", "entityId",
                "parentType", "parentName", "parentId")) {
            verify(root).get(column);
        }
    }

    private void stubEmptyFilterQuery() {
        when(entityManager.getCriteriaBuilder()).thenReturn(criteriaBuilder);
        when(criteriaBuilder.createQuery(ActionLog.class)).thenReturn(criteriaQuery);
        when(criteriaQuery.from(ActionLog.class)).thenReturn(root);
        when(criteriaQuery.select(root)).thenReturn(criteriaQuery);
        when(root.get("actionTime")).thenReturn(actionTimePath);
        when(criteriaBuilder.desc(actionTimePath)).thenReturn(order);
        when(criteriaQuery.orderBy(order)).thenReturn(criteriaQuery);
    }
}
