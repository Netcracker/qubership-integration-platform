package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.chain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.readers.migrations.chain.V107ChainImportFileMigration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import static org.junit.jupiter.api.Assertions.*;

class V107ChainImportFileMigrationTest {
    private V107ChainImportFileMigration migration;

    @BeforeEach
    void setUp() {
        migration = new V107ChainImportFileMigration();
    }

    @DisplayName("Should move 'asyncValidationSchema' into 'after[0].schema' for 'async-api-trigger' element")
    @Test
    void shouldMoveAsyncValidationSchemaIntoAfterSchema() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("async-api-trigger"));
        ObjectNode properties = elementNode.withObjectProperty("properties");
        properties.set("asyncValidationSchema", StringNode.valueOf("{\"type\":\"object\"}"));
        ObjectNode afterElement = JsonNodeFactory.instance.objectNode();
        afterElement.put("someField", "someValue");
        properties.withArrayProperty("after").add(afterElement);

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode.withObject("content").withArrayProperty("elements").add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode propertiesNode = chainNode.get("content").get("elements").get(0).get("properties");
        assertFalse(propertiesNode.has("asyncValidationSchema"));
        assertEquals("{\"type\":\"object\"}", propertiesNode.get("after").get(0).get("schema").asText());
    }

    @DisplayName("Should not modify 'async-api-trigger' when 'asyncValidationSchema' is missing")
    @Test
    void shouldNotModifyAsyncApiTriggerWhenAsyncValidationSchemaIsMissing() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("async-api-trigger"));
        ObjectNode properties = elementNode.withObjectProperty("properties");
        ObjectNode afterElement = JsonNodeFactory.instance.objectNode();
        afterElement.put("someField", "someValue");
        properties.withArrayProperty("after").add(afterElement);

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode.withObject("content").withArrayProperty("elements").add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode propertiesNode = chainNode.get("content").get("elements").get(0).get("properties");
        assertFalse(propertiesNode.get("after").get(0).has("schema"));
    }

    @DisplayName("Should not modify 'async-api-trigger' when 'after' array has more than one element")
    @Test
    void shouldNotModifyAsyncApiTriggerWhenAfterArrayHasMultipleElements() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("async-api-trigger"));
        ObjectNode properties = elementNode.withObjectProperty("properties");
        properties.set("asyncValidationSchema", StringNode.valueOf("{\"type\":\"object\"}"));
        properties.withArrayProperty("after")
                .add(JsonNodeFactory.instance.objectNode())
                .add(JsonNodeFactory.instance.objectNode());

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode.withObject("content").withArrayProperty("elements").add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode propertiesNode = chainNode.get("content").get("elements").get(0).get("properties");
        assertTrue(propertiesNode.has("asyncValidationSchema"));
    }

    @DisplayName("Should rename 'scheme' to 'schema' in 'afterValidation' array for 'service-call' element")
    @Test
    void shouldRenameSchemeToSchemaInAfterValidation() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("service-call"));
        ObjectNode properties = elementNode.withObjectProperty("properties");
        ObjectNode validationEntry = JsonNodeFactory.instance.objectNode();
        validationEntry.put("scheme", "myScheme");
        validationEntry.put("otherField", "otherValue");
        properties.withArrayProperty("afterValidation").add(validationEntry);

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode.withObject("content").withArrayProperty("elements").add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode entry = chainNode.get("content").get("elements").get(0)
                .get("properties").get("afterValidation").get(0);
        assertFalse(entry.has("scheme"));
        assertEquals("myScheme", entry.get("schema").asText());
        assertEquals("otherValue", entry.get("otherField").asText());
    }

    @DisplayName("Should not modify 'service-call' when 'afterValidation' is empty")
    @Test
    void shouldNotModifyServiceCallWhenAfterValidationIsEmpty() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("service-call"));
        elementNode.withObjectProperty("properties").withArrayProperty("afterValidation");

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode.withObject("content").withArrayProperty("elements").add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode afterValidation = chainNode.get("content").get("elements").get(0)
                .get("properties").get("afterValidation");
        assertTrue(afterValidation.isEmpty());
    }

    @DisplayName("Should not modify entries in 'afterValidation' that don't have 'scheme' field")
    @Test
    void shouldNotModifyEntriesWithoutSchemeField() throws JacksonException {
        ObjectNode elementNode = JsonNodeFactory.instance.objectNode();
        elementNode.set("id", StringNode.valueOf("42"));
        elementNode.set("type", StringNode.valueOf("service-call"));
        ObjectNode properties = elementNode.withObjectProperty("properties");
        ObjectNode validationEntry = JsonNodeFactory.instance.objectNode();
        validationEntry.put("schema", "alreadyCorrect");
        properties.withArrayProperty("afterValidation").add(validationEntry);

        ObjectNode chainNode = JsonNodeFactory.instance.objectNode();
        chainNode.withObject("content").withArrayProperty("elements").add(elementNode);

        chainNode = migration.makeMigration(chainNode);

        JsonNode entry = chainNode.get("content").get("elements").get(0)
                .get("properties").get("afterValidation").get(0);
        assertEquals("alreadyCorrect", entry.get("schema").asText());
        assertFalse(entry.has("scheme"));
    }
}
