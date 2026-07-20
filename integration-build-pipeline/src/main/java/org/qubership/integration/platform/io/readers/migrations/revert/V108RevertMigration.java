package org.qubership.integration.platform.io.readers.migrations.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.io.readers.migrations.common.GroupPathUtils;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil.removeMigrationVersion;

/**
 * Restores the legacy nested {@code content.folder} structure from the top-level
 * {@code metaInfo.group} path. Runs before {@link V101RevertMigration}, while the
 * chain fields (including {@code migrations}) still live inside {@code content}.
 */
@Component
public class V108RevertMigration implements RevertMigration {

    private static final String META_INFO_FIELD = "metaInfo";

    @Override
    public int getVersion() {
        return 108;
    }

    @Override
    public boolean supportsDocument(ObjectNode node) {
        return node.get(META_INFO_FIELD) instanceof ObjectNode
                && node.get("content") instanceof ObjectNode;
    }

    @Override
    public ObjectNode revert(ObjectNode node) {
        ObjectNode result = node.deepCopy();

        if (!(result.get(META_INFO_FIELD) instanceof ObjectNode metaInfo)
                || !(result.get("content") instanceof ObjectNode content)) {
            return result;
        }

        JsonNode groupNode = metaInfo.get("group");
        if (groupNode == null || groupNode.asText().isBlank()) {
            return result;
        }

        ObjectNode folder = buildNestedFolder(GroupPathUtils.parseSegments(groupNode.asText()));
        if (folder != null) {
            content.set("folder", folder);
        }
        removeMigrationVersion(content, String.valueOf(getVersion()));

        // Keep any other metaInfo fields; drop the node only once it is empty.
        metaInfo.remove("group");
        if (metaInfo.isEmpty()) {
            result.remove(META_INFO_FIELD);
        }
        return result;
    }

    private ObjectNode buildNestedFolder(List<String> segments) {
        ObjectNode root = null;
        ObjectNode current = null;
        for (String segment : segments) {
            ObjectNode folder = JsonNodeFactory.instance.objectNode();
            folder.put("name", segment);
            if (current == null) {
                root = folder;
            } else {
                current.set("subfolder", folder);
            }
            current = folder;
        }
        return root;
    }
}
