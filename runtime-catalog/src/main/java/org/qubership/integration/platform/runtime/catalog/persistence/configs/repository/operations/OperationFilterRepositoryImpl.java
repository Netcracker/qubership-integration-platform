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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OperationFilterRepositoryImpl implements OperationFilterRepository {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Operation> getOperations(String modelId, List<String> sortColumns) {
        CriteriaQuery<Operation> query = createGetOperationsByFilterQuery(modelId, Collections.emptyList(), sortColumns);
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<Operation> getOperations(String modelId, List<String> sortColumns, int offset, int count) {
        CriteriaQuery<Operation> query = createGetOperationsByFilterQuery(modelId, Collections.emptyList(), sortColumns);
        return entityManager.createQuery(query).setFirstResult(offset).setMaxResults(count).getResultList();
    }

    @Override
    public List<Operation> getOperationsByFilter(String modelId, List<String> filter, List<String> sortColumns) {
        CriteriaQuery<Operation> query = createGetOperationsByFilterQuery(modelId, filter, sortColumns);
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<Operation> getOperationsByFilter(String modelId, List<String> filter, List<String> sortColumns, int offset, int count) {
        CriteriaQuery<Operation> query = createGetOperationsByFilterQuery(modelId, filter, sortColumns);
        return entityManager.createQuery(query).setFirstResult(offset).setMaxResults(count).getResultList();
    }

    private CriteriaQuery<Operation> createGetOperationsByFilterQuery(String modelId, List<String> filter, List<String> sortColumns) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Operation> query = cb.createQuery(Operation.class);
        Root<Operation> operation = query.from(Operation.class);

        Join<Operation, SystemModel> joinSystemModel = operation.join("systemModel", JoinType.INNER);

        Expression<String> searchExpr =
                cb.concat(operation.get("method"), cb.concat(operation.get("name"), operation.get("path")));

        List<Predicate> predicates = new ArrayList<>();

        for (String value : filter) {
            predicates.add(cb.like(cb.lower(searchExpr), "%" + value.toLowerCase() + "%"));
        }

        if (modelId.isBlank()) {
            predicates.add(cb.equal(joinSystemModel.get("active"), true));
        } else {
            predicates.add(cb.equal(joinSystemModel.get("id"), modelId));
        }

        List<Order> orders = new ArrayList<>();
        for (String column : sortColumns) {
            orders.add(cb.asc(operation.get(column)));
        }

        return query
                .select(operation)
                .where(cb.and(predicates.toArray(new Predicate[0])))
                .orderBy(orders);
    }
}
