package org.qubership.integration.platform.io.readers.migrations.chain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

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
                            properties.set("handleChainFailureAction", StringNode.valueOf("default"));
                            if (!properties.has("chainFailureHandlerContainer")) {
                                properties.set("chainFailureHandlerContainer", JsonNodeFactory.instance.objectNode());
                            }
                        }
                    }
            ),
            Map.entry("if",
                    properties -> {
                        if (properties.path("priority") instanceof StringNode) {
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
    public ObjectNode makeMigration(ObjectNode fileNode) throws JacksonException {
        log.debug("Applying chain migration: {}", getVersion());
        ObjectNode rootNode = fileNode.deepCopy();
        rootNode
                .path("content")
                .path("elements")
                .forEach(this::migrateElementNode);
        return rootNode;
    }

    private void migrateElementNode(JsonNode elementNode) {
        JsonNode typeNode = elementNode.path("type");
        if (typeNode.isMissingNode()) {
            typeNode = elementNode.path("element-type");
        }
        String type = typeNode.asText();
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
        Optional.ofNullable(getMigratorMap().get(type))
                .ifPresent(migrator -> migrator.accept((ObjectNode) properties));
    }

    protected Map<String, Consumer<ObjectNode>> getMigratorMap() {
        return PROPERTY_MIGRATOR_MAP;
    }
}
