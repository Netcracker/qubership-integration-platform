package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.common.MigrationUtil.removeMigrationVersion;

@Component
public class V101RevertMigration implements RevertMigration {

    @Override
    public int getVersion() {
        return 101;
    }

    @Override
    public ObjectNode revert(ObjectNode canonicalNode) {
        ObjectNode result = MigrationUtil.moveContentFieldsToRoot(
                canonicalNode.deepCopy()
        );

        ArrayNode elements = (ArrayNode) result.get("elements");
        if (elements != null) {
            for (int i = 0; i < elements.size(); i++) {
                ObjectNode element = (ObjectNode) elements.get(i);

                renameField(element, "type", "element-type", false);
                extractPropertiesFilename(element);

                ObjectNode reordered = reorderElement(element);
                elements.set(i, reordered);
            }
        }

        // remove 101 from migrations
        removeMigrationVersion(result, String.valueOf(this.getVersion()));

        return result;
    }

    private void renameField(JsonNode node, String from, String to, boolean recursive) {
        if (node.isObject() && node.has(from) && !node.has(to)) {
            ObjectNode obj = (ObjectNode) node;
            obj.set(to, obj.get(from));
            obj.remove(from);
        }
        if (recursive) {
            node.forEach(child -> renameField(child, from, to, true));
        }
    }

    private ObjectNode reorderElement(ObjectNode element) {
        ObjectNode reordered = element.objectNode();

        copyIfExists(element, reordered, "id");
        copyIfExists(element, reordered, "name");
        copyIfExists(element, reordered, "description");

        List<String> remainingFields = new ArrayList<>();
        element.fieldNames().forEachRemaining(field -> {
            if (!reordered.has(field)) {
                remainingFields.add(field);
            }
        });

        remainingFields.stream()
                .sorted()
                .forEach(field -> reordered.set(field, element.get(field)));

        return reordered;
    }

    private void copyIfExists(ObjectNode from, ObjectNode to, String field) {
        if (from.has(field)) {
            to.set(field, from.get(field));
        }
    }

    private void extractPropertiesFilename(ObjectNode element) {
        JsonNode propsNode = element.get("properties");
        if (!(propsNode instanceof ObjectNode props)) {
            return;
        }

        JsonNode filenameNode = props.get("propertiesFilename");
        if (filenameNode == null) {
            return;
        }

        element.set("properties-filename", filenameNode);
        props.remove("propertiesFilename");

        if (props.isEmpty()) {
            element.remove("properties");
        }
    }
}
