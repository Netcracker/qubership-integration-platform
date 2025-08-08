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
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions.*;


@Component
public class ChainFilterSpecificationBuilder {
    private static final Set<FilterFeature> ELEMENT_PARAMS_FEATURE_SET = Set.of(
            FilterFeature.EXCHANGE, FilterFeature.QUEUE, FilterFeature.TOPIC, FilterFeature.SERVICE_ID, FilterFeature.CLASSIFIER);
    private final FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory;

    @Autowired
    public ChainFilterSpecificationBuilder(FilterConditionPredicateBuilderFactory filterConditionPredicateBuilderFactory) {
        this.filterConditionPredicateBuilderFactory = filterConditionPredicateBuilderFactory;
    }

    public Specification<Chain> buildSearch(String searchString) {
        Collection<FilterRequestDTO> filters = buildFiltersFromSearchString(searchString);
        return build(filters, CriteriaBuilder::or, true);
    }

    public Specification<Chain> buildFilter(Collection<FilterRequestDTO> filters) {
        return build(filters, CriteriaBuilder::and, false);
    }

    private List<FilterRequestDTO> buildFiltersFromSearchString(String searchString) {
        return Stream.of(
                FilterFeature.ID,
                FilterFeature.NAME,
                FilterFeature.DESCRIPTION,
                FilterFeature.BUSINESS_DESCRIPTION,
                FilterFeature.ASSUMPTIONS,
                FilterFeature.OUT_OF_SCOPE,
                FilterFeature.PATH,
                FilterFeature.METHOD,
                FilterFeature.EXCHANGE,
                FilterFeature.TOPIC,
                FilterFeature.QUEUE,
                FilterFeature.LABELS,
                FilterFeature.CLASSIFIER
        ).map(feature -> FilterRequestDTO
                .builder()
                .feature(feature)
                .value(searchString)
                .condition(FilterCondition.CONTAINS)
                .build()
        ).toList();
    }

    public Specification<Chain> build(
            Collection<FilterRequestDTO> filters,
            BiFunction<CriteriaBuilder, Predicate[], Predicate> predicateAccumulator,
            boolean searchMode
    ) {
        Map<FilterFeature, List<FilterRequestDTO>> filtersMap =
                filters.stream().collect(Collectors.groupingBy(FilterRequestDTO::getFeature));

        List<FilterRequestDTO> commonFilters = new ArrayList<>();
        Map<FilterFeature, List<FilterRequestDTO>> orFilters = new HashMap<>();
        for (Map.Entry<FilterFeature, List<FilterRequestDTO>> entry : filtersMap.entrySet()) {
            List<FilterRequestDTO> filterRequestDTOs = entry.getValue();
            commonFilters.addAll(filterRequestDTOs.stream()
                    .filter(dto -> !(dto.getCondition() != FilterCondition.IS_NOT
                            && ELEMENT_PARAMS_FEATURE_SET.contains(dto.getFeature())))
                    .toList());
            // combine same filters with OR
            orFilters.put(entry.getKey(), filterRequestDTOs.stream()
                    .filter(dto -> dto.getCondition() != FilterCondition.IS_NOT
                            && ELEMENT_PARAMS_FEATURE_SET.contains(dto.getFeature()))
                    .toList());
        }

        return buildComplexSpec(commonFilters, orFilters, predicateAccumulator, searchMode);
    }

