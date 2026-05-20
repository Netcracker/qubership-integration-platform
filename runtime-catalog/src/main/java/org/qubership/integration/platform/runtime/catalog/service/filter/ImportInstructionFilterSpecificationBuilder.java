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

import jakarta.persistence.criteria.*;
import org.apache.commons.collections4.CollectionUtils;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.instructions.ImportInstruction;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.function.BiFunction;

@Component
public class ImportInstructionFilterSpecificationBuilder {

    private final FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory;

    public ImportInstructionFilterSpecificationBuilder(FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory) {
        this.filterConditionPredicateBuilderFactory = filterConditionPredicateBuilderFactory;
    }

    public Specification<ImportInstruction> buildSearch(Collection<FilterRequestDTO> filters) {
        return buildFilter(filters, CriteriaBuilder::or);
    }

    public Specification<ImportInstruction> buildFilter(Collection<FilterRequestDTO> filters) {
        return buildFilter(filters, CriteriaBuilder::and);
    }

    private Specification<ImportInstruction> buildFilter(
            Collection<FilterRequestDTO> filters,
            BiFunction<CriteriaBuilder, Predicate[], Predicate> predicateAccumulator
    ) {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true);

            if (CollectionUtils.isEmpty(filters)) {
                return null;
            }

            Predicate[] predicates = filters.stream()
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
            FilterRequestDTO filter
    ) {
        BiFunction<Expression<String>, String, Predicate> conditionPredicateBuilder =
                filterConditionPredicateBuilderFactory.getPredicateBuilder(criteriaBuilder, filter.getCondition());
        String filterValue = filter.getValue();
        return switch (filter.getFeature()) {
            case ID -> conditionPredicateBuilder.apply(root.get("id"), filterValue);
            case ENTITY_TYPE -> conditionPredicateBuilder.apply(root.get("entityType"), filterValue);
            case INSTRUCTION_ACTION -> conditionPredicateBuilder.apply(root.get("action"), filterValue);
            case OVERRIDDEN_BY -> conditionPredicateBuilder.apply(root.get("overriddenBy"), filterValue);
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

    private Join<ImportInstruction, ?> getJoin(Root<ImportInstruction> root, String attributeName) {
        return root.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attributeName))
                .findAny()
                .orElseGet(() -> root.join(attributeName, JoinType.LEFT));
    }
}
