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

package org.qubership.integration.platform.runtime.catalog.service.filter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.function.BiFunction;

@Component
public class FilterConditionPredicateBuilderFactory {
    public <T> BiFunction<Expression<T>, T, Predicate> getPredicateBuilder(
            CriteriaBuilder criteriaBuilder,
            FilterCondition condition
    ) {
        return switch (condition) {
            case IS -> criteriaBuilder::equal;
            case IS_NOT ->
                    criteriaBuilder::notEqual;
            case CONTAINS -> (expression, value) -> criteriaBuilder.like(
                    criteriaBuilder.lower(expression.as(String.class)),
                    criteriaBuilder.lower(criteriaBuilder.literal("%" + EscapeCharacter.DEFAULT.escape((String) value) + '%')), EscapeCharacter.DEFAULT.getEscapeCharacter()
            );
            case DOES_NOT_CONTAIN -> (expression, value) -> criteriaBuilder.notLike(
                    criteriaBuilder.lower(expression.as(String.class)),
                    criteriaBuilder.lower(criteriaBuilder.literal("%" + EscapeCharacter.DEFAULT.escape((String) value) + '%')), EscapeCharacter.DEFAULT.getEscapeCharacter()
            );
            case START_WITH -> (expression, value) -> criteriaBuilder.like(
                    criteriaBuilder.lower(expression.as(String.class)),
                    String.valueOf(EscapeCharacter.DEFAULT.escape((String) value)).toLowerCase() + "%", EscapeCharacter.DEFAULT.getEscapeCharacter());
            case ENDS_WITH -> (expression, value) -> criteriaBuilder.like(
                    criteriaBuilder.lower(expression.as(String.class)),
                    "%" + String.valueOf(EscapeCharacter.DEFAULT.escape((String) value)).toLowerCase(), EscapeCharacter.DEFAULT.getEscapeCharacter());
            case IN -> (expression, value) -> criteriaBuilder.lower(expression.as(String.class)).in(Arrays.asList(String.valueOf(value).split(",")));
            case NOT_IN -> (expression, value) -> criteriaBuilder.not(criteriaBuilder.lower(expression.as(String.class)).in(Arrays.asList(String.valueOf(value).split(","))));
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
}
