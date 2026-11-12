package org.qubership.integration.platform.io.readers.migrations.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V109ChainImportFileMigrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final V109ChainImportFileMigration migration = new V109ChainImportFileMigration();

    private JsonNode migrate(String elementJson) throws Exception {
        ObjectNode chain = (ObjectNode) MAPPER.readTree("{\"content\":{\"elements\":[" + elementJson + "]}}");
        return migration.makeMigration(chain).get("content").get("elements").get(0);
    }

    private JsonNode serviceCallProperties(String propertiesJson) throws Exception {
        return migrate("{\"id\":\"1\",\"type\":\"service-call\",\"properties\":" + propertiesJson + "}").get("properties");
    }

    @Test
    void versionIs109() {
        assertEquals(109, migration.getVersion());
    }

    @Test
    void removesErrorThrowing() throws Exception {
        JsonNode properties = serviceCallProperties("{\"errorThrowing\":true,\"after\":[]}");
        assertTrue(!properties.has("errorThrowing"));
    }

    @Test
    void setsMissingAfterToEmptyList() throws Exception {
        JsonNode properties = serviceCallProperties("{\"errorThrowing\":true}");
        assertEquals(MAPPER.readTree("[]"), properties.get("after"));
    }

    @Test
    void setsNullAfterToEmptyList() throws Exception {
        JsonNode properties = serviceCallProperties("{\"after\":null}");
        assertEquals(MAPPER.readTree("[]"), properties.get("after"));
    }

    @Test
    void rewritesDefaultLabelCode() throws Exception {
        JsonNode properties = serviceCallProperties("{\"after\":[{\"code\":\"Default\"}]}");
        assertEquals(MAPPER.readTree("[{\"code\":\"default\"}]"), properties.get("after"));
    }

    @Test
    void keepsDefaultLabelCodeWhenDefaultExists() throws Exception {
        String after = "[{\"code\":\"Default\"},{\"code\":\"default\"}]";
        JsonNode properties = serviceCallProperties("{\"after\":" + after + "}");
        assertEquals(MAPPER.readTree(after), properties.get("after"));
    }

    @Test
    void rewritesWildcardLabels() throws Exception {
        for (int n = 1; n <= 5; n++) {
            JsonNode properties = serviceCallProperties(
                    "{\"after\":[{\"code\":\"" + n + "xx\",\"wildcard\":false}]}");
            assertEquals(
                    MAPPER.readTree("[{\"code\":\"" + n + "00.." + n + "99\",\"wildcard\":true}]"),
                    properties.get("after"));
        }
    }

    @Test
    void leavesNonArrayAfterAlone() throws Exception {
        JsonNode properties = serviceCallProperties("{\"after\":{\"code\":\"404\"}}");
        assertEquals(MAPPER.readTree("{\"code\":\"404\"}"), properties.get("after"));
    }

    @Test
    void migratesNestedServiceCall() throws Exception {
        JsonNode container = migrate("{\"id\":\"0\",\"type\":\"container\",\"children\":["
                + "{\"id\":\"1\",\"type\":\"service-call\",\"properties\":{\"errorThrowing\":true}}]}");
        JsonNode properties = container.get("children").get(0).get("properties");
        assertTrue(!properties.has("errorThrowing"));
        assertEquals(MAPPER.readTree("[]"), properties.get("after"));
    }

    @Test
    void leavesOtherElementTypesAlone() throws Exception {
        JsonNode element = migrate("{\"id\":\"2\",\"type\":\"http-trigger\",\"properties\":{\"errorThrowing\":true}}");
        assertTrue(element.get("properties").has("errorThrowing"));
        assertTrue(!element.get("properties").has("after"));
    }
}
