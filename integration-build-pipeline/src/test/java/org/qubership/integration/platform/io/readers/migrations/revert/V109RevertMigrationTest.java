package org.qubership.integration.platform.io.readers.migrations.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V109RevertMigrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final V109RevertMigration migration = new V109RevertMigration();

    private ObjectNode chain(String elements) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"content": {"migrations": "[100, 108, 109]", "elements": %s}}
                """.formatted(elements));
    }

    private static JsonNode firstProperties(ObjectNode result) {
        return result.at("/content/elements/0/properties");
    }

    @Test
    void writesErrorThrowingTrueWithoutDefaultHandler() throws Exception {
        ObjectNode result = migration.revert(chain(
                "[{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{\"after\":[]}}]"));

        assertTrue(firstProperties(result).get("errorThrowing").asBoolean());
    }

    @Test
    void writesErrorThrowingFalseWithDefaultHandler() throws Exception {
        ObjectNode result = migration.revert(chain(
                "[{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{\"after\":[{\"code\":\"default\"}]}}]"));

        assertFalse(firstProperties(result).get("errorThrowing").asBoolean());
    }

    @Test
    void treatsSingleObjectAfterAsOneItemList() throws Exception {
        ObjectNode result = migration.revert(chain(
                "[{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{\"after\":{\"code\":\"default\"}}}]"));

        assertFalse(firstProperties(result).get("errorThrowing").asBoolean());
    }

    @Test
    void keepsAfterAndCodes() throws Exception {
        String after = "[{\"code\":\"500..599\",\"wildcard\":true},{\"code\":\"default\"}]";
        ObjectNode result = migration.revert(chain(
                "[{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{\"after\":" + after + "}}]"));

        assertEquals(MAPPER.readTree(after), firstProperties(result).get("after"));
    }

    @Test
    void writesEmptyAfterWhenMissing() throws Exception {
        ObjectNode result = migration.revert(chain(
                "[{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{}}]"));

        assertTrue(firstProperties(result).get("errorThrowing").asBoolean());
        assertEquals(MAPPER.readTree("[]"), firstProperties(result).get("after"));
    }

    @Test
    void writesEmptyAfterWhenNull() throws Exception {
        ObjectNode result = migration.revert(chain(
                "[{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{\"after\":null}}]"));

        assertTrue(firstProperties(result).get("errorThrowing").asBoolean());
        assertEquals(MAPPER.readTree("[]"), firstProperties(result).get("after"));
    }

    @Test
    void removesVersion109() throws Exception {
        ObjectNode result = migration.revert(chain(
                "[{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{\"after\":[]}}]"));

        assertEquals("[100, 108]", result.at("/content/migrations").asText());
    }

    @Test
    void removesVersion109WithoutServiceCall() throws Exception {
        ObjectNode document = chain("[{\"id\":\"1\",\"type\":\"script\",\"properties\":{}}]");

        assertTrue(migration.supportsDocument(document));
        assertEquals("[100, 108]", migration.revert(document).at("/content/migrations").asText());
    }

    @Test
    void revertsNestedServiceCall() throws Exception {
        ObjectNode result = migration.revert(chain("""
                [{"id":"1","type":"container","children":[
                  {"id":"2","element-type":"service-call","properties":{"after":[]}}]}]"""));

        assertTrue(result.at("/content/elements/0/children/0/properties/errorThrowing").asBoolean());
    }
}
