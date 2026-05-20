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
import org.qubership.integration.platform.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.catalog.service.filter.FilterConditionPredicateBuilderFactory;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.function.BiFunction;

@Component
public class ChainAlertFilterSpecificationBuilder {
    private final FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory;

    @Autowired
    public ChainAlertFilterSpecificationBuilder(FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory) {
        this.filterConditionPredicateBuilderFactory = filterConditionPredicateBuilderFactory;
    }

    public Specification<ValidationChainAlert> buildSearch(Collection<FilterRequestDTO> filters) {
        return build(filters, CriteriaBuilder::or, true);
    }

    public Specification<ValidationChainAlert> buildFilter(Collection<FilterRequestDTO> filters) {
        return build(filters, CriteriaBuilder::and, false);
    }

    public Specification<ValidationChainAlert> build(
            Collection<FilterRequestDTO> filters,
            BiFunction<CriteriaBuilder, Predicate[], Predicate> predicateAccumulator,
            boolean searchMode
    ) {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true);

            if (!filters.isEmpty()) {
                Predicate[] predicates = filters.stream()
                        .map(filter -> buildPredicate(root, criteriaBuilder, filter))
                        .toArray(Predicate[]::new);

                return filters.size() > 1
                        ? predicateAccumulator.apply(criteriaBuilder, predicates)
                        : predicates[0];
            }

            return null;
        };
    }

    private Predicate buildPredicate(
            Root<ValidationChainAlert> root,
            CriteriaBuilder criteriaBuilder,
            FilterRequestDTO filter
    ) {
        var conditionPredicateBuilder = filterConditionPredicateBuilderFactory
                .<String>getPredicateBuilder(criteriaBuilder, filter.getCondition());
        String value = filter.getValue();
        return switch (filter.getFeature()) {
            case CHAIN_ID -> conditionPredicateBuilder.apply(root.get("chain").get("id"), value);
            case CHAIN_NAME -> conditionPredicateBuilder.apply(getJoin(root, "chain").get("name"), value);
            case ELEMENT_ID -> conditionPredicateBuilder.apply(root.get("element").get("id"), value);
            case ELEMENT_NAME -> conditionPredicateBuilder.apply(getJoin(root, "element").get("name"), value);
            case ELEMENT_TYPE -> conditionPredicateBuilder.apply(getJoin(root, "element").get("type"), value);
            default -> throw new IllegalStateException("Unexpected filter feature: " + filter.getFeature());
        };
    }

    private Join<ValidationChainAlert, ?> getJoin(Root<ValidationChainAlert> root, String attributeName) {
        return root.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attributeName))
                .findAny()
                .orElseGet(() -> root.join(attributeName, JoinType.LEFT));
    }
}
