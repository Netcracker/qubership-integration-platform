package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.chain.V108ChainImportFileMigration;

import static org.junit.jupiter.api.Assertions.*;

class V108ChainImportFileMigrationTest {

    private final JsonNodeFactory factory = JsonNodeFactory.instance;
    private V108ChainImportFileMigration migration;

    @BeforeEach
    void setUp() {
        migration = new V108ChainImportFileMigration();
    }

    @DisplayName("Should convert nested content.folder into top-level metaInfo.group")
    @Test
    void shouldConvertNestedFolderToGroupPath() {
        ObjectNode folder = factory.objectNode();
        folder.put("name", "A");
        ObjectNode subfolder = factory.objectNode();
        subfolder.put("name", "B");
        ObjectNode leaf = factory.objectNode();
        leaf.put("name", "C");
        subfolder.set("subfolder", leaf);
        folder.set("subfolder", subfolder);

        ObjectNode content = factory.objectNode();
        content.set("folder", folder);
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("content", content);

        ObjectNode result = migration.makeMigration(chainNode);

        assertEquals("A/B/C", result.get("metaInfo").get("group").asText());
        assertFalse(result.get("content").has("folder"));
    }

    @DisplayName("Should replace forbidden characters in folder names with '-'")
    @Test
    void shouldSanitizeForbiddenCharacters() {
        ObjectNode folder = factory.objectNode();
        folder.put("name", "a:b");
        ObjectNode subfolder = factory.objectNode();
        subfolder.put("name", "c/d");
        folder.set("subfolder", subfolder);

        ObjectNode content = factory.objectNode();
        content.set("folder", folder);
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("content", content);

        ObjectNode result = migration.makeMigration(chainNode);

        assertEquals("a-b/c-d", result.get("metaInfo").get("group").asText());
    }

    @DisplayName("Should be a no-op when content.folder is absent")
    @Test
    void shouldDoNothingWhenFolderAbsent() {
        ObjectNode content = factory.objectNode();
        content.put("description", "no folder here");
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("content", content);

        ObjectNode result = migration.makeMigration(chainNode);

        assertFalse(result.has("metaInfo"));
        assertFalse(result.get("content").has("folder"));
    }

    @DisplayName("Should merge group into an existing metaInfo node without dropping other fields")
    @Test
    void shouldPreserveExistingMetaInfoFields() {
        ObjectNode folder = factory.objectNode();
        folder.put("name", "A");

        ObjectNode content = factory.objectNode();
        content.set("folder", folder);
        ObjectNode metaInfo = factory.objectNode();
        metaInfo.put("application", "QIP");
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("metaInfo", metaInfo);
        chainNode.set("content", content);

        ObjectNode result = migration.makeMigration(chainNode);

        assertEquals("QIP", result.get("metaInfo").get("application").asText());
        assertEquals("A", result.get("metaInfo").get("group").asText());
        assertFalse(result.get("content").has("folder"));
    }

    @DisplayName("Should keep content.folder when every folder name is blank")
    @Test
    void shouldKeepFolderWhenAllNamesBlank() {
        ObjectNode folder = factory.objectNode();
        folder.put("name", "   ");

        ObjectNode content = factory.objectNode();
        content.set("folder", folder);
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("content", content);

        ObjectNode result = migration.makeMigration(chainNode);

        assertFalse(result.has("metaInfo"));
        assertTrue(result.get("content").has("folder"));
    }
}
