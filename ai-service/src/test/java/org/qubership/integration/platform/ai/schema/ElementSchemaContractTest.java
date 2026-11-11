package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ElementSchemaContractTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final DeterministicElementSchemaService schemas =
      DeterministicElementSchemaService.createForUnitTests(mapper);

  @ParameterizedTest
  @ValueSource(strings = {"http-trigger", "http-sender", "graphql-sender", "service-call"})
  void preservesReferencedConditionalProperties(String type) throws Exception {
    var model = schemas.elementPropertiesSchemaModel(type);
    assertTrue(model.propertyDefs().keySet().containsAll(
        Set.of("correlationIdName", "correlationIdPosition")), type);
    assertTrue(model.requiredKeysFor(Map.of("receiveCorrelationId", "true")).containsAll(
        Set.of("correlationIdName", "correlationIdPosition")), type);
    assertFalse(model.requiredKeysFor(Map.of("receiveCorrelationId", "false"))
        .contains("correlationIdName"), type);
    assertTrue(mapper.readTree(schemas.describeElementPatchSchema(type))
        .path("properties").has("correlationIdPosition"), type);
  }

  @ParameterizedTest
  @CsvSource({
      "context-storage, ttl, 0, false",
      "context-storage, ttl, 1, true",
      "mcp-trigger, name, bad tool name, false",
      "mcp-trigger, name, valid_tool, true",
      "chain-call-2, timeout, banana, false",
      "chain-call-2, timeout, -1, false",
      "chain-call-2, timeout, 30000, true",
      "chain-call-2, timeout, #{timeout}, true",
      "rabbitmq-trigger-2, acknowledgeMode, INVALID, false",
      "rabbitmq-trigger-2, acknowledgeMode, AUTO, true",
      "http-trigger, idempotency, {\"enabled\":true}, false",
      "http-trigger, idempotency, {\"enabled\":false}, true"
  })
  void validatesCaptureValuesAgainstTheSharedConstraints(
      String type, String key, String raw, boolean accepted) {
    Object value = schemas.coercePatchPropertyValue(type, key, raw);
    var error = schemas.validateCapturePropertyValue(type, key, mapper.valueToTree(value));
    assertEquals(accepted, error.isEmpty(), () -> type + "." + key + ": " + error);
  }

  @Test
  void validatesCompleteCorrelationWithoutRejectingItsFields() throws Exception {
    var properties = mapper.createObjectNode().put("httpMethod", "GET")
        .put("uri", "https://example.invalid").put("receiveCorrelationId", true);
    assertFalse(patch("http-sender", properties).path("valid").asBoolean());
    properties.put("correlationIdName", "X-Correlation-ID").put("correlationIdPosition", "header");
    var result = patch("http-sender", properties);
    assertTrue(result.path("valid").asBoolean(), result.toString());
  }

  @Test
  void exposesRuntimePropertiesWithoutEditorControls() {
    assertTrue(schemas.allowedPatchPropertyKeys("chain-call-2")
        .containsAll(Set.of("block", "failIfNoConsumers")));
    assertFalse(schemas.allowedPatchPropertyKeys("chain-call-2").contains("chainTriggerReference"));
    assertTrue(schemas.allowedPatchPropertyKeys("context-storage").contains("overrideContextParams"));
    assertTrue(schemas.allowedPatchPropertyKeys("rabbitmq-trigger-2")
        .containsAll(Set.of("vhost", "sslProtocol")));
    assertTrue(schemas.allowedPatchPropertyKeys("rabbitmq-sender-2").contains("vhost"));
  }

  @Test
  void keepsChainCallFlagsFixed() {
    assertTrue(schemas.validateCapturePropertyValue("chain-call-2", "block", mapper.valueToTree(true)).isEmpty());
    assertTrue(schemas.validateCapturePropertyValue("chain-call-2", "block", mapper.valueToTree(false)).isPresent());
    assertTrue(schemas.validateCapturePropertyValue(
        "chain-call-2", "failIfNoConsumers", mapper.valueToTree(false)).isEmpty());
    assertTrue(schemas.validateCapturePropertyValue(
        "chain-call-2", "failIfNoConsumers", mapper.valueToTree(true)).isPresent());
  }

  @Test
  void requiresTimeoutForTheExplicitNoConsumerFlag() throws Exception {
    var properties = mapper.createObjectNode().put("elementId", "resolved-target").put("block", true);
    assertTrue(patch("chain-call-2", properties).path("valid").asBoolean());
    properties.put("failIfNoConsumers", false);
    assertFalse(patch("chain-call-2", properties).path("valid").asBoolean());
    properties.put("timeout", 30000);
    assertTrue(patch("chain-call-2", properties).path("valid").asBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"scs-sender", "sds-trigger"})
  void doesNotPromiseCreateSupportWithoutARuntimeDescriptor(String type) {
    assertEquals(ChainElementFamilies.BindingMode.UNSUPPORTED_IN_CREATE, ChainElementFamilies.bindingMode(type));
  }

  @Test
  void validatesNestedConditionalsAndDistinctOneOfBranches() throws Exception {
    var properties = mapper.createObjectNode().put("contextServiceId", "context")
        .put("useCorrelationId", true).put("operation", "GET").put("target", "HEADER");
    assertFalse(patch("context-storage", properties).path("valid").asBoolean());
    properties.put("targetName", "X-Context");
    assertTrue(patch("context-storage", properties).path("valid").asBoolean());
    assertTrue(schemas.validateCapturePropertyValue("service-call", "integrationSystemId",
        mapper.valueToTree("system-id")).isEmpty());
    var hint = mapper.readTree(schemas.describeElementPatchSchema("service-call"))
        .path("properties").path("integrationSystemId");
    assertEquals("string", hint.path("type").asText(), hint.toString());
  }

  @Test
  void reportsNestedValidationPathsInThePatchFormat() throws Exception {
    var result = patch("http-trigger", mapper.createObjectNode().put("httpMethod", "GET")
        .put("contextPath", "/orders").set("idempotency",
            mapper.readTree("{\"enabled\":true,\"keyExpiry\":false}")));
    assertTrue(result.path("errors").findValuesAsText("path").stream()
        .anyMatch("properties.idempotency.keyExpiry"::equals), result.toString());
  }

  @Test
  void keepsHintsAndStructuredValuesAcrossPropertyVariants() throws Exception {
    var hint = mapper.readTree(schemas.describeElementProperty("http-trigger", "httpMethodRestrict"))
        .path("schema");
    assertEquals("anyOf", hint.path("type").asText(), hint.toString());
    assertEquals(2, hint.path("variants").size(), hint.toString());
    var methods = mapper.readTree(schemas.describeElementProperty(
        "http-trigger", "httpMethodRestrict.httpMethods")).path("schema");
    assertEquals("array", methods.path("type").asText(), methods.toString());
    Object value = schemas.coercePatchPropertyValue(
        "http-trigger", "httpMethodRestrict", "{\"httpMethods\":[\"GET\"]}");
    assertEquals(Map.of("httpMethods", java.util.List.of("GET")), value);
    assertTrue(schemas.validateCapturePropertyValue(
        "http-trigger", "httpMethodRestrict", mapper.valueToTree(value)).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"jschLoggingLevel", "readLockLoggingLevel"})
  void keepsLoggingDefaultsAsStrings(String key) throws Exception {
    JsonNode property = mapper.readTree(schemas.describeElementProperty("sftp-trigger-2", key)).path("schema");
    assertEquals("OFF", property.path("default").asText(), property.toString());
    assertTrue(property.path("default").isTextual(), property.toString());
  }

  private JsonNode patch(String type, JsonNode properties) throws Exception {
    return mapper.readTree(schemas.validateElementPatch(type,
        mapper.writeValueAsString(Map.of("properties", properties))));
  }
}
