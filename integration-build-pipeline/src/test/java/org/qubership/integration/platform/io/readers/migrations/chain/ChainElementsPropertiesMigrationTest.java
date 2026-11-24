package org.qubership.integration.platform.io.readers.migrations.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ChainElementsPropertiesMigrationTest extends ChainElementsPropertiesMigration {
    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    protected Collection<ElementPropertiesMigrationRule> getMigrationRules() {
        return List.of(new ElementPropertiesMigrationRule(
                elementHasType("foo"),
                properties -> properties.set("baz", StringNode.valueOf("biz"))));
    }

    @DisplayName("elementHasType should return predicate that tests element type field")
    @Test
    void elementHasTypeShouldCheckElementType() {
        Predicate<JsonNode> predicate = elementHasType("foo");
        assertNotNull(predicate);

        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        assertFalse(predicate.test(elementNode));

        elementNode.set("type", StringNode.valueOf("foo"));
        assertTrue(predicate.test(elementNode));

        elementNode.set("type", StringNode.valueOf("bar"));
        assertFalse(predicate.test(elementNode));
    }

    @DisplayName("Should apply property migrations described by rules")
    @Test
    void shouldApplyPropertyMigrationsDescribedByRules() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("foo"));
        elementNode.withObjectProperty("properties")
                .set("bla", StringNode.valueOf("bla-bla-bla"));

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode
                .withObject("content")
                .withArrayProperty("elements")
                .add(elementNode);

        chainNode = makeMigration(chainNode);
        assertTrue(chainNode.get("content").get("elements").get(0).get("properties").has("baz"));
    }
}
