package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class V108RevertMigrationTest {

    private final JsonNodeFactory factory = JsonNodeFactory.instance;
    private V108RevertMigration migration;

    @BeforeEach
    void setUp() {
        migration = new V108RevertMigration();
    }

    @DisplayName("Should rebuild nested content.folder from metaInfo.group and drop version 108")
    @Test
    void shouldRebuildNestedFolderFromGroup() {
        ObjectNode metaInfo = factory.objectNode();
        metaInfo.put("group", "A/B/C");
        ObjectNode content = factory.objectNode();
        content.put("migrations", "[101, 108]");
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("metaInfo", metaInfo);
        chainNode.set("content", content);

        ObjectNode result = migration.revert(chainNode);

        assertFalse(result.has("metaInfo"));
        ObjectNode folder = (ObjectNode) result.get("content").get("folder");
        assertEquals("A", folder.get("name").asText());
        assertEquals("B", folder.get("subfolder").get("name").asText());
        assertEquals("C", folder.get("subfolder").get("subfolder").get("name").asText());
        assertEquals("[101]", result.get("content").get("migrations").asText());
    }

    @DisplayName("Should keep other metaInfo fields and only remove the group")
    @Test
    void shouldPreserveOtherMetaInfoFields() {
        ObjectNode metaInfo = factory.objectNode();
        metaInfo.put("group", "A");
        metaInfo.put("application", "QIP");
        ObjectNode content = factory.objectNode();
        content.put("migrations", "[101, 108]");
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("metaInfo", metaInfo);
        chainNode.set("content", content);

        ObjectNode result = migration.revert(chainNode);

        assertInstanceOf(ObjectNode.class, result.get("metaInfo"));
        assertFalse(result.get("metaInfo").has("group"));
        assertEquals("QIP", result.get("metaInfo").get("application").asText());
        assertEquals("A", result.get("content").get("folder").get("name").asText());
        assertEquals("[101]", result.get("content").get("migrations").asText());
    }

    @DisplayName("Should not touch metaInfo or migrations when the group is blank")
    @Test
    void shouldDoNothingWhenGroupBlank() {
        ObjectNode metaInfo = factory.objectNode();
        metaInfo.put("group", "");
        metaInfo.put("application", "QIP");
        ObjectNode content = factory.objectNode();
        content.put("migrations", "[101, 108]");
        ObjectNode chainNode = factory.objectNode();
        chainNode.set("metaInfo", metaInfo);
        chainNode.set("content", content);

        ObjectNode result = migration.revert(chainNode);

        assertInstanceOf(ObjectNode.class, result.get("metaInfo"));
        assertEquals("QIP", result.get("metaInfo").get("application").asText());
        assertFalse(result.get("content").has("folder"));
        assertEquals("[101, 108]", result.get("content").get("migrations").asText());
    }

    @DisplayName("Should be a no-op when metaInfo is absent")
    @Test
    void shouldDoNothingWhenMetaInfoAbsent() {
        ObjectNode content = factory.objectNode();
        content.put("migrations", "[101]");
        ObjectNode serviceNode = factory.objectNode();
        serviceNode.set("content", content);

        ObjectNode result = migration.revert(serviceNode);

        assertFalse(result.get("content").has("folder"));
        assertEquals("[101]", result.get("content").get("migrations").asText());
    }

    @DisplayName("Should support only documents that carry both metaInfo and content")
    @Test
    void shouldSupportOnlyChainShapedDocuments() {
        ObjectNode withBoth = factory.objectNode();
        withBoth.set("metaInfo", factory.objectNode());
        withBoth.set("content", factory.objectNode());
        assertTrue(migration.supportsDocument(withBoth));

        ObjectNode withoutContent = factory.objectNode();
        withoutContent.set("metaInfo", factory.objectNode());
        assertFalse(migration.supportsDocument(withoutContent));

        ObjectNode withoutMetaInfo = factory.objectNode();
        withoutMetaInfo.set("content", factory.objectNode());
        assertFalse(migration.supportsDocument(withoutMetaInfo));
    }
}
