package org.qubership.integration.platform.io.readers.migrations.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import static org.qubership.integration.platform.io.readers.migrations.common.MigrationUtil.removeMigrationVersion;

/**
 * Writes {@code errorThrowing} back to every Service Call, so an installation without migration 109 behaves the
 * same: the call rethrows exactly when no item of {@code after} has the code {@code default}. A missing or
 * {@code null} {@code after} becomes {@code []}. Runs before
 * {@link V108RevertMigration}, while the chain fields still live inside {@code content}.
 */
@Component
public class V109RevertMigration implements RevertMigration {

    @Override
    public int getVersion() {
        return 109;
    }

    @Override
    public boolean supportsDocument(ObjectNode node) {
        return node.path("content").path("elements").isArray();
    }

    @Override
    public ObjectNode revert(ObjectNode node) {
        ObjectNode result = node.deepCopy();
        ObjectNode content = (ObjectNode) result.get("content");
        content.get("elements").forEach(V109RevertMigration::revertElement);
        removeMigrationVersion(content, String.valueOf(getVersion()));
        return result;
    }

    private static void revertElement(JsonNode element) {
        JsonNode type = element.has("type") ? element.get("type") : element.get("element-type");
        if (type != null && "service-call".equals(type.asText())
                && element.get("properties") instanceof ObjectNode properties) {
            properties.put("errorThrowing", !hasDefaultHandler(properties.get("after")));
            // An older template renders the <choice>, and so honors errorThrowing, only when "after" is present.
            if (properties.path("after").isMissingNode() || properties.get("after").isNull()) {
                properties.putArray("after");
            }
        }
        element.path("children").forEach(V109RevertMigration::revertElement);
    }

    private static boolean hasDefaultHandler(JsonNode after) {
        if (after == null) {
            return false;
        }
        if (after.isArray()) {
            for (JsonNode item : after) {
                if ("default".equals(item.path("code").asText())) {
                    return true;
                }
            }
            return false;
        }
        return "default".equals(after.path("code").asText());
    }
}
