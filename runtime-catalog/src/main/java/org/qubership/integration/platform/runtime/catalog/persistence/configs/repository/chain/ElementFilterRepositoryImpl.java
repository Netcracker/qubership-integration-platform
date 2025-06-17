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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElementFilterRequestDTO;

import java.util.*;


public class ElementFilterRepositoryImpl implements ElementFilterRepository {

    private static final String TYPE_ATTRIBUTE_NAME = "type";
    private static final String CHAIN_ATTRIBUTE_NAME = "chain";
    private static final String PROPERTIES_ATTRIBUTE = "properties";
    private static final String PRIVATE_ROUTE_TYPE = "Private";
    private static final String INTERNAL_ROUTE_TYPE = "Internal";
    private static final String EXTERNAL_ROUTE_TYPE = "External";
    private static final String IMPLEMENTED_SERVICE_TYPE = "IMPLEMENTED";
    private static final String NAME_PROPERTY = "name";
    private static final String ROLES_PROPERTY = "roles";
    private static final String SYSTEM_TYPE_PROPERTY = "systemType";
    private static final String CONTEXT_PATH_PROPERTY = "contextPath";
    private static final String PRIVATE_ROUTE_PROPERTY = "privateRoute";
    private static final String EXTERNAL_ROUTE_PROPERTY = "externalRoute";
    private static final String INTEGRATION_OPERATION_PATH_PROPERTY = "integrationOperationPath";
    private static final String INTEGRATION_SPECIFICATION_ID = "integrationSpecificationId";
    private static final Set<String> PROPERTIES_FILTER = Set.of(ROLES_PROPERTY, CONTEXT_PATH_PROPERTY, PRIVATE_ROUTE_PROPERTY, EXTERNAL_ROUTE_PROPERTY, INTEGRATION_OPERATION_PATH_PROPERTY, INTEGRATION_SPECIFICATION_ID);

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<ChainElement> findElementsByFilter(int offset, int limit, List<String> types, List<ChainElementFilterRequestDTO> filters, boolean isImplementedOnly) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<ChainElement> query = buildFilterQuery(builder, types, filters);
        List<ChainElement> resultList = entityManager.createQuery(query)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();

