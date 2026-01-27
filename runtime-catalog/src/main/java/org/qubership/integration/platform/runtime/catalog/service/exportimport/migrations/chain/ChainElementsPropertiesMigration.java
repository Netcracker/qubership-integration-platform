package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Slf4j
public abstract class ChainElementsPropertiesMigration implements ChainImportFileMigration {
    protected record ElementPropertiesMigrationRule(
            Predicate<JsonNode> elementPredicate,
            Consumer<ObjectNode> action
    ) {}

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
        String id = elementNode.get("id").asText();
        String type = getElementType(elementNode);
        JsonNode propertiesNode = elementNode.path("properties");
        log.debug("Applying migration to element {} of type {}", id, type);
        getMigrationRules().stream()
                .filter(rule -> rule.elementPredicate.test(elementNode))
                .forEach(rule -> {
                    if (propertiesNode instanceof ObjectNode node) {
                        rule.action.accept(node);
                    } else {
                        log.warn("Element {} properties node is not an object: {}", id, propertiesNode);
                    }
                });
        elementNode
                .path("children")
                .forEach(this::migrateElementNode);
    }

    protected abstract Collection<ElementPropertiesMigrationRule> getMigrationRules();

    protected static Predicate<JsonNode> elementHasType(String typeName) {
        return elementNode -> {
            JsonNode typeNode = elementNode.path("type");
            if (typeNode.isMissingNode()) {
                typeNode = elementNode.path("element-type");
            }
            return typeName.equals(typeNode.asText());
        };
    }

    private static String getElementType(JsonNode elementNode) {
        JsonNode typeNode = elementNode.path("type");
        if (typeNode.isMissingNode()) {
            typeNode = elementNode.path("element-type");
        }
        return typeNode.asText();
    }
}
