package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;


class V102ServiceImportFileMigrationTest {
    @Test
    public void testMigration() throws JsonProcessingException {
        YAMLMapper mapper = new YAMLMapper();
        JsonNode node = mapper.readTree("""
                ---
                content:
                  operations:
                  - id: "foo"
                    name: "emitUserSignUpEvent"
                    method: "subscribe"
                    path: "user/signed-up"
                  - id: "bar"
                    method: "publish"
                    path: "user/notify"
                """);

        assertInstanceOf(ObjectNode.class, node);

        V102ServiceImportFileMigration migration = new V102ServiceImportFileMigration();
        ObjectNode result = migration.makeMigration((ObjectNode) node);
        assertEquals("emitUserSignUpEvent", result.path("content")
                .path("operations").path(0).path("name").asText());
        assertEquals("bar-publish-user/notify", result.path("content")
                .path("operations").path(1).path("name").asText());
    }

}
