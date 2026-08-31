package org.qubership.integration.platform.ai.plan.mapping.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;

class JsonSchemaMappingContractFactoryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void objectPropertiesBecomeKnownFields() throws Exception {
    JsonNode schema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "orderId": { "type": "string" } },
              "required": ["orderId"]
            }
            """);
    MappingContract contract = JsonSchemaMappingContractFactory.from(schema);
    assertTrue(contract.known());
    MappingContract.Field field = contract.field("$.orderId").orElseThrow();
    assertEquals("string", field.type());
    assertTrue(field.required());
  }

  @Test
  void nestedObjectUsesDotPath() throws Exception {
    JsonNode schema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": {
                "customer": {
                  "type": "object",
                  "properties": { "name": { "type": "string" } }
                }
              }
            }
            """);
    MappingContract contract = JsonSchemaMappingContractFactory.from(schema);
    assertTrue(contract.field("$.customer.name").isPresent());
  }

  @Test
  void nullInputIsUnknownContract() {
    assertFalse(JsonSchemaMappingContractFactory.from(null).known());
  }

  @Test
  void objectWithoutPropertiesIsKnownWithNoFields() throws Exception {
    JsonNode schema = MAPPER.readTree("{\"type\": \"object\"}");
    MappingContract contract = JsonSchemaMappingContractFactory.from(schema);
    assertTrue(contract.known());
    assertTrue(contract.field("$.anything").isEmpty());
  }

  @Test
  void oneOfObjectVariantsExposeUnionFields() throws Exception {
    JsonNode schema =
        MAPPER.readTree(
            """
            {
              "oneOf": [
                {
                  "type": "object",
                  "properties": { "taskId": { "type": "string" }, "orderId": { "type": "string" } },
                  "required": ["taskId", "orderId"]
                },
                {
                  "type": "object",
                  "properties": { "taskId": { "type": "string" }, "executionId": { "type": "string" } },
                  "required": ["taskId"]
                }
              ]
            }
            """);
    MappingContract contract = JsonSchemaMappingContractFactory.from(schema);
    assertTrue(contract.known());
    assertTrue(contract.field("$.taskId").isPresent());
    assertTrue(contract.field("$.orderId").isPresent());
    assertTrue(contract.field("$.executionId").isPresent());
    assertTrue(contract.field("$.taskId").orElseThrow().required());
    assertFalse(contract.field("$.orderId").orElseThrow().required());
  }

  @Test
  void inDocumentRefViaDefinitionsResolvesProperties() throws Exception {
    JsonNode schema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": {
                "orderId": { "$ref": "#/definitions/OrderId" }
              },
              "required": ["orderId"],
              "definitions": {
                "OrderId": { "type": "string" }
              }
            }
            """);
    MappingContract contract = JsonSchemaMappingContractFactory.from(schema);
    MappingContract.Field field = contract.field("$.orderId").orElseThrow();
    assertEquals("string", field.type());
    assertTrue(field.required());
  }

  @Test
  void fieldLookupAcceptsBareNamesUsedInCapturedBriefs() throws Exception {
    JsonNode schema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": {
                "Subject": { "type": "string" },
                "parameters": {
                  "type": "object",
                  "properties": { "salesforceTaskId": { "type": "string" } }
                }
              },
              "required": ["Subject"]
            }
            """);
    MappingContract contract = JsonSchemaMappingContractFactory.from(schema);
    assertTrue(contract.field("Subject").isPresent());
    assertTrue(contract.field("parameters.salesforceTaskId").isPresent());
    assertEquals("$.Subject", contract.field("Subject").orElseThrow().path());
  }
}
