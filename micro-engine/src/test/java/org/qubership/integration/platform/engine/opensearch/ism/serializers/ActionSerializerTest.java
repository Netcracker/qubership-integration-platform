package org.qubership.integration.platform.engine.opensearch.ism.serializers;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.ActionRetry;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.ReadOnlyAction;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ActionSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeSupportedActionWithMappedFieldName() throws Exception {
        ReadOnlyAction action = new ReadOnlyAction();

        JsonNode result = objectMapper.readTree(objectMapper.writeValueAsString(action));

        assertTrue(result.has("read_only"));
        assertTrue(result.get("read_only").isObject());
        assertEquals(0, result.get("read_only").size());
        assertEquals(1, result.size());
    }

    @Test
    void shouldSerializeBasePropertiesAtRootLevelWhenTheyArePresent() throws Exception {
        ReadOnlyAction action = new ReadOnlyAction();
        action.setTimeout(TimeValue.timeValueMinutes(5));
        action.setRetry(ActionRetry.builder()
                .count(3L)
                .delay(TimeValue.timeValueSeconds(10))
                .build());

        JsonNode result = objectMapper.readTree(objectMapper.writeValueAsString(action));

        assertEquals("300000000000nanos", result.get("timeout").asText());
        assertEquals(3L, result.get("retry").get("count").asLong());
        assertEquals("10000000000nanos", result.get("retry").get("delay").asText());
        assertTrue(result.has("read_only"));
        assertTrue(result.get("read_only").isObject());
        assertEquals(0, result.get("read_only").size());
        assertEquals(3, result.size());
    }

    @Test
    void shouldWrapIOExceptionWhenActionClassIsUnsupported() {
        OpenSearchTestUtils.UnsupportedAction action = new OpenSearchTestUtils.UnsupportedAction();

        JsonMappingException exception = assertThrows(
                JsonMappingException.class,
                () -> objectMapper.writeValueAsString(action)
        );

        assertTrue(exception.getMessage().contains("Unexpected IOException"));
        assertTrue(exception.getMessage().contains("Unsupported Action class: "
                + OpenSearchTestUtils.UnsupportedAction.class.getCanonicalName()));
    }


}