    private Specification<Chain> buildComplexSpec(
            List<FilterRequestDTO> commonFilters,
            Map<FilterFeature, List<FilterRequestDTO>> orFilters,
            BiFunction<CriteriaBuilder, Predicate[], Predicate> predicateAccumulator,
            boolean searchMode) {
        return (root, query, criteriaBuilder) -> {
            boolean isMainQuery = query.getResultType() == Chain.class;
            if (isMainQuery) {
                query.distinct(true);
            }

            List<Predicate> havingPredicates = new ArrayList<>();

            Predicate commonResult = null;
            if (!commonFilters.isEmpty()) {
                Predicate[] predicates = commonFilters.stream()
                        .map(filter -> buildPredicate(root, criteriaBuilder, filter))
                        .toArray(Predicate[]::new);

                commonResult = commonFilters.size() > 1
                        ? predicateAccumulator.apply(criteriaBuilder, predicates)
                        : predicates[0];
            }

            Predicate orResult = null;
            if (!orFilters.isEmpty()) {
                List<Predicate> orPredicates = new ArrayList<>();

                for (Map.Entry<FilterFeature, List<FilterRequestDTO>> entry : orFilters.entrySet()) {
                    FilterFeature feature = entry.getKey();
                    List<FilterRequestDTO> filterRequestDTOS = entry.getValue();

                    filterRequestDTOS.stream()
                            .map(dto -> buildPredicate(root, criteriaBuilder, dto))
                            .forEach(orPredicates::add);

                    if (isMainQuery && !filterRequestDTOS.isEmpty()) {
                        Expression<? extends Long> expression = switch (feature) {
                            case TOPIC -> criteriaBuilder.sum(
                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_PATH_TOPIC)),
                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, TOPICS)));
                            case QUEUE -> criteriaBuilder.sum(
                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, QUEUES)),
                                    criteriaBuilder.countDistinct(getChainElementSubPropertyExpression(root, criteriaBuilder, OPERATION_ASYNC_PROPERTIES, QUEUES)));
                            case EXCHANGE -> criteriaBuilder.sum(
                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_PATH_EXCHANGE)),
                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, EXCHANGE)));
                            case SERVICE_ID ->
                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, SYSTEM_ID));
                            case CLASSIFIER ->
                                    criteriaBuilder.sum(
                                            criteriaBuilder.sum(
                                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, MAAS_TOPICS_CLASSIFIER_NAME_PROP)),
                                                    criteriaBuilder.countDistinct(getChainElementPropertyExpression(root, criteriaBuilder, MAAS_VHOST_CLASSIFIER_NAME_PROP))
                                            ),
                                            criteriaBuilder.countDistinct(getChainElementSubPropertyExpression(root, criteriaBuilder, OPERATION_ASYNC_PROPERTIES, MAAS_CLASSIFIER_NAME_PROP))
                                    );
                            default -> null;
                        };

                        if (expression != null) {
                            havingPredicates.add(criteriaBuilder.greaterThanOrEqualTo(expression, Long.valueOf(filterRequestDTOS.size())));
                        }
                    }
                }

                if (!havingPredicates.isEmpty() && !searchMode) {
                    Path<Integer> chainIdPath = root.get("id");
                    query.groupBy(chainIdPath);

                    query.having(havingPredicates.size() > 1
                            ? criteriaBuilder.and(havingPredicates.toArray(Predicate[]::new))
                            : havingPredicates.get(0));
                }

                if (!orPredicates.isEmpty()) {
                    orResult = orPredicates.size() > 1
                            ? criteriaBuilder.or(orPredicates.toArray(Predicate[]::new))
                            : orPredicates.get(0);
                }
            }

            if (commonResult == null && orResult != null) {
                return orResult;
            }

            if (commonResult != null && orResult == null) {
                return commonResult;
            }

            if (commonResult != null && orResult != null) {
                return predicateAccumulator.apply(criteriaBuilder, new Predicate[]{ commonResult, orResult });
            }

            return null;
        };
    }

    private Predicate buildPredicate(
            Root<Chain> root,
            CriteriaBuilder criteriaBuilder,
            FilterRequestDTO filter
    ) {
        boolean isNegativeElementFilter =
                (filter.getCondition() == FilterCondition.IS_NOT || filter.getCondition() == FilterCondition.NOT_IN)
                        && ELEMENT_PARAMS_FEATURE_SET.contains(filter.getFeature());
        var conditionPredicateBuilder = filterConditionPredicateBuilderFactory
                .<String>getPredicateBuilder(criteriaBuilder, filter.getCondition());
        String value = filter.getValue();
        return switch (filter.getFeature()) {
            case ID -> conditionPredicateBuilder.apply(root.get("id"), value);
            case NAME -> conditionPredicateBuilder.apply(root.get("name"), value);
            case DESCRIPTION -> conditionPredicateBuilder.apply(root.get("description"), value);
            case BUSINESS_DESCRIPTION -> conditionPredicateBuilder.apply(root.get("businessDescription"), value);
            case ASSUMPTIONS -> conditionPredicateBuilder.apply(root.get("assumptions"), value);
            case OUT_OF_SCOPE -> conditionPredicateBuilder.apply(root.get("outOfScope"), value);
            case ENGINES -> conditionPredicateBuilder.apply(
                    getDeploymentPropertyExpression(root, "domain"), value);
            case LOGGING, STATUS, ELEMENT -> criteriaBuilder.conjunction();
            case PATH -> criteriaBuilder.or(
                    criteriaBuilder.and(
                            elementTypeIs(root, criteriaBuilder, HTTP_TRIGGER_COMPONENT),
                            criteriaBuilder.or(
                                    conditionPredicateBuilder.apply(
                                            getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_PATH), value),
                                    conditionPredicateBuilder.apply(
                                            getChainElementPropertyExpression(root, criteriaBuilder, CONTEXT_PATH), value)
                            )
                    ),
                    criteriaBuilder.and(
                            elementTypeIs(root, criteriaBuilder, SERVICE_CALL_COMPONENT),
                            criteriaBuilder.equal(
                                    getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_PROTOCOL_TYPE_PROP),
                                    OPERATION_PROTOCOL_TYPE_HTTP
                            ),
                            conditionPredicateBuilder.apply(
                                    getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_PATH), value)
                    ),
                    criteriaBuilder.and(
                            elementTypeIs(root, criteriaBuilder, HTTP_SENDER_COMPONENT),
                            conditionPredicateBuilder.apply(
                                    criteriaBuilder.function(
                                            "regexp_replace",
                                            String.class,
                                            getChainElementPropertyExpression(root, criteriaBuilder, URI),
                                            criteriaBuilder.literal("^https?://[^:/]+(:\\d{1,5})?"),
                                            criteriaBuilder.literal("")
                                    ),
                                    value
                            )
                    )
            );
            case METHOD -> criteriaBuilder.or(
                    criteriaBuilder.and(
                            elementTypeIs(root, criteriaBuilder, HTTP_SENDER_COMPONENT),
                            conditionPredicateBuilder.apply(
                                    getChainElementPropertyExpression(root, criteriaBuilder, HTTP_METHOD), value)
                    ),
                    criteriaBuilder.and(
                            elementTypeIs(root, criteriaBuilder, SERVICE_CALL_COMPONENT),
                            criteriaBuilder.equal(
                                    getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_PROTOCOL_TYPE_PROP),
                                    OPERATION_PROTOCOL_TYPE_HTTP
                            ),
                            conditionPredicateBuilder.apply(
                                    getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_METHOD), value)
                    ),
                    criteriaBuilder.and(
                            elementTypeIs(root, criteriaBuilder, HTTP_TRIGGER_COMPONENT),
                            buildPredicateForHttpTriggerMethod(root, criteriaBuilder, filter.getCondition(), value)
                    )
            );
            case LABELS -> {
                Predicate predicate = conditionPredicateBuilder.apply(getJoin(root, "labels").get("name"), value);
                boolean negativeLabelFilter =
                        filter.getCondition() == FilterCondition.IS_NOT
                                || filter.getCondition() == FilterCondition.DOES_NOT_CONTAIN;

                yield negativeLabelFilter
                        ? criteriaBuilder.or(predicate, criteriaBuilder.isNull(getJoin(root, "labels").get("name")))
                        : predicate;
            }
            case TOPIC -> {
                Function<Root<ChainElement>, Predicate> basePredicateFunc = (elRoot) -> criteriaBuilder.or(
                        buildAsyncOperationPredicate(root, elRoot, criteriaBuilder, conditionPredicateBuilder,
                                OPERATION_PROTOCOL_TYPE_KAFKA, OPERATION_PATH_TOPIC, value),
                        isNegativeElementFilter
                                ? criteriaBuilder.equal(getElementPropertyExpression(elRoot.get("properties"), criteriaBuilder, TOPICS), value)
                                : conditionPredicateBuilder.apply(getChainElementPropertyExpression(root, criteriaBuilder, TOPICS), value));

                yield isNegativeElementFilter
                        ? getNegativeFilterPredicate(root, criteriaBuilder, basePredicateFunc)
                        : basePredicateFunc.apply(null);
            }
            case QUEUE -> {
                Function<Root<ChainElement>, Predicate> basePredicateFunc = (elRoot) -> criteriaBuilder.or(
                        buildAsyncOperationPredicate(root, elRoot, criteriaBuilder, conditionPredicateBuilder,
                                OPERATION_PROTOCOL_TYPE_AMQP, QUEUES, value),
                        isNegativeElementFilter
                                ? criteriaBuilder.equal(
                                        getElementPropertyExpression(elRoot.get("properties"), criteriaBuilder, QUEUES), value)
                                : conditionPredicateBuilder.apply(getChainElementPropertyExpression(root, criteriaBuilder, QUEUES), value));

                yield isNegativeElementFilter
                        ? getNegativeFilterPredicate(root, criteriaBuilder, basePredicateFunc)
                        : basePredicateFunc.apply(null);
            }
            case EXCHANGE -> {
                Function<Root<ChainElement>, Predicate> basePredicateFunc = (elRoot) -> criteriaBuilder.or(
                        buildAsyncOperationPredicate(root, elRoot, criteriaBuilder, conditionPredicateBuilder,
                                OPERATION_PROTOCOL_TYPE_AMQP, OPERATION_PATH_EXCHANGE, value),
                        isNegativeElementFilter
                                ? criteriaBuilder.equal(getElementPropertyExpression(elRoot.get("properties"), criteriaBuilder, EXCHANGE), value)
                                : conditionPredicateBuilder.apply(getChainElementPropertyExpression(root, criteriaBuilder, EXCHANGE), value));

                yield isNegativeElementFilter
                        ? getNegativeFilterPredicate(root, criteriaBuilder, basePredicateFunc)
                        : basePredicateFunc.apply(null);
            }
            case SERVICE_ID -> conditionPredicateBuilder.apply(getChainElementPropertyExpression(root, criteriaBuilder, SYSTEM_ID), value);
            case CLASSIFIER -> {
                Function<Root<ChainElement>, Predicate> basePredicateFunc = (elRoot) -> criteriaBuilder.or(
                        buildAsyncOperationPredicate(root, elRoot, criteriaBuilder, conditionPredicateBuilder,
                                OPERATION_PROTOCOL_TYPE_KAFKA, MAAS_CLASSIFIER_NAME_PROP, value),
                        buildAsyncOperationPredicate(root, elRoot, criteriaBuilder, conditionPredicateBuilder,
                                OPERATION_PROTOCOL_TYPE_AMQP, MAAS_CLASSIFIER_NAME_PROP, value),
                        isNegativeElementFilter
                                ? criteriaBuilder.or(
                                        criteriaBuilder.equal(getChainElementPropertyExpression(root, criteriaBuilder, MAAS_TOPICS_CLASSIFIER_NAME_PROP), value),
                                        criteriaBuilder.equal(getChainElementPropertyExpression(root, criteriaBuilder, MAAS_VHOST_CLASSIFIER_NAME_PROP), value))
                                : criteriaBuilder.or(
                                        conditionPredicateBuilder.apply(getChainElementPropertyExpression(root, criteriaBuilder, MAAS_TOPICS_CLASSIFIER_NAME_PROP), value),
                                        conditionPredicateBuilder.apply(getChainElementPropertyExpression(root, criteriaBuilder, MAAS_VHOST_CLASSIFIER_NAME_PROP), value)));

                yield isNegativeElementFilter
                        ? getNegativeFilterPredicate(root, criteriaBuilder, basePredicateFunc)
                        : basePredicateFunc.apply(null);
            }
            default -> throw new IllegalStateException("Unexpected filter feature: " + filter.getFeature());
        };
    }

    @NotNull
    private Predicate getNegativeFilterPredicate(
            Root<Chain> root,
            CriteriaBuilder criteriaBuilder,
            Function<Root<ChainElement>, Predicate> basePredicateFunc
    ) {
        Subquery<String> negativeSubquery = criteriaBuilder.createQuery().subquery(String.class);
        Root<ChainElement> elRoot = negativeSubquery.from(ChainElement.class);
        negativeSubquery
                .select(elRoot.get("chain").get("id"))
                .where(criteriaBuilder.and(
                        basePredicateFunc.apply(elRoot),
                        criteriaBuilder.isNotNull(elRoot.get("chain").get("id"))));
        return criteriaBuilder.not(root.get("id").in(negativeSubquery));
    }

    private Predicate buildPredicateForHttpTriggerMethod(
            Root<Chain> root,
            CriteriaBuilder criteriaBuilder,
            FilterCondition condition,
            String value
    ) {
        Expression<String> methodPropertyExpression =
                getChainElementPropertyExpression(root, criteriaBuilder, HTTP_METHOD_RESTRICT);
        Predicate likeValue = criteriaBuilder.like(
                criteriaBuilder.lower(methodPropertyExpression),
                criteriaBuilder.lower(criteriaBuilder.literal('%' + value + '%'))
        );
        return switch (condition) {
            case IS, CONTAINS, IN -> likeValue;
            case DOES_NOT_CONTAIN, IS_NOT, NOT_IN -> criteriaBuilder.not(likeValue);
            case STARTS_WITH -> criteriaBuilder.like(
                    criteriaBuilder.lower(methodPropertyExpression),
                    criteriaBuilder.lower(criteriaBuilder.literal(value + '%')));
            case ENDS_WITH -> criteriaBuilder.like(
                    criteriaBuilder.lower(methodPropertyExpression),
                    criteriaBuilder.lower(criteriaBuilder.literal('%' + value)));
            case EMPTY -> criteriaBuilder.or(
                    criteriaBuilder.literal(value).isNull(),
                    criteriaBuilder.equal(criteriaBuilder.literal(value), ""));
            case NOT_EMPTY -> criteriaBuilder.or(
                    criteriaBuilder.literal(value).isNotNull(),
                    criteriaBuilder.notEqual(criteriaBuilder.literal(value), ""));
            default -> throw new IllegalStateException("Unexpected condition value: " + condition);
        };
    }

    private Predicate buildAsyncOperationPredicate(
            Root<Chain> root,
            Root<ChainElement> elRoot,
            CriteriaBuilder criteriaBuilder,
            BiFunction<Expression<String>, String, Predicate> conditionPredicateBuilder,
            String protocol,
            String operationProperty,
            String value
    ) {
        return criteriaBuilder.and(
                criteriaBuilder.or(
                        elementTypeIs(root, criteriaBuilder, SERVICE_CALL_COMPONENT),
                        elementTypeIs(root, criteriaBuilder, ASYNC_API_TRIGGER_COMPONENT)
                ),
                criteriaBuilder.equal(
                        getChainElementPropertyExpression(root, criteriaBuilder, OPERATION_PROTOCOL_TYPE_PROP),
                        protocol
                ),
                elRoot == null
                        ? criteriaBuilder.or(
                            conditionPredicateBuilder.apply(getChainElementSubPropertyExpression(root, criteriaBuilder, OPERATION_ASYNC_PROPERTIES, operationProperty), value),
                            conditionPredicateBuilder.apply(getChainElementPropertyExpression(root, criteriaBuilder, operationProperty), value))
                        : criteriaBuilder.or(
                            criteriaBuilder.equal(getChainElementSubPropertyExpression(root, criteriaBuilder, OPERATION_ASYNC_PROPERTIES, operationProperty), value),
                            criteriaBuilder.equal(getChainElementPropertyExpression(root, criteriaBuilder, operationProperty), value))
        );
    }

    private Predicate elementTypeIs(Root<Chain> root, CriteriaBuilder criteriaBuilder, String typeName) {
        Join<Chain, ?> elementJoin = getJoin(root, "elements");
        return criteriaBuilder.equal(elementJoin.get("type"), typeName);
    }

    private Expression<String> getChainElementPropertyExpression(
            Root<Chain> root,
            CriteriaBuilder criteriaBuilder,
            String propertyName
    ) {
        Join<Chain, ?> elementJoin = getJoin(root, "elements");
        return getElementPropertyExpression(elementJoin.get("properties"), criteriaBuilder, propertyName);
    }

    private Expression<String> getChainElementSubPropertyExpression(
            Root<Chain> root,
            CriteriaBuilder criteriaBuilder,
            String propertyName,
            String subPropertyName
    ) {
        Join<Chain, ?> elementJoin = getJoin(root, "elements");
        return getElementPropertySubParameterExpression(elementJoin.get("properties"), criteriaBuilder, propertyName, subPropertyName);
    }

    private Expression<String> getElementPropertyExpression(
            Expression<?> props,
            CriteriaBuilder criteriaBuilder,
            String propertyName
    ) {
        return criteriaBuilder.function(
                "jsonb_extract_path_text",
                String.class,
                props,
                criteriaBuilder.literal(propertyName)
        );
    }

    private Expression<String> getElementPropertySubParameterExpression(
            Expression<?> props,
            CriteriaBuilder criteriaBuilder,
            String propertyName,
            String subPropertyName
    ) {
        return criteriaBuilder.function(
                "jsonb_extract_path_text",
                String.class,
                props,
                criteriaBuilder.literal(propertyName),
                criteriaBuilder.literal(subPropertyName)
        );
    }

    private <T> Expression<T> getDeploymentPropertyExpression(
            Root<Chain> root,
            String propertyName
    ) {
        Join<Chain, ?> deploymentJoin = getJoin(root, "deployments");
        return deploymentJoin.get(propertyName);
    }

    private Join<Chain, ?> getJoin(Root<Chain> root, String attributeName) {
        return root.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(attributeName))
                .findAny()
                .orElseGet(() -> root.join(attributeName, JoinType.LEFT));
    }
}
