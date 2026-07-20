package org.qubership.integration.platform.io.readers.migrations.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.io.readers.migrations.common.GroupPathUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the deprecated nested {@code content.folder} structure into the flat
 * top-level {@code metaInfo.group} path (segments joined with {@code /}).
 */
@Slf4j
@Component
public class V108ChainImportFileMigration implements ChainImportFileMigration {

    private static final String FOLDER_FIELD = "folder";

    @Override
    public int getVersion() {
        return 108;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) {
        log.debug("Applying chain migration: {}", getVersion());

        ObjectNode result = fileNode.deepCopy();

        if (!(result.get("content") instanceof ObjectNode contentNode)) {
            return result;
        }
        if (!(contentNode.get(FOLDER_FIELD) instanceof ObjectNode)) {
            return result;
        }

        List<String> segments = new ArrayList<>();
        JsonNode current = contentNode.get(FOLDER_FIELD);
        while (current instanceof ObjectNode folder) {
            JsonNode nameNode = folder.get("name");
            if (nameNode != null && !nameNode.asText().isBlank()) {
                segments.add(GroupPathUtils.sanitizeSegment(nameNode.asText()));
            }
            current = folder.get("subfolder");
        }

        if (!segments.isEmpty()) {
            ObjectNode metaInfo = result.get("metaInfo") instanceof ObjectNode existing
                    ? existing
                    : result.putObject("metaInfo");
            metaInfo.put("group", String.join("/", segments));
            contentNode.remove(FOLDER_FIELD);
        }

        return result;
    }
}
