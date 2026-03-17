package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidationSchemaHelperTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final ValidationSchemaHelper helper = spy(new ValidationSchemaHelper(OBJECT_MAPPER));

    @Test
    void applyReturnsNullForNullValue() {
        Options options = mock(Options.class);
        doReturn(null).when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertNull(result);
    }

    @Test
    void applyReturnsNullForEmptyList() {
        Options options = mock(Options.class);
        doReturn(Collections.emptyList()).when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertNull(result);
    }

    @Test
    void applyReturnsNullWhenNoSchemasInList() {
        Options options = mock(Options.class);
        List<Map<String, Object>> list = List.of(
                Map.of("name", "op1"),
                Map.of("name", "op2")
        );
        doReturn(list).when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertNull(result);
    }

    @Test
    void applyReturnsNullWhenSchemasAreEmpty() {
        Options options = mock(Options.class);
        List<Map<String, Object>> list = List.of(
                Map.of("schema", "")
        );
        doReturn(list).when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertNull(result);
    }

    @Test
    void applyReturnsSingleSchemaAsIs() {
        Options options = mock(Options.class);
        String schema = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";
        List<Map<String, Object>> list = List.of(
                Map.of("schema", schema)
        );
        doReturn(list).when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertEquals(schema, result);
    }

    @Test
    void applyCombinesMultipleSchemasWithOneOf() throws Exception {
        Options options = mock(Options.class);
        String schema1 = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
        String schema2 = "{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"integer\"}}}";
        List<Map<String, Object>> list = List.of(
                Map.of("schema", schema1),
                Map.of("schema", schema2)
        );
        doReturn(list).when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertNotNull(result);
        JsonNode combined = OBJECT_MAPPER.readTree(result.toString());
        assertEquals("http://json-schema.org/draft-07/schema#", combined.get("$schema").asText());
        assertTrue(combined.has("oneOf"));
        assertEquals(2, combined.get("oneOf").size());
        assertEquals("string", combined.get("oneOf").get(0).at("/properties/a/type").asText());
        assertEquals("integer", combined.get("oneOf").get(1).at("/properties/b/type").asText());
    }

    @Test
    void applyFiltersOutEmptySchemasAndNonStringEntries() {
        Options options = mock(Options.class);
        String validSchema = "{\"type\":\"object\"}";
        List<Map<String, Object>> list = List.of(
                Map.of("schema", ""),
                Map.of("schema", validSchema),
                Map.of("name", "noSchemaField"),
                Map.of("schema", 42)
        );
        doReturn(list).when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertEquals(validSchema, result);
    }

    @Test
    void applyReturnsNullForNonListValue() {
        Options options = mock(Options.class);
        doReturn("not a list").when(helper).getPropertyValue(eq("after"), any());

        Object result = helper.apply("after", options);

        assertNull(result);
    }
}
