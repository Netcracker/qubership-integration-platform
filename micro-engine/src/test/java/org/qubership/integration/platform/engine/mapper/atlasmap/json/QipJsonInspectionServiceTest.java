package org.qubership.integration.platform.engine.mapper.atlasmap.json;

import io.atlasmap.json.inspect.JsonInspectionException;
import io.atlasmap.json.inspect.JsonSchemaInspector;
import io.atlasmap.json.v2.JsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipJsonInspectionServiceTest {

    @Mock
    private QipJsonInstanceInspector jsonInstanceInspector;

    private QipJsonInspectionService service;

    @BeforeEach
    void setUp() {
        service = new QipJsonInspectionService(jsonInstanceInspector);
    }

    @Test
    void shouldReturnEmptyJsonDocumentWhenInspectJsonDocumentWithNull() throws Exception {
        JsonDocument result = service.inspectJsonDocument(null);

        assertNotNull(result);
        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldReturnEmptyJsonDocumentWhenInspectJsonDocumentWithBlank() throws Exception {
        JsonDocument result = service.inspectJsonDocument("   ");

        assertNotNull(result);
        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldInspectJsonDocumentWhenObjectJsonProvided() throws Exception {
        JsonDocument expected = new JsonDocument();
        when(jsonInstanceInspector.inspect("{\"name\":\"Alex\"}")).thenReturn(expected);

        JsonDocument result = service.inspectJsonDocument("{\"name\":\"Alex\"}");

        assertSame(expected, result);
    }

    @Test
    void shouldInspectJsonDocumentWhenArrayJsonProvided() throws Exception {
        JsonDocument expected = new JsonDocument();
        when(jsonInstanceInspector.inspect("[{\"name\":\"Alex\"}]")).thenReturn(expected);

        JsonDocument result = service.inspectJsonDocument("[{\"name\":\"Alex\"}]");

        assertSame(expected, result);
    }

    @Test
    void shouldThrowJsonInspectionExceptionWhenJsonDocumentHasInvalidPrefix() {
        JsonInspectionException exception = assertThrows(
                JsonInspectionException.class,
                () -> service.inspectJsonDocument("name: Alex")
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "JSON data must begin with either '{' or '['",
                exception.getMessage()
        );
        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenDoInspectJsonDocumentWithBlank() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.doInspectJsonDocument("   ")
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "Source document cannot be null, empty or contain only whitespace.",
                exception.getMessage()
        );
        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldReturnEmptyJsonDocumentWhenInspectJsonSchemaWithBlank() throws Exception {
        JsonDocument result = service.inspectJsonSchema("   ");

        assertNotNull(result);
        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldInspectJsonSchemaWhenObjectSchemaProvided() throws Exception {
        JsonDocument expected = new JsonDocument();
        JsonSchemaInspector schemaInspector = org.mockito.Mockito.mock(JsonSchemaInspector.class);
        when(schemaInspector.inspect("{\"type\":\"object\"}")).thenReturn(expected);

        try (MockedStatic<JsonSchemaInspector> mockedStatic = mockStatic(JsonSchemaInspector.class)) {
            mockedStatic.when(JsonSchemaInspector::instance).thenReturn(schemaInspector);

            JsonDocument result = service.inspectJsonSchema("{\"type\":\"object\"}");

            assertSame(expected, result);
        }

        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldInspectJsonSchemaWhenArraySchemaProvided() throws Exception {
        JsonDocument expected = new JsonDocument();
        JsonSchemaInspector schemaInspector = org.mockito.Mockito.mock(JsonSchemaInspector.class);
        when(schemaInspector.inspect("[{\"type\":\"object\"}]")).thenReturn(expected);

        try (MockedStatic<JsonSchemaInspector> mockedStatic = mockStatic(JsonSchemaInspector.class)) {
            mockedStatic.when(JsonSchemaInspector::instance).thenReturn(schemaInspector);

            JsonDocument result = service.inspectJsonSchema("[{\"type\":\"object\"}]");

            assertSame(expected, result);
        }

        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldThrowJsonInspectionExceptionWhenJsonSchemaHasInvalidPrefix() {
        JsonInspectionException exception = assertThrows(
                JsonInspectionException.class,
                () -> service.inspectJsonSchema("type: object")
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "JSON schema must begin with either '{' or '['",
                exception.getMessage()
        );
        verifyNoInteractions(jsonInstanceInspector);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenDoInspectJsonSchemaWithBlank() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.doInspectJsonSchema("   ")
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "Schema cannot be null, empty or contain only whitespace.",
                exception.getMessage()
        );
        verifyNoInteractions(jsonInstanceInspector);
    }
}
