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
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Component
public class SystemFilterSpecificationBuilder {

    private final FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory;

    @Autowired
    public SystemFilterSpecificationBuilder(FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory) {
        this.filterConditionPredicateBuilderFactory = filterConditionPredicateBuilderFactory;
    }

    public Specification<IntegrationSystem> buildFilter(Collection<FilterRequestDTO> filters) {
        return build(filters, CriteriaBuilder::and);
    }

    public Specification<IntegrationSystem> build(
            Collection<FilterRequestDTO> filters,
            BiFunction<CriteriaBuilder, Predicate[], Predicate> predicateAccumulator
    ) {
        return (root, query, criteriaBuilder) -> {
            query.distinct(true);

            Predicate commonResult = null;
            if (!filters.isEmpty()) {
                Predicate[] predicates = filters.stream()
                        .map(filter -> buildPredicate(root, criteriaBuilder, filter))
                        .toArray(Predicate[]::new);

                commonResult = filters.size() > 1
                        ? predicateAccumulator.apply(criteriaBuilder, predicates)
                        : predicates[0];
            }

            return commonResult;
        };
    }

    private Predicate buildPredicate(
            Root<IntegrationSystem> root,
            CriteriaBuilder criteriaBuilder,
            FilterRequestDTO filter
    ) {
        var conditionPredicateBuilder = filterConditionPredicateBuilderFactory
                .<String>getPredicateBuilder(criteriaBuilder, filter.getCondition());
        String value = filter.getValue();
        return switch (filter.getFeature()) {
            case ID -> conditionPredicateBuilder.apply(root.get("id"), value);
            case NAME -> conditionPredicateBuilder.apply(root.get("name"), value);
            case SPECIFICATION_GROUP -> conditionPredicateBuilder.apply(root.join("specificationGroups").get("name"), value);
            case SPECIFICATION_VERSION -> conditionPredicateBuilder.apply(root.join("specificationGroups")
                                                                            .join("systemModels")
                                                                            .get("version"), value);
            case URL -> conditionPredicateBuilder.apply(root.join("specificationGroups")
                    .join("systemModels")
                    .join("operations")
                    .get("path"), value);
            case PROTOCOL -> conditionPredicateBuilder.apply(root.get("protocol"), convertProtocols(value));
            case CREATED -> conditionPredicateBuilder.apply(root.get("createdWhen"), value);
            case LABELS -> {
                Predicate predicate = conditionPredicateBuilder.apply(getJoin(root, "labels").get("name"), value);
                boolean negativeLabelFilter =
                        filter.getCondition() == FilterCondition.IS_NOT
                                || filter.getCondition() == FilterCondition.DOES_NOT_CONTAIN;

                yield negativeLabelFilter
                        ? criteriaBuilder.or(predicate, criteriaBuilder.isNull(getJoin(root, "labels").get("name")))
                        : predicate;
            }
            default -> throw new IllegalStateException("Unexpected feature value: " + filter.getFeature());
        };
    }

    private String convertProtocols(String value) {
        return Arrays.stream(String.valueOf(value).split(","))
                .map(protocol -> "," + OperationProtocol.fromValue(protocol).getValue())
                .collect(Collectors.joining()).replaceFirst(",", "");
    }

    private Join<IntegrationSystem, ?> getJoin(Root<IntegrationSystem> root, String attributeName) {
        return root.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attributeName))
                .findAny()
                .orElseGet(() -> root.join(attributeName, JoinType.LEFT));
    }
}
