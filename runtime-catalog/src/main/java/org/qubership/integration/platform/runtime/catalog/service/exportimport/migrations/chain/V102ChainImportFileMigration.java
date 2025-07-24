package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

@Slf4j
@Component
public class V102ChainImportFileMigration implements ChainImportFileMigration {
    private static final Map<String, Consumer<ObjectNode>> PROPERTY_MIGRATOR_MAP = Map.ofEntries(
            Map.entry("async-api-trigger",
                    properties -> {
                        if (properties.path("asyncValidationSchema").isEmpty()) {
                            properties.remove("asyncValidationSchema");
                        }
                    }
            ),
            Map.entry("http-trigger",
                    properties -> {
                        if (!properties.has("handleChainFailureAction")) {
                            properties.set("handleChainFailureAction", TextNode.valueOf("default"));
                            if (!properties.has("chainFailureHandlerContainer")) {
                                properties.set("chainFailureHandlerContainer", JsonNodeFactory.instance.objectNode());
                            }
                        }
                    }
            ),
            Map.entry("if",
                    properties -> {
                        if (properties.path("priority") instanceof TextNode) {
                            try {
                                Integer priority = Integer.valueOf(properties.path("priority").asText());
                                properties.set("priority", JsonNodeFactory.instance.numberNode(priority));
                            } catch (NumberFormatException e) {
                                log.warn("Failed to convert priority value from string to integer", e);
                            }
                        }
                    }
            ),
            Map.entry("service-call",
                    properties -> {
                        if (properties.path("before").isEmpty()) {
                            properties.remove("before");
                        }
                    }
            )
    );

    @Override
    public int getVersion() {
        return 102;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JsonProcessingException {
        log.debug("Applying chain migration: {}", getVersion());
        ObjectNode rootNode = fileNode.deepCopy();
        rootNode
                .path("content")
                .path("elements")
                .forEach(this::migrateElementNode);
        return rootNode;
    }

    private void migrateElementNode(JsonNode elementNode) {
        String type = elementNode.path("type").asText();
        JsonNode propertiesNode = elementNode.path("properties");
        log.debug("Applying migration to element {} of type {}", elementNode.path("id").asText(), type);
        migrateElementNode(type, propertiesNode);
        elementNode
                .path("children")
                .forEach(this::migrateElementNode);
    }

    private void migrateElementNode(String type, JsonNode properties) {
        if (!(properties instanceof ObjectNode)) {
            return;
        }
        Optional.ofNullable(PROPERTY_MIGRATOR_MAP.get(type))
                .ifPresent(migrator -> migrator.accept((ObjectNode) properties));
    }
}
