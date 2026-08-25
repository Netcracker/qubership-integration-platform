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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OperationService {
    private static final String OPERATION_WITH_ID_NOT_FOUND_MESSAGE = "Can't find operation with id ";

    /**
     * Attributes {@code OperationFilterRepositoryImpl} may sort by. Anything else reaches
     * {@code Root.get(String)}, which throws {@link IllegalArgumentException} while the Criteria query is built —
     * Spring does not translate that, so an unchecked column would surface as a 500.
     */
    private static final Set<String> SORTABLE_COLUMNS =
            Set.of("id", "name", "description", "method", "path", "createdWhen", "modifiedWhen");

    private final OperationRepository operationRepository;
    private final ObjectMapper objectMapper;
    private final ElementHelperService elementHelperService;
    private final OperationSchemaExtractor operationSchemaExtractor;

    @Autowired
    public OperationService(
            OperationRepository operationRepository,
            @Qualifier("primaryObjectMapper") ObjectMapper objectMapper,
            ElementHelperService elementHelperService,
            OperationSchemaExtractor operationSchemaExtractor
    ) {
        this.operationRepository = operationRepository;
        this.objectMapper = objectMapper;
        this.elementHelperService = elementHelperService;
        this.operationSchemaExtractor = operationSchemaExtractor;
    }

    public List<Operation> getOperationsByModel(
            String modelId,
            int offset,
            int count,
            String prefixFilter,
            List<String> sortColumns) {

        if (StringUtils.isBlank(modelId)) {
            throw new BadRequestException("Request parameter 'modelId' must not be blank");
        }
        return getOperations(modelId, offset, count, prefixFilter, validateSortColumns(sortColumns));
    }

    // Spring trims the tokens of a single comma-separated value but not those of a repeated parameter, so trim
    // before checking and pass on the same list that was checked.
    private List<String> validateSortColumns(List<String> sortColumns) {
        if (sortColumns == null) {
            return List.of();
        }
        List<String> trimmed = sortColumns.stream().map(String::trim).toList();
        List<String> unknown = trimmed.stream().filter(column -> !SORTABLE_COLUMNS.contains(column)).toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Unknown sort columns: " + String.join(", ", unknown));
        }
        return trimmed;
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

        enrichOperationsWithChains(operations);
        return operations;
    }

    public Operation getOperation(String operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new EntityNotFoundException(OPERATION_WITH_ID_NOT_FOUND_MESSAGE + operationId));
    }

    /**
     * Loads the operation and fills its transient {@code requestSchema} / {@code responseSchemas} from
     * the extractor, rebuilding them on demand from the raw specification source. Both the load and the
     * parse run inside one short read-only transaction; real specs are small, so holding the connection
     * during the parse is acceptable.
     */
    @Transactional(readOnly = true)
    public Operation getOperationWithSchemas(String operationId) {
        Operation operation = getOperation(operationId);
        SystemModel model = operation.getSystemModel();
        if (model == null) {
            return operation;
        }
        OperationProtocol protocol = protocolOf(model);
        List<SpecificationSource> sources = model.getSpecificationSources();
        if (protocol == null || sources == null || sources.isEmpty()) {
            return operation;
        }
        try {
            OperationSchemaExtractor.ExtractedSchemas schemas = operationSchemaExtractor.extract(
                    sources, protocol, operation.getPath(), operation.getMethod());
            if (schemas != null) {
                operation.setRequestSchema(schemas.requestSchema());
                operation.setResponseSchemas(schemas.responseSchemas());
            }
        } catch (SpecificationImportException | IllegalArgumentException e) {
            log.warn("Failed to extract schemas for operation {}: {}", operation.getId(), e.getMessage());
        }
        return operation;
    }

    @Transactional(readOnly = true)
    public Operation getOperationLight(String operationId) {
        Operation operation = getOperationWithSchemas(operationId);

        Map<String, JsonNode> requestSchema = operation.getRequestSchema();
        if (requestSchema != null) {
            Map<String, JsonNode> requestLight = requestSchema
                    .keySet()
                    .stream()
                    .map(key -> new ImmutablePair<String, JsonNode>(key, objectMapper.createObjectNode()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            operation.setRequestSchema(requestLight);
        }

        Map<String, JsonNode> responseSchemas = operation.getResponseSchemas();
        if (responseSchemas != null) {
            Map<String, JsonNode> responsesLight = responseSchemas
                    .keySet()
                    .stream()
                    .map(key -> {
                        var fields = responseSchemas.get(key).fields();
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
        }
        return operation;
    }

    public String getSpecification(String operationId) {
        Operation operation = getOperation(operationId);
        // A retained specification slice is absent for some protocols (e.g. WSDL): degrade to null
        // rather than NPE to a 500, matching the graceful null contract of the schema readers.
        JsonNode specification = operation.getSpecification();
        return specification == null ? null : specification.toString();
    }

    @Transactional(readOnly = true)
    public JsonNode getRequestSchema(String operationId, String contentType) {
        Operation operation = getOperationWithSchemas(operationId);
        Map<String, JsonNode> requestSchema = operation.getRequestSchema();
        return requestSchema == null ? null : requestSchema.get(contentType);
    }

    @Transactional(readOnly = true)
    public JsonNode getResponseSchema(String operationId, String contentType, String responseCode) {
        Operation operation = getOperationWithSchemas(operationId);
        Map<String, JsonNode> responseSchemas = operation.getResponseSchemas();
        if (responseSchemas == null) {
            return null;
        }
        JsonNode byCode = responseSchemas.get(responseCode);
        return byCode == null ? null : byCode.path(contentType);
    }

    private static OperationProtocol protocolOf(SystemModel model) {
        ApiGroup group = model.getApiGroup();
        if (group == null || group.getSystem() == null) {
            return null;
        }
        return group.getSystem().getProtocol();
    }

    // One lookup for the whole page instead of one per row.
    private void enrichOperationsWithChains(List<Operation> operations) {
        Set<String> operationIds = operations.stream()
                .map(Operation::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, List<Chain>> chainsByOperationId =
                elementHelperService.findChainsGroupedByOperationId(operationIds);
        operations.forEach(operation ->
                operation.setChains(chainsByOperationId.getOrDefault(operation.getId(), List.of())));
    }
}
