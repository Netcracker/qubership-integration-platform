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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ImportEnvironmentImpl;
import org.qubership.integration.platform.chain.impl.ImportOperationImpl;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.io.model.exportimport.system.EnvironmentDto;
import org.qubership.integration.platform.io.model.exportimport.system.OperationDto;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the export mapping in {@link SystemEntitySeam} field by field.
 *
 * <p>The seam copies a JPA {@code Environment} or {@code Operation} into its serialization value
 * type one field at a time. A dropped or renamed field would silently disappear from the export;
 * these tests populate every field with a distinct, non-default value and assert each one survives
 * the mapping. The inverse mapping is checked the same way for the fields it owns.
 */
class SystemEntitySeamFieldFidelityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return OBJECT_MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void toModelEnvironmentCopiesEveryField() {
        JsonNode properties = json("{\"timeout\":42}");
        Environment environment = Environment.builder()
                .id("env-id")
                .name("env-name")
                .description("env-description")
                .address("https://service.example.com")
                .sourceType(EnvironmentSourceType.MAAS_BY_CLASSIFIER)
                .labels(List.of(EnvironmentLabel.PRODUCTION, EnvironmentLabel.QA1))
                .maasInstanceId("maas-instance-id")
                .properties(properties)
                .build();

        EnvironmentDto dto = SystemEntitySeam.toModelEnvironment(environment);

        assertEquals("env-id", dto.getId());
        assertEquals("env-name", dto.getName());
        assertEquals("env-description", dto.getDescription());
        assertEquals("https://service.example.com", dto.getAddress());
        assertEquals(EnvironmentSourceType.MAAS_BY_CLASSIFIER, dto.getSourceType());
        assertEquals(List.of("PRODUCTION", "QA1"), dto.getLabels());
        assertEquals("maas-instance-id", dto.getMaasInstanceId());
        assertEquals(properties, dto.getProperties());
    }

    @Test
    void toPersistenceEnvironmentRestoresEveryField() {
        JsonNode properties = json("{\"retries\":3}");
        ImportEnvironmentImpl importEnvironment = new ImportEnvironmentImpl();
        importEnvironment.setId("env-id");
        importEnvironment.setName("env-name");
        importEnvironment.setDescription("env-description");
        importEnvironment.setAddress("https://service.example.com");
        importEnvironment.setSourceType(EnvironmentSourceType.MAAS_BY_CLASSIFIER);
        importEnvironment.setLabels(List.of("PRODUCTION", "QA1"));
        importEnvironment.setMaasInstanceId("maas-instance-id");
        importEnvironment.setProperties(properties);

        Environment environment = SystemEntitySeam.toPersistenceEnvironment(importEnvironment);

        assertEquals("env-id", environment.getId());
        assertEquals("env-name", environment.getName());
        assertEquals("env-description", environment.getDescription());
        assertEquals("https://service.example.com", environment.getAddress());
        assertEquals(EnvironmentSourceType.MAAS_BY_CLASSIFIER, environment.getSourceType());
        assertEquals(List.of(EnvironmentLabel.PRODUCTION, EnvironmentLabel.QA1), environment.getLabels());
        assertEquals("maas-instance-id", environment.getMaasInstanceId());
        assertEquals(properties, environment.getProperties());
    }

    @Test
    void toModelOperationCopiesEveryField() {
        JsonNode specification = json("{\"openapi\":\"3.0.0\"}");
        Map<String, JsonNode> requestSchema = Map.of("application/json", json("{\"type\":\"object\"}"));
        Map<String, JsonNode> responseSchemas = Map.of("200", json("{\"type\":\"string\"}"));
        Operation operation = Operation.builder()
                .id("op-id")
                .name("op-name")
                .description("op-description")
                .method("POST")
                .path("/orders")
                .specification(specification)
                .requestSchema(requestSchema)
                .responseSchemas(responseSchemas)
                .build();

        OperationDto dto = SystemEntitySeam.toModelOperation(operation);

        assertEquals("op-id", dto.getId());
        assertEquals("op-name", dto.getName());
        assertEquals("op-description", dto.getDescription());
        assertEquals("POST", dto.getMethod());
        assertEquals("/orders", dto.getPath());
        assertEquals(specification, dto.getSpecification());
        assertEquals(requestSchema, dto.getRequestSchema());
        assertEquals(responseSchemas, dto.getResponseSchemas());
    }

    @Test
    void toPersistenceOperationRestoresEveryField() {
        JsonNode specification = json("{\"openapi\":\"3.1.0\"}");
        Map<String, JsonNode> requestSchema = Map.of("application/json", json("{\"type\":\"array\"}"));
        Map<String, JsonNode> responseSchemas = Map.of("204", json("{\"type\":\"null\"}"));
        ImportOperationImpl importOperation = new ImportOperationImpl();
        importOperation.setId("op-id");
        importOperation.setName("op-name");
        importOperation.setDescription("op-description");
        importOperation.setMethod("PUT");
        importOperation.setPath("/orders/{id}");
        importOperation.setSpecification(specification);
        importOperation.setRequestSchema(requestSchema);
        importOperation.setResponseSchemas(responseSchemas);

        Operation operation = SystemEntitySeam.toPersistenceOperation(importOperation);

        assertEquals("op-id", operation.getId());
        assertEquals("op-name", operation.getName());
        assertEquals("op-description", operation.getDescription());
        assertEquals("PUT", operation.getMethod());
        assertEquals("/orders/{id}", operation.getPath());
        assertEquals(specification, operation.getSpecification());
        assertEquals(requestSchema, operation.getRequestSchema());
        assertEquals(responseSchemas, operation.getResponseSchemas());
    }
}
