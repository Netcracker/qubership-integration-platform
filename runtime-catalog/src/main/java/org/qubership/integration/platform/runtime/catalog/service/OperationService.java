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

package org.qubership.integration.platform.runtime.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OperationService {
    private static final String OPERATION_WITH_ID_NOT_FOUND_MESSAGE = "Can't find operation with id ";

    private final OperationRepository operationRepository;
    private final ObjectMapper objectMapper;
    private final ElementHelperService elementHelperService;

    @Autowired
    public OperationService(
            OperationRepository operationRepository,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            ElementHelperService elementHelperService
    ) {
        this.operationRepository = operationRepository;
        this.objectMapper = objectMapper;
        this.elementHelperService = elementHelperService;
    }

    public List<Operation> getOperationsByModel(
            String modelId,
            int offset,
            int count,
            String prefixFilter,
            List<String> sortColumns) {

        return getOperations(modelId, offset, count, prefixFilter, sortColumns);
    }

    private List<Operation> getOperations(
            String modelId,
            int offset,
            int limit,
            String prefixFilter,
            List<String> sortColumns) {

        if (offset < 0 || limit < 0) { // invalid indexes
            return Collections.emptyList();
        }

        prefixFilter = prefixFilter.stripLeading().stripTrailing();

        boolean filterPresent = !prefixFilter.isEmpty();
        List<String> query = Arrays.asList(prefixFilter.split("\\s+"));

        List<Operation> operations;
        if (limit > 0) { // partial operations loading
            operations = filterPresent
                    ? operationRepository.getOperationsByFilter(modelId, query, sortColumns, offset, limit)
                    : operationRepository.getOperations(modelId, sortColumns, offset, limit);
        } else {
            operations = filterPresent
                    ? operationRepository.getOperationsByFilter(modelId, query, sortColumns)
                    : operationRepository.getOperations(modelId, sortColumns);
        }

        return operations.stream()
                .peek(this::enrichOperationWithChains)
                .collect(Collectors.toList());
    }

    public Operation getOperation(String operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new EntityNotFoundException(OPERATION_WITH_ID_NOT_FOUND_MESSAGE + operationId));
    }

    public Operation getOperationLight(String operationId) {
        Operation operation = getOperation(operationId);
        Map<String, JsonNode> requestLight = operation
                .getRequestSchema()
                .keySet()
                .stream()
                .map(key -> new ImmutablePair<String, JsonNode>(key, objectMapper.createObjectNode()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        operation.setRequestSchema(requestLight);

        Map<String, JsonNode> responsesLight = operation
                .getResponseSchemas()
                .keySet()
                .stream()
                .map(key -> {
                    var fields = operation.getResponseSchemas().get(key).fields();
                    var fieldsMap = new HashMap<>();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        fieldsMap.put(field.getKey(), objectMapper.createObjectNode());
                    }
                    JsonNode subNode = objectMapper.convertValue(fieldsMap, JsonNode.class);
                    return new ImmutablePair<>(key, subNode);
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        operation.setResponseSchemas(responsesLight);
        return operation;
    }

    public String getSpecification(String operationId) {
        Operation operation = getOperation(operationId);
        return operation.getSpecification().toString();
    }

    public JsonNode getRequestSchema(String operationId, String contentType) {
        Operation operation = getOperation(operationId);
        return operation.getRequestSchema().get(contentType);
    }

    public JsonNode getResponseSchema(String operationId, String contentType, String responseCode) {
        Operation operation = getOperation(operationId);
        System.out.print(operation.getResponseSchemas().get(responseCode).path(contentType));
        return operation.getResponseSchemas().get(responseCode).path(contentType);
    }

    private void enrichOperationWithChains(Operation operation) {
        List<Chain> chains = elementHelperService.findBySystemAndOperationId(null, operation.getId());
        operation.setChains(chains);
    }
}
