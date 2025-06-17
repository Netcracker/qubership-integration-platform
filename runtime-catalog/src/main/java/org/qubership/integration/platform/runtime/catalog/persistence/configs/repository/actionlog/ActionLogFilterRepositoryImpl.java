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
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ActionLogException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.InvalidEnumConstantException;
import org.qubership.integration.platform.runtime.catalog.model.dto.actionlog.ActionLogFilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.model.filter.ActionLogFilterColumn;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;

public class ActionLogFilterRepositoryImpl implements ActionLogFilterRepository {

    private static final Map<ActionLogFilterColumn, Pair<String, Function<String, ?>>> FILTER_ENTITY_COLUMN_MAPPING = Map.of(
            ActionLogFilterColumn.ENTITY_ID, Pair.of("entityId", Function.identity()),
            ActionLogFilterColumn.ENTITY_NAME, Pair.of("entityName", Function.identity()),
            ActionLogFilterColumn.PARENT_ID, Pair.of("parentId", Function.identity()),
            ActionLogFilterColumn.PARENT_NAME, Pair.of("parentName", Function.identity()),
            ActionLogFilterColumn.REQUEST_ID, Pair.of("requestId", Function.identity()),
            ActionLogFilterColumn.OPERATION, Pair.of("operation", LogOperation::valueOf),
            ActionLogFilterColumn.ENTITY_TYPE, Pair.of("entityType", EntityType::valueOf),
            ActionLogFilterColumn.ACTION_TIME, Pair.of("actionTime", Function.identity()),
            ActionLogFilterColumn.INITIATOR, Pair.of("user.username", Function.identity())
    );
    private static final String ACTION_TIME_COLUMN = "actionTime";

    @PersistenceContext
    private EntityManager entityManager;


    @Override
    public List<ActionLog> findActionLogsByFilter(
            Timestamp offsetTime, long rangeTime, List<ActionLogFilterRequestDTO> filters) throws InvalidEnumConstantException {
        CriteriaQuery<ActionLog> query = buildFilterQuery(offsetTime, rangeTime, filters);
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public long getRecordsCountAfterTime(Timestamp timestamp, List<ActionLogFilterRequestDTO> filters) {
        CriteriaQuery<Long> query = getRecordsCount(timestamp, filters);
        return entityManager.createQuery(query).getSingleResult();
    }

    public CriteriaQuery<Long> getRecordsCount(Timestamp timestamp, List<ActionLogFilterRequestDTO> filters) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<ActionLog> actionLog = query.from(ActionLog.class);
        List<Predicate> predicates = new LinkedList<>(); // combined with 'AND'

        // record_time <= timestamp
        predicates.add(builder.lessThanOrEqualTo(actionLog.get(ACTION_TIME_COLUMN), timestamp));

        removeRedundantFilters(filters);
        addFiltersToQuery(filters, builder, actionLog, predicates);

        query = query.select(builder.count(actionLog));
        Predicate finalPredicate = builder.and(predicates.toArray(new Predicate[0]));

        return (!predicates.isEmpty()
                ? query.where(finalPredicate)
                : query);
    }

    private CriteriaQuery<ActionLog> buildFilterQuery(
            Timestamp offsetTime,
            long rangeTime,
            List<ActionLogFilterRequestDTO> filters) throws InvalidEnumConstantException {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<ActionLog> query = builder.createQuery(ActionLog.class);
        Root<ActionLog> actionLog = query.from(ActionLog.class);
        List<Predicate> predicates = new LinkedList<>(); // combined with 'AND'

        // record_time > (offsetTime - rangeTime)
        predicates.add(builder.greaterThan(actionLog.get(ACTION_TIME_COLUMN), new Timestamp(offsetTime.getTime() - rangeTime)));
        // record_time <= offsetTime
        predicates.add(builder.lessThanOrEqualTo(actionLog.get(ACTION_TIME_COLUMN), offsetTime));

        removeRedundantFilters(filters);
        addFiltersToQuery(filters, builder, actionLog, predicates);

        query = query.select(actionLog);
        Predicate finalPredicate = builder.and(predicates.toArray(new Predicate[0]));

        return (!predicates.isEmpty()
                    ? query.where(finalPredicate)
                    : query)
                .orderBy(Collections.singletonList(builder.desc(actionLog.get(ACTION_TIME_COLUMN))));
    }

