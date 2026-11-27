package org.qubership.integration.platform.engine.service.debugger.util.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonSerializationHelperTest {
    private final JsonMapper mapper = new JsonMapper();

    static class Node {
        public String name;
        public Node next;

        Node(String name) {
            this.name = name;
        }
    }

    @Test
    void serializesScalarValues() {
        assertEquals("0", JsonSerializationHelper.serializeJson(0));
        assertEquals("true", JsonSerializationHelper.serializeJson(true));
        assertEquals("\"text\"", JsonSerializationHelper.serializeJson("text"));
    }

    @Test
    void serializesAnObjectWithoutCycles() {
        JsonNode result = mapper.readTree(JsonSerializationHelper.serializeJson(new Node("first")));
        assertEquals("first", result.get("name").asText());
        assertFalse(result.has("@json-id"));
    }

    @Test
    void preservesCircularReferences() {
        Node first = new Node("first");
        Node second = new Node("second");
        first.next = second;
        second.next = first;

        JsonNode result = mapper.readTree(JsonSerializationHelper.serializeJson(first));
        JsonNode reference = result.get("reference");
        assertEquals("first", reference.get("name").asText());
        assertEquals("second", reference.get("next").get("name").asText());
        assertEquals(result.get("@json-id").asText(), reference.get("next").get("next").asText());
    }
}
