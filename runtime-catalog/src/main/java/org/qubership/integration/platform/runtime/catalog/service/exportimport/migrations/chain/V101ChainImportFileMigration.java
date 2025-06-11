package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class V101ChainImportFileMigration implements ChainImportFileMigration {
    @Override
    public int getVersion() {
        return 101;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JsonProcessingException {
        log.debug("Applying chain migration: {}", getVersion());
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("id", fileNode.get("id"));
        result.set("name", fileNode.get("name"));

        // Move all fields except id and name to the content node
        ObjectNode contentNode = JsonNodeFactory.instance.objectNode();
        fileNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!"id".equals(key) && !"name".equals(key)) {
                contentNode.set(key, entry.getValue());
            }
        });

        // Rename properties-filename property to propertiesFilename
        renameField(contentNode, "properties-filename", "propertiesFilename");
        // Make new default value from "" to "org.apache.kafka.common.serialization.StringSerializer" for keySerializer
        setNewValueToEmptyField(contentNode, "keySerializer", "org.apache.kafka.common.serialization.StringSerializer");

        result.set("content", contentNode);
        return result;
    }

    private void renameField(JsonNode node, String from, String to) {
        if (node.isObject()) {
            if (node.has(from)) {
                if (node.has(to)) {
                    log.error("Object already has field {}", to);
                } else {
                    ((ObjectNode) node).set(to, node.get(from));
                    ((ObjectNode) node).remove(from);
                }
            }
        }
        node.forEach(child -> renameField(child, from, to));
    }

    private void setNewValueToEmptyField(JsonNode node, String fieldName, String newValue) {
        if (node.isObject()) {
            JsonNode field = node.get(fieldName);
            if (field != null && (field.isNull() || field.asText().isBlank())) {
                ((ObjectNode) node).put(fieldName, newValue);
            }
            node.fields().forEachRemaining(entry -> setNewValueToEmptyField(entry.getValue(), fieldName, newValue));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                setNewValueToEmptyField(item, fieldName, newValue);
            }
        }
    }
}