        if (isImplementedOnly) {
            resultList = filterImplementedServices(resultList);
        }
        filterElementProperties(resultList);
        return resultList;
    }

    private List<ChainElement> filterImplementedServices(List<ChainElement> elementList) {
        List<ChainElement> filteredChainList = new ArrayList<>();
        elementList.forEach(chainElement -> {
            if (IMPLEMENTED_SERVICE_TYPE.equals(chainElement.getProperty(SYSTEM_TYPE_PROPERTY))) {
                filteredChainList.add(chainElement);
            }
        });
        return filteredChainList;
    }

    private void filterElementProperties(List<ChainElement> elementList) {
        elementList.forEach(chainElement -> {
            Map<String, Object> filteredProperties = chainElement
                    .getProperties()
                    .entrySet()
                    .stream()
                    .filter((propertyEntry -> PROPERTIES_FILTER.contains(propertyEntry.getKey())))
                    .collect(HashMap::new, (m, v) -> m.put(v.getKey(), v.getValue()), HashMap::putAll);
            chainElement.setProperties(filteredProperties);
        });
    }

    private CriteriaQuery<ChainElement> buildFilterQuery(CriteriaBuilder builder, List<String> types, List<ChainElementFilterRequestDTO> filters) {
        CriteriaQuery<ChainElement> query = builder.createQuery(ChainElement.class);
        Root<ChainElement> chainElementRoot = query.from(ChainElement.class);
        List<Predicate> predicates = new LinkedList<>(); // combined with 'AND'

        removeRedundantFilters(filters);
        addPermanentFiltersToQuery(builder, types, chainElementRoot, predicates);
        addRequestFiltersToQuery(builder, filters, chainElementRoot, predicates);

        query.select(chainElementRoot);

        Predicate finalPredicate = builder.and(
                predicates.stream()
                        .filter(Objects::nonNull)
                        .toList()
                        .toArray(new Predicate[0])
        );

        return (!predicates.isEmpty()
                ? query.where(finalPredicate)
                : query);
    }

    private void addPermanentFiltersToQuery(CriteriaBuilder builder, List<String> types, Root<ChainElement> chainElementRoot, List<Predicate> predicates) {
        //Main filter by elements type
        Expression<Boolean> chainIdExpression = chainElementRoot.get(CHAIN_ATTRIBUTE_NAME);
        Predicate chainIdPredicate = builder.isNotNull(chainIdExpression);
        predicates.add(chainIdPredicate);

        //Main filter for element chain current state
        Expression<String> typeInExpression = chainElementRoot.get(TYPE_ATTRIBUTE_NAME);
        Predicate typeInPredicate = typeInExpression.in(types);
        predicates.add(typeInPredicate);
    }

    private void addRequestFiltersToQuery(CriteriaBuilder builder, List<ChainElementFilterRequestDTO> requestFilters, Root<ChainElement> chainElementRoot, List<Predicate> predicates) {
        for (ChainElementFilterRequestDTO filter : requestFilters) {
            switch (filter.getColumn()) {
                case ENDPOINT -> predicates.add(getEndpointPredicate(builder, filter, chainElementRoot));
                case TYPE -> predicates.add(getTypePredicate(builder, filter, chainElementRoot));
                case ROLES -> predicates.add(getRolesPredicate(builder, filter, chainElementRoot));
                case CHAIN -> predicates.add(getChainPredicate(builder, filter, chainElementRoot));
            }
        }
    }

    private Predicate getEndpointPredicate(CriteriaBuilder builder, ChainElementFilterRequestDTO filter, Root<ChainElement> chainElementRoot) {
        Predicate endpointPredicate = null;
        switch (filter.getCondition()) {
            case IS -> endpointPredicate = getIsEndpointPredicate(builder, chainElementRoot, filter.getValue());
            case IS_NOT ->
                    endpointPredicate = getIsEndpointPredicate(builder, chainElementRoot, filter.getValue()).not();
            case CONTAINS ->
                    endpointPredicate = getLikeEndpointPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase() + "%");
            case DOES_NOT_CONTAIN ->
                    endpointPredicate = getLikeEndpointPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase() + "%").not();
            case START_WITH ->
                    endpointPredicate = getLikeEndpointPredicate(builder, chainElementRoot, filter.getValue().toLowerCase() + "%");
            case ENDS_WITH ->
                    endpointPredicate = getLikeEndpointPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase());
            case EMPTY -> endpointPredicate = getEmptyEndpointPredicate(builder, chainElementRoot);
        }
        return endpointPredicate;
    }

    private Predicate getTypePredicate(CriteriaBuilder builder, ChainElementFilterRequestDTO filter, Root<ChainElement> chainElementRoot) {
        Predicate typePredicate = null;
        switch (filter.getCondition()) {
            case IN -> typePredicate = getInTypePredicate(builder, chainElementRoot, filter.getValue().split(","));
            case NOT_IN ->
                    typePredicate = getInTypePredicate(builder, chainElementRoot, filter.getValue().split(",")).not();
        }
        return typePredicate;
    }

    private Predicate getRolesPredicate(CriteriaBuilder builder, ChainElementFilterRequestDTO filter, Root<ChainElement> chainElementRoot) {
        Predicate rolesPredicate = null;
        switch (filter.getCondition()) {
            case IS -> rolesPredicate = getIsRolesPredicate(builder, chainElementRoot, filter.getValue());
            case IS_NOT -> rolesPredicate = getIsRolesPredicate(builder, chainElementRoot, filter.getValue()).not();
            case CONTAINS ->
                    rolesPredicate = getLikeRolesPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase() + "%");
            case DOES_NOT_CONTAIN ->
                    rolesPredicate = getLikeRolesPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase() + "%").not();
            case EMPTY -> rolesPredicate = getEmptyRolesPredicate(builder, chainElementRoot);
            case NOT_EMPTY -> rolesPredicate = getEmptyRolesPredicate(builder, chainElementRoot).not();
        }
        return rolesPredicate;
    }

    private Predicate getChainPredicate(CriteriaBuilder builder, ChainElementFilterRequestDTO filter, Root<ChainElement> chainElementRoot) {
        Predicate chainPredicate = null;
        switch (filter.getCondition()) {
            case CONTAINS ->
                    chainPredicate = getLikeChainPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase() + "%");
            case DOES_NOT_CONTAIN ->
                    chainPredicate = getLikeChainPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase() + "%").not();
            case START_WITH ->
                    chainPredicate = getLikeChainPredicate(builder, chainElementRoot, filter.getValue().toLowerCase() + "%");
            case ENDS_WITH ->
                    chainPredicate = getLikeChainPredicate(builder, chainElementRoot, "%" + filter.getValue().toLowerCase());
        }
        return chainPredicate;
    }

    private Predicate getIsEndpointPredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String filterValue) {
        //Based on type (regular | implemented service) endpoint can be stored in different properties
        Expression<String> endpointTypeExpression = getJsonPropertyStringExpression(builder, chainElementRoot, SYSTEM_TYPE_PROPERTY, false);
        Expression<String> defaultEndpointExpression = getJsonPropertyStringExpression(builder, chainElementRoot, CONTEXT_PATH_PROPERTY, true);
        Expression<String> typedEndpointExpression = getJsonPropertyStringExpression(builder, chainElementRoot, INTEGRATION_OPERATION_PATH_PROPERTY, true);

        Predicate defaultEndpointTypePredicate = builder.isNull(endpointTypeExpression);
        Predicate typedEndpointTypePredicate = builder.isNotNull(endpointTypeExpression);
        Predicate defaultEndpointPredicate = builder.equal(defaultEndpointExpression, filterValue);
        Predicate typedEndpointPredicate = builder.equal(typedEndpointExpression, filterValue);

        return builder.or(
                builder.and(defaultEndpointTypePredicate, defaultEndpointPredicate),
                builder.and(typedEndpointTypePredicate, typedEndpointPredicate)
        );
    }

    private Predicate getLikeEndpointPredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String filterValue) {
        //Based on type endpoint can be stored in different properties
        Expression<String> endpointTypeExpression = getJsonPropertyStringExpression(builder, chainElementRoot, SYSTEM_TYPE_PROPERTY, false);
        Expression<String> defaultEndpointExpression = getJsonPropertyStringExpression(builder, chainElementRoot, CONTEXT_PATH_PROPERTY, false);
        Expression<String> typedEndpointExpression = getJsonPropertyStringExpression(builder, chainElementRoot, INTEGRATION_OPERATION_PATH_PROPERTY, false);

        Predicate defaultEndpointTypePredicate = builder.isNull(endpointTypeExpression);
        Predicate typedEndpointTypePredicate = builder.isNotNull(endpointTypeExpression);
        Predicate defaultEndpointPredicate = builder.like(defaultEndpointExpression, filterValue);
        Predicate typedEndpointPredicate = builder.like(typedEndpointExpression, filterValue);

        return builder.or(
                builder.and(defaultEndpointTypePredicate, defaultEndpointPredicate),
                builder.and(typedEndpointTypePredicate, typedEndpointPredicate)
        );
    }

    private Predicate getEmptyEndpointPredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot) {
        //Based on type (regular | implemented service) endpoint can be stored in different properties
        Expression<String> endpointTypeExpression = getJsonPropertyStringExpression(builder, chainElementRoot, SYSTEM_TYPE_PROPERTY, false);
        Expression<String> defaultEndpointExpression = getJsonPropertyStringExpression(builder, chainElementRoot, CONTEXT_PATH_PROPERTY, true);
        Expression<String> typedEndpointExpression = getJsonPropertyStringExpression(builder, chainElementRoot, INTEGRATION_OPERATION_PATH_PROPERTY, true);

        Predicate defaultEndpointTypePredicate = builder.isNull(endpointTypeExpression);
        Predicate typedEndpointTypePredicate = builder.isNotNull(endpointTypeExpression);
        Predicate defaultEndpointPredicate = defaultEndpointExpression.isNull();
        Predicate typedEndpointPredicate = typedEndpointExpression.isNull();

        return builder.or(
                builder.and(defaultEndpointTypePredicate, defaultEndpointPredicate),
                builder.and(typedEndpointTypePredicate, typedEndpointPredicate)
        );
    }

    private Predicate getInTypePredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String[] filterValue) {
        Predicate typePredicate = null;
        for (String value : filterValue) {
            switch (value) {
                case INTERNAL_ROUTE_TYPE, EXTERNAL_ROUTE_TYPE -> {
                    Expression<Boolean> externalRouteExpression = getJsonPropertyBooleamExpression(builder, chainElementRoot, EXTERNAL_ROUTE_PROPERTY);
                    Boolean predicateFlag = value.equals(EXTERNAL_ROUTE_TYPE);
                    typePredicate = typePredicate == null
                            ? builder.equal(externalRouteExpression, predicateFlag)
                            : builder.or(typePredicate, builder.equal(externalRouteExpression, predicateFlag));
                }
                case PRIVATE_ROUTE_TYPE -> {
                    Expression<Boolean> privateRouteExpression = getJsonPropertyBooleamExpression(builder, chainElementRoot, PRIVATE_ROUTE_PROPERTY);
                    typePredicate = typePredicate == null
                            ? builder.equal(privateRouteExpression, true)
                            : builder.or(typePredicate, builder.equal(privateRouteExpression, true));

                }
            }
        }
        return typePredicate;
    }

    private Predicate getLikeRolesPredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String filterValue) {
        return builder.like(getJsonPropertyStringExpression(builder, chainElementRoot, ROLES_PROPERTY, true), filterValue);
    }

    private Predicate getIsRolesPredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String filterValue) {
        Expression<Object> jsonbFilterValue = builder.function("to_jsonb", Object.class, builder.literal(filterValue).as(String.class));

        Expression<Object> rolesJsonb = builder.function(
                "jsonb_extract_path",
                Object.class,
                chainElementRoot.get(PROPERTIES_ATTRIBUTE),
                builder.literal(ROLES_PROPERTY)
        );

        return builder.isTrue(
                builder.function("jsonb_contains", Boolean.class, rolesJsonb, jsonbFilterValue)
        );
    }

    private Predicate getEmptyRolesPredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot) {
        Expression<String> expression = getJsonPropertyStringExpression(builder, chainElementRoot, ROLES_PROPERTY, false);
        return builder.or(builder.isNull(expression), builder.like(expression, ""));
    }

    private Predicate getLikeChainPredicate(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String filterValue) {
        return builder.like(builder.lower(chainElementRoot.get(CHAIN_ATTRIBUTE_NAME).get(NAME_PROPERTY)), filterValue);
    }

    private Expression<String> getJsonPropertyStringExpression(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String propertyName, Boolean isCaseSensitive) {
        Expression<String> propertyStringExpression =
                builder.function(
                        "jsonb_extract_path_text",
                        String.class,
                        chainElementRoot.get(PROPERTIES_ATTRIBUTE),
                        builder.literal(propertyName)
                );
        return isCaseSensitive ? builder.lower(propertyStringExpression) : propertyStringExpression;
    }

    private Expression<Boolean> getJsonPropertyBooleamExpression(CriteriaBuilder builder, Root<ChainElement> chainElementRoot, String propertyName) {
        return builder.function(
                "BOOL",
                Boolean.class,
                builder.function(
                        "jsonb_extract_path",
                        Object.class,
                        chainElementRoot.get(PROPERTIES_ATTRIBUTE),
                        builder.literal(propertyName)
                )
        );
    }

    private void removeRedundantFilters(List<ChainElementFilterRequestDTO> filters) {
        List<ChainElementFilterRequestDTO> filtersToRemove = new ArrayList<>();

        for (ChainElementFilterRequestDTO filter : filters) {
            if (filter.getCondition().equals(FilterCondition.IS)) {
                ChainElementFilterRequestDTO oppositeFilter = new ChainElementFilterRequestDTO();
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
