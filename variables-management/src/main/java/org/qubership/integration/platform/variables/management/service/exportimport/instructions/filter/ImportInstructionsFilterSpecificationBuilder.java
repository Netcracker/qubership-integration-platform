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

package org.qubership.integration.platform.variables.management.service.exportimport.instructions.filter;

import jakarta.persistence.criteria.*;
import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.variables.management.model.exportimport.instructions.ImportInstructionsFilterColumn;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.enums.filter.FilterCondition;
import org.qubership.integration.platform.variables.management.persistence.configs.entity.exportimport.instructions.ImportInstruction;
import org.qubership.integration.platform.variables.management.rest.v1.dto.instructions.ImportInstructionsFilterRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

@Component
public class ImportInstructionsFilterSpecificationBuilder {

    public Specification<ImportInstruction> buildSearch(List<ImportInstructionsFilterRequest> filters) {
        return buildFilter(filters, CriteriaBuilder::or);
    }

    public Specification<ImportInstruction> buildFilter(List<ImportInstructionsFilterRequest> filters) {
        return buildFilter(filters, CriteriaBuilder::and);
    }

    private Specification<ImportInstruction> buildFilter(
            List<ImportInstructionsFilterRequest> filters,
            BiFunction<CriteriaBuilder, Predicate[], Predicate> predicateAccumulator
    ) {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true);

            if (CollectionUtils.isEmpty(filters)) {
                return null;
            }

            Predicate[] predicates = filters.stream()
                    .filter(filter -> !ImportInstructionsFilterColumn.OVERRIDDEN_BY.equals(filter.getFeature()))
                    .map(filter -> buildPredicate(root, criteriaBuilder, filter))
                    .toArray(Predicate[]::new);

            return predicates.length > 1
                    ? predicateAccumulator.apply(criteriaBuilder, predicates)
                    : predicates[0];
        };
    }

    private Predicate buildPredicate(
            Root<ImportInstruction> root,
            CriteriaBuilder criteriaBuilder,
            ImportInstructionsFilterRequest filter
    ) {
        BiFunction<Expression<String>, String, Predicate> conditionPredicateBuilder =
                getPredicateBuilder(criteriaBuilder, filter.getCondition());
        String filterValue = filter.getValue();
        return switch (filter.getFeature()) {
            case ID -> conditionPredicateBuilder.apply(root.get("id"), filterValue);
            case INSTRUCTION_ACTION -> conditionPredicateBuilder.apply(root.get("action"), filterValue);
            case LABELS -> {
                Predicate predicate = conditionPredicateBuilder.apply(getJoin(root, "labels").get("name"), filterValue);
                boolean negativeLabelFilter =
                        filter.getCondition() == FilterCondition.IS_NOT
                                || filter.getCondition() == FilterCondition.DOES_NOT_CONTAIN;

                yield negativeLabelFilter
                        ? criteriaBuilder.or(predicate, criteriaBuilder.isNull(getJoin(root, "labels").get("name")))
                        : predicate;
            }
            case MODIFIED_WHEN -> conditionPredicateBuilder.apply(root.get("modifiedWhen"), filterValue);
            default -> throw new IllegalStateException("Unexpected feature value: " + filter.getFeature());
        };
    }

    private BiFunction<Expression<String>, String, Predicate> getPredicateBuilder(
            CriteriaBuilder criteriaBuilder,
            FilterCondition condition
    ) {
        return switch (condition) {
            case IS -> criteriaBuilder::equal;
            case IS_NOT ->
                    criteriaBuilder::notEqual;
            case CONTAINS -> (expression, value) -> criteriaBuilder.like(
                    criteriaBuilder.lower(expression.as(String.class)),
                    criteriaBuilder.lower(criteriaBuilder.literal("%" + value + '%'))
            );
            case DOES_NOT_CONTAIN -> (expression, value) -> criteriaBuilder.notLike(
                    criteriaBuilder.lower(expression.as(String.class)),
                    criteriaBuilder.lower(criteriaBuilder.literal("%" + value + '%'))
            );
            case STARTS_WITH -> (expression, value) -> criteriaBuilder.like(
                    criteriaBuilder.lower(expression.as(String.class)),
                    String.valueOf(value).toLowerCase() + "%");
            case ENDS_WITH -> (expression, value) -> criteriaBuilder.like(
                    criteriaBuilder.lower(expression.as(String.class)),
                    "%" + String.valueOf(value).toLowerCase());
            case IN -> (expression, value) -> expression.as(String.class).in(Arrays.asList(String.valueOf(value).split(",")));
            case NOT_IN -> (expression, value) -> criteriaBuilder.not(expression.as(String.class).in(Arrays.asList(String.valueOf(value).split(","))));
            case EMPTY -> (expression, value) -> criteriaBuilder.or(expression.isNull(), criteriaBuilder.equal(expression.as(String.class), ""));
            case NOT_EMPTY -> (expression, value) -> criteriaBuilder.notEqual(expression.as(String.class), "");
            case IS_AFTER -> (expression, value) -> criteriaBuilder.greaterThan(expression.as(Timestamp.class), new Timestamp(Long.parseLong(String.valueOf(value))));
            case IS_BEFORE -> (expression, value) -> criteriaBuilder.lessThan(expression.as(Timestamp.class), new Timestamp(Long.parseLong(String.valueOf(value))));
            case IS_WITHIN -> (expression, value) -> {
                String[] range = String.valueOf(value).split(",");
                return criteriaBuilder.between(expression.as(Timestamp.class), new Timestamp(Long.parseLong(String.valueOf(range[0]))), new Timestamp(Long.parseLong(String.valueOf(range[1]))));
            };
        };
    }

    private Join<ImportInstruction, ?> getJoin(Root<ImportInstruction> root, String attributeName) {
        return root.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attributeName))
                .findAny()
                .orElseGet(() -> root.join(attributeName, JoinType.LEFT));
    }
}
