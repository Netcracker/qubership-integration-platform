package org.qubership.integration.platform.runtime.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.operations.OperationRepository;
import org.qubership.integration.platform.runtime.catalog.service.helpers.ElementHelperService;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationServiceTest {

    private static final String OPERATION_ID = "operation-id";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OperationRepository operationRepository;

    @Mock
    private ElementHelperService elementHelperService;

    private OperationService operationService;
    private JsonNode schema;

    @BeforeEach
    void setUp() {
        operationService = new OperationService(operationRepository, objectMapper, elementHelperService);
        schema = objectMapper.createObjectNode().put("type", "object");
        Operation operation = new Operation();
        operation.setResponseSchemas(Map.of(
                "201", objectMapper.createObjectNode().set("application/json", schema)));
        when(operationRepository.findById(OPERATION_ID)).thenReturn(Optional.of(operation));
    }

    @Test
    @DisplayName("Returns the schema declared for the response code and content type")
    void returnsDeclaredSchema() {
        assertSame(schema, operationService.getResponseSchema(OPERATION_ID, "application/json", "201"));
    }

    @Test
    @DisplayName("Returns null for an undeclared response code, as the request form does for a miss")
    void returnsNullForUndeclaredResponseCode() {
        assertNull(operationService.getResponseSchema(OPERATION_ID, "application/json", "200"));
    }

    @Test
    @DisplayName("Returns null for an undeclared content type of a declared response code")
    void returnsNullForUndeclaredContentType() {
        assertNull(operationService.getResponseSchema(OPERATION_ID, "application/xml", "201"));
    }
}
