package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                properties -> properties.set("baz", TextNode.valueOf("biz"))));
    }

    @DisplayName("elementHasType should return predicate that tests element type field")
    @Test
    void elementHasTypeShouldCheckElementType() {
        Predicate<JsonNode> predicate = elementHasType("foo");
        assertNotNull(predicate);

        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        assertFalse(predicate.test(elementNode));

        elementNode.set("type", TextNode.valueOf("foo"));
        assertTrue(predicate.test(elementNode));

        elementNode.set("type", TextNode.valueOf("bar"));
        assertFalse(predicate.test(elementNode));
    }

    @DisplayName("Should apply property migrations described by rules")
    @Test
    void shouldApplyPropertyMigrationsDescribedByRules() throws JsonProcessingException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", TextNode.valueOf("42"));
        elementNode.set("type", TextNode.valueOf("foo"));
        elementNode.withObjectProperty("properties")
                .set("bla", TextNode.valueOf("bla-bla-bla"));

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode
                .withObject("content")
                .withArrayProperty("elements")
                .add(elementNode);

        chainNode = makeMigration(chainNode);
        assertTrue(chainNode.get("content").get("elements").get(0).get("properties").has("baz"));
    }
}
