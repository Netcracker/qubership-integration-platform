package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.chain.V106ChainImportFileMigration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import static org.junit.jupiter.api.Assertions.*;

class V106ChainImportFileMigrationTest {
    private V106ChainImportFileMigration migration;

    @BeforeEach
    void setUp() {
        migration = new V106ChainImportFileMigration();
    }

    @DisplayName("Should change 'priorityNumber' property type from string to integer for 'when' element")
    @Test
    void shouldChangePriorityNumberPropertyTypeFromStringToIntegerForWhenElement() throws JacksonException {
        testPropTypeChangeFromStringToInteger("when", "priorityNumber");
    }

    @DisplayName("Should change 'priorityNumber' property type from string to integer for 'catch' element")
    @Test
    void shouldChangePriorityNumberPropertyTypeFromStringToIntegerForCatchElement() throws JacksonException {
        testPropTypeChangeFromStringToInteger("catch", "priorityNumber");
    }

    @DisplayName("Should change 'priority' property type from string to integer for 'catch-2' element")
    @Test
    void shouldChangePriorityPropertyTypeFromStringToIntegerForCatch2Element() throws JacksonException {
        testPropTypeChangeFromStringToInteger("catch-2", "priority");
    }

    @DisplayName("Should remove 'priorityNumber' property from 'if' element")
    @Test
    void shouldRemovePriorityNumberPropertyFromIfElement() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("if"));
        elementNode.withObjectProperty("properties")
                .set("priorityNumber", StringNode.valueOf("42"));

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode
                .withObject("content")
                .withArrayProperty("elements")
                .add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode propertiesNode = chainNode.get("content").get("elements").get(0).get("properties");
        assertFalse(propertiesNode.has("priorityNumber"));
    }

    private void testPropTypeChangeFromStringToInteger(String elementType, String propName) throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf(elementType));
        elementNode.withObjectProperty("properties")
                .set(propName, StringNode.valueOf("42"));

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