    private void addFiltersToQuery(List<ActionLogFilterRequestDTO> filters, CriteriaBuilder builder, Root<ActionLog> actionLog, List<Predicate> predicates) {
        for (ActionLogFilterColumn actionLogFilterColumn : ActionLogFilterColumn.values()) {

            // add filters
            for (ActionLogFilterRequestDTO filter : filters) {
                String value = filter.getValue();
                ActionLogFilterColumn column = filter.getColumn();
                Pair<String, Function<String, ?>> columnMapping = FILTER_ENTITY_COLUMN_MAPPING.get(column);
                String columnName = columnMapping.getKey();
                Function<String, ?> valueConverter = columnMapping.getValue();

                if (columnName == null) {
                    throw new ActionLogException("Filter column not found: " + column);
                }

                if (filter.getColumn() == actionLogFilterColumn) {
                    Path valuePath = actionLog;
                    for (String path : columnName.split("\\.")) {
                        valuePath = valuePath.get(path);
                    }
                    switch (filter.getCondition()) {
                        case IS -> predicates.add(builder.equal(valuePath, value));
                        case IS_NOT -> predicates.add(builder.notEqual(valuePath, value));
                        case CONTAINS -> predicates.add(
                                builder.like(
                                        builder.lower(valuePath),
                                        "%" + value.toLowerCase() + "%"));
                        case DOES_NOT_CONTAIN -> predicates.add(
                                builder.notLike(
                                        builder.lower(valuePath),
                                        "%" + value.toLowerCase() + "%"));
                        case START_WITH -> predicates.add(
                                builder.like(
                                        builder.lower(valuePath),
                                        value.toLowerCase() + "%"));
                        case ENDS_WITH -> predicates.add(
                                builder.like(
                                        builder.lower(valuePath),
                                        "%" + value.toLowerCase()));
                        case EMPTY -> predicates.add(builder.or(
                                valuePath.isNull(),
                                builder.equal(valuePath, "")));
                        case NOT_EMPTY -> predicates.add(builder.and(
                                valuePath.isNotNull(),
                                builder.notEqual(valuePath, "")));
                        case IN -> predicates.add(valuePath.in(Arrays.stream(value.split(",")).map(valueConverter).toList()));
                        case NOT_IN -> predicates.add(valuePath.in(Arrays.stream(value.split(",")).map(valueConverter).toList()).not());
                        case IS_BEFORE -> predicates.add(builder.lt(valuePath, Long.parseLong(value)));
                        case IS_AFTER -> predicates.add(builder.gt(valuePath, Long.parseLong(value)));
                        case IS_WITHIN -> {
                            String[] dates = value.split(",");
                            predicates.add(builder.between(valuePath, Long.parseLong(dates[0]), Long.parseLong(dates[1])));
                        }
                    }
                }
            }
        }
    }

    private void removeRedundantFilters(List<ActionLogFilterRequestDTO> filters) {
        List<ActionLogFilterRequestDTO> filtersToRemove = new ArrayList<>();

        for (ActionLogFilterRequestDTO filter : filters) {
            if (filter.getCondition().equals(FilterCondition.IS)) {
                ActionLogFilterRequestDTO oppositeFilter = new ActionLogFilterRequestDTO();
                oppositeFilter.setValue(filter.getValue());
                oppositeFilter.setColumn(filter.getColumn());
                oppositeFilter.setCondition(FilterCondition.IS_NOT);

                if (filters.contains(oppositeFilter)) {
                    filtersToRemove.add(filter);
                }
            }
        }

        filters.removeAll(filtersToRemove);
    }
}
