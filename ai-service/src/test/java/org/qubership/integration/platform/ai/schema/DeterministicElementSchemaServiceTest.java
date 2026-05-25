package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DeterministicElementSchemaServiceTest {

  @Inject DeterministicElementSchemaService deterministicElementSchemaService;

  @Inject ObjectMapper objectMapper;

  @Test
  void describeElementPatchSchemaServiceCallContainsRetryAndAuthorization() throws Exception {
    String json = deterministicElementSchemaService.describeElementPatchSchema("service-call");
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    assertEquals("service-call", root.get("elementType").asText());
    JsonNode props = root.get("properties");
    assertNotNull(props);
    assertTrue(props.has("retryCount"), json);
    assertTrue(props.has("retryDelay"), json);
    assertTrue(props.has("integrationOperationSkipEmptyQueryParameters"), json);
    JsonNode auth = props.get("authorizationConfiguration");
    assertNotNull(auth);
    assertEquals("oneOf", auth.get("type").asText());
    assertTrue(auth.get("detailsAvailable").asBoolean());
  }

  @Test
  void describeElementPropertyAuthorizationConfigurationListsVariants() throws Exception {
    String json =
        deterministicElementSchemaService.describeElementProperty(
            "service-call", "authorizationConfiguration");
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    String blob = json.toLowerCase();
    assertTrue(blob.contains("inherit") || blob.contains("none") || blob.contains("basic"), json);
  }

  @Test
  void validateElementPatchValidMinimalProperties() throws Exception {
    String patch = "{\"properties\":{\"retryCount\":0,\"retryDelay\":5000}}";
    String json = deterministicElementSchemaService.validateElementPatch("service-call", patch);
    JsonNode root = objectMapper.readTree(json);
    assertTrue(root.get("valid").asBoolean(), json);
  }

  @Test
  void validateElementPatchServiceCallIdsOnlyReportsMissingOperationFields() throws Exception {
    String patch =
        "{\"properties\":{"
            + "\"integrationSystemId\":\"sys-1\","
            + "\"integrationOperationId\":\"op-1\","
            + "\"integrationOperationProtocolType\":\"http\""
            + "}}";
    String json = deterministicElementSchemaService.validateElementPatch("service-call", patch);
    JsonNode root = objectMapper.readTree(json);
    assertFalse(root.get("valid").asBoolean(), json);
    String errorsBlob = root.get("errors").toString();
    assertTrue(
        errorsBlob.contains("integrationOperationMethod")
            || errorsBlob.contains("integrationSpecificationId")
            || errorsBlob.contains("systemType"),
        json);
    String summary = ElementPatchValidationMessages.summarizeFailure(json, objectMapper);
    assertTrue(
        summary.contains("integrationOperationMethod")
            || summary.contains("integrationSpecificationId")
            || summary.contains("systemType")
            || summary.contains("missingRequired"),
        summary);
    assertFalse(
        summary.contains("does not match any allowed alternative"),
        "summary should name concrete fields, not generic oneOf text only");
  }

  @Test
  void validateElementPatchUnknownProperty() throws Exception {
    String patch = "{\"properties\":{\"retryCount\":0,\"retryDelay\":1,\"__unknown_prop__\":true}}";
    String json = deterministicElementSchemaService.validateElementPatch("service-call", patch);
    JsonNode root = objectMapper.readTree(json);
    assertFalse(root.get("valid").asBoolean());
    assertTrue(root.get("errors").toString().contains("__unknown_prop__"));
  }

  @Test
  void validateElementPatchServiceCallHttpConditionalPropertyNotMarkedUnknown() throws Exception {
    String patch =
        "{\"properties\":{\"retryCount\":0,\"retryDelay\":5000,"
            + "\"integrationOperationProtocolType\":\"http\","
            + "\"integrationOperationSkipEmptyQueryParameters\":false}}";
    String json = deterministicElementSchemaService.validateElementPatch("service-call", patch);
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    assertFalse(
        root.get("errors").toString().contains("integrationOperationSkipEmptyQueryParameters"),
        json);
    assertFalse(root.get("errors").toString().contains("Unknown property"), json);
  }

  @Test
  void validateElementPatchMissingRetryDelayFilledFromSchemaDefault() throws Exception {
    String patch = "{\"properties\":{\"retryCount\":0}}";
    String json = deterministicElementSchemaService.validateElementPatch("service-call", patch);
    JsonNode root = objectMapper.readTree(json);
    assertTrue(root.has("defaultsApplied"), json);
    assertTrue(root.get("defaultsApplied").toString().contains("retryDelay"), json);
    assertTrue(root.has("patchWithDefaults"), json);
    assertEquals(
        5000, root.path("patchWithDefaults").path("properties").path("retryDelay").asInt());
    assertFalse(root.get("missingRequired").toString().contains("retryDelay"), json);
  }

  @Test
  void validateElementPatchHttpTriggerFillsAccessControlDefault() throws Exception {
    String patch =
        "{\"properties\":{\"contextPath\":\"/supertest\",\"httpMethodRestrict\":\"PATCH\"}}";
    String json = deterministicElementSchemaService.validateElementPatch("http-trigger", patch);
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    assertTrue(root.has("defaultsApplied"), json);
    assertTrue(root.get("defaultsApplied").toString().contains("accessControlType"), json);
    assertEquals(
        "NONE",
        root.path("patchWithDefaults").path("properties").path("accessControlType").asText(),
        json);
  }

  @Test
  void describeElementPatchSchemaScriptContainsPropertiesFromRefFragment() throws Exception {
    String json = deterministicElementSchemaService.describeElementPatchSchema("script");
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    JsonNode props = root.get("properties");
    assertNotNull(props);
    assertTrue(props.has("script"), json);
    assertTrue(props.has("exportFileExtension"), json);
  }

  @Test
  void validateElementPatchScriptScriptPropertyNotUnknown() throws Exception {
    String patch = "{\"properties\":{\"script\":\"// noop\"}}";
    String json = deterministicElementSchemaService.validateElementPatch("script", patch);
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    assertFalse(root.get("errors").toString().contains("Unknown property"), json);
  }

  @Test
  void validateElementPatchHttpTriggerCustomEndpointKeysNotUnknown() throws Exception {
    String patch =
        "{\"properties\":{\"contextPath\":\"/api/test\",\"httpMethodRestrict\":\"GET\",\"accessControlType\":\"NONE\"}}";
    String json = deterministicElementSchemaService.validateElementPatch("http-trigger", patch);
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    assertTrue(root.path("valid").asBoolean(), json);
    assertFalse(root.get("errors").toString().contains("Unknown property"), json);
  }

  @Test
  void validateElementPatchRootNotObject() throws Exception {
    String json = deterministicElementSchemaService.validateElementPatch("service-call", "[1,2]");
    JsonNode root = objectMapper.readTree(json);
    assertFalse(root.get("valid").asBoolean());
  }
}
