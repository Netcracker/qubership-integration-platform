package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class V106ChainImportFileMigrationTest {
    private V106ChainImportFileMigration migration;

    @BeforeEach
    void setUp() {
        migration = new V106ChainImportFileMigration();
    }

    @DisplayName("Should change 'priorityNumber' property type from string to integer for 'when' element")
    @Test
    void shouldChangePriorityNumberPropertyTypeFromStringToIntegerForWhenElement() throws JsonProcessingException {
        testPropTypeChangeFromStringToInteger("when", "priorityNumber");
    }

    @DisplayName("Should change 'priorityNumber' property type from string to integer for 'catch' element")
    @Test
    void shouldChangePriorityNumberPropertyTypeFromStringToIntegerForCatchElement() throws JsonProcessingException {
        testPropTypeChangeFromStringToInteger("catch", "priorityNumber");
    }

    @DisplayName("Should change 'priority' property type from string to integer for 'catch-2' element")
    @Test
    void shouldChangePriorityPropertyTypeFromStringToIntegerForCatch2Element() throws JsonProcessingException {
        testPropTypeChangeFromStringToInteger("catch-2", "priority");
    }

    @DisplayName("Should remove 'priorityNumber' property from 'if' element")
    @Test
    void shouldRemovePriorityNumberPropertyFromIfElement() throws JsonProcessingException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", TextNode.valueOf("42"));
        elementNode.set("type", TextNode.valueOf("if"));
        elementNode.withObjectProperty("properties")
                .set("priorityNumber", TextNode.valueOf("42"));

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode
                .withObject("content")
                .withArrayProperty("elements")
                .add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode propertiesNode = chainNode.get("content").get("elements").get(0).get("properties");
        assertFalse(propertiesNode.has("priorityNumber"));
    }

    private void testPropTypeChangeFromStringToInteger(String elementType, String propName) throws JsonProcessingException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", TextNode.valueOf("42"));
        elementNode.set("type", TextNode.valueOf(elementType));
        elementNode.withObjectProperty("properties")
                .set(propName, TextNode.valueOf("42"));

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode
                .withObject("content")
                .withArrayProperty("elements")
                .add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode propertyNode = chainNode.get("content").get("elements").get(0).get("properties").get(propName);

        assertTrue(propertyNode.isIntegralNumber());
        assertEquals(Integer.valueOf(42), propertyNode.asInt());
    }
}
