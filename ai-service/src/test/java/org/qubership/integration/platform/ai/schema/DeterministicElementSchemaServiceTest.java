package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeterministicElementSchemaServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private DeterministicElementSchemaService service;

  @BeforeEach
  void setUp() {
    SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();
    QipSchemaYamlParser qipSchemaYamlParser = new QipSchemaYamlParser();
    SchemaRefResolver schemaRefResolver =
        new SchemaRefResolver(schemaResourceLoader, qipSchemaYamlParser);

    service = DeterministicElementSchemaService.createForUnitTests(objectMapper);
  }

  @Test
  void describeElementPatchSchemaExposesHttpTriggerRootOneOfRequired() throws Exception {
    // Prove describeElementPatchSchema tells the truth for http-trigger Endpoint oneOf:
    // unconditional required stays on accessControlType; branch required lives in alternatives.
    String schemaJson = service.describeElementPatchSchema("http-trigger");
    JsonNode root = objectMapper.readTree(schemaJson);

    JsonNode requiredProperties = root.path("requiredProperties");
    JsonNode unconditionalRequired = root.path("unconditionalRequiredProperties");
    assertTrue(requiredProperties.isArray(), schemaJson);
    assertTrue(unconditionalRequired.isArray(), schemaJson);
    assertEquals(requiredProperties, unconditionalRequired, schemaJson);
    assertTrue(
        arrayContains(requiredProperties, "accessControlType"),
        "unconditional required must keep accessControlType: " + schemaJson);
    assertTrue(
        arrayContains(unconditionalRequired, "accessControlType"),
        "unconditionalRequiredProperties must keep accessControlType: " + schemaJson);

    JsonNode alternatives = root.path("rootOneOfAlternatives");
    assertTrue(alternatives.isArray(), schemaJson);
    assertTrue(alternatives.size() >= 2, "rootOneOfAlternatives must be non-empty: " + schemaJson);

    JsonNode custom = findAlternative(alternatives, "Custom", "CustomEndpoint");
    assertTrue(custom != null && !custom.isMissingNode(), "Custom branch missing: " + schemaJson);
    assertTrue(requiredContains(custom, "contextPath"), "Custom requires contextPath: " + custom);

    JsonNode implemented =
        findAlternative(alternatives, "Implemented Service", "ImplementedServiceEndpoint");
    assertTrue(
        implemented != null && !implemented.isMissingNode(),
        "Implemented Service branch missing: " + schemaJson);
    assertTrue(
        requiredContains(implemented, "integrationOperationId"),
        "Implemented Service requires integrationOperationId: " + implemented);
  }

  @Test
  void coercesHttpTriggerBooleanPropertyBeforeValidation() throws Exception {
    Object externalRoute =
        service.coercePatchPropertyValue("http-trigger", "externalRoute", "true");
    Map<String, Object> patch =
        Map.of(
            "properties",
            Map.of(
                "contextPath",
                "/greetings",
                "httpMethodRestrict",
                "GET",
                "accessControlType",
                "NONE",
                "externalRoute",
                externalRoute));

    String validationJson =
        service.validateElementPatch("http-trigger", objectMapper.writeValueAsString(patch));

    assertEquals(Boolean.TRUE, externalRoute);
    JsonNode root = objectMapper.readTree(validationJson);
    assertTrue(root.path("valid").asBoolean(), validationJson);
  }

  @Test
  void coercesHttpTriggerRolesJsonArrayBeforeValidation() throws Exception {
    Object roles =
        service.coercePatchPropertyValue("http-trigger", "roles", "[\"qip-viewer\",\"admin\"]");
    assertInstanceOf(List.class, roles);

    Map<String, Object> patch =
        Map.of(
            "properties",
            Map.of(
                "contextPath",
                "/greetings",
                "httpMethodRestrict",
                "GET",
                "accessControlType",
                "RBAC",
                "externalRoute",
                true,
                "roles",
                roles));

    String validationJson =
        service.validateElementPatch("http-trigger", objectMapper.writeValueAsString(patch));

    JsonNode root = objectMapper.readTree(validationJson);
    assertTrue(root.path("valid").asBoolean(), validationJson);
  }

  @Test
  void validatesSecureHelloStyleRbacPatch() throws Exception {
    Object roles =
        service.coercePatchPropertyValue("http-trigger", "roles", "[\"qip-viewer\"]");
    Object externalRoute =
        service.coercePatchPropertyValue("http-trigger", "externalRoute", "true");
    Map<String, Object> patch =
        Map.of(
            "properties",
            Map.of(
                "contextPath",
                "/secure-hello",
                "httpMethodRestrict",
                "GET",
                "externalRoute",
                externalRoute,
                "accessControlType",
                "RBAC",
                "roles",
                roles));

    String validationJson =
        service.validateElementPatch("http-trigger", objectMapper.writeValueAsString(patch));

    JsonNode root = objectMapper.readTree(validationJson);
    assertTrue(root.path("valid").asBoolean(), validationJson);
  }

  @Test
  void validatesSecureHelloStyleRbacPatchWithElementName() throws Exception {
    Object roles =
        service.coercePatchPropertyValue("http-trigger", "roles", "[\"qip-viewer\"]");
    Object externalRoute =
        service.coercePatchPropertyValue("http-trigger", "externalRoute", "true");
    Map<String, Object> patch =
        Map.of(
            "name",
            "HTTP Trigger",
            "properties",
            Map.of(
                "contextPath",
                "/secure-hello",
                "httpMethodRestrict",
                "GET",
                "externalRoute",
                externalRoute,
                "accessControlType",
                "RBAC",
                "roles",
                roles));

    String validationJson =
        service.validateElementPatch("http-trigger", objectMapper.writeValueAsString(patch));

    JsonNode root = objectMapper.readTree(validationJson);
    assertTrue(root.path("valid").asBoolean(), validationJson);
  }

  @Test
  void coercesHttpTriggerAbacParametersJsonObjectBeforeValidation() throws Exception {
    String abacJson =
        "{\"resourceType\":\"CHAIN\",\"operation\":\"ALL\",\"resourceDataType\":\"String\",\"resourceString\":\"/cip-routes/demo\"}";
    Object abacParameters =
        service.coercePatchPropertyValue("http-trigger", "abacParameters", abacJson);
    assertInstanceOf(Map.class, abacParameters);

    Map<String, Object> patch =
        Map.of(
            "properties",
            Map.of(
                "contextPath",
                "/greetings",
                "httpMethodRestrict",
                "GET",
                "accessControlType",
                "ABAC",
                "externalRoute",
                true,
                "abacParameters",
                abacParameters));

    String validationJson =
        service.validateElementPatch("http-trigger", objectMapper.writeValueAsString(patch));

    JsonNode root = objectMapper.readTree(validationJson);
    assertTrue(root.path("valid").asBoolean(), validationJson);
  }

  @Test
  void rejectsHttpMethodRestrictArrayAtCaptureBoundary() {
    ArrayNode methodArray = objectMapper.createArrayNode().add("GET");

    assertTrue(
        service
            .validateCapturePropertyValue("http-trigger", "httpMethodRestrict", methodArray)
            .isPresent());
  }

  @Test
  void acceptsHttpMethodRestrictCatalogObjectAtCaptureBoundary() throws Exception {
    JsonNode catalogObject = objectMapper.readTree("{\"httpMethods\":[\"GET\"]}");

    assertTrue(
        service
            .validateCapturePropertyValue("http-trigger", "httpMethodRestrict", catalogObject)
            .isEmpty());
  }

  @Test
  void acceptsRolesArrayAtCaptureBoundary() {
    ArrayNode roles = objectMapper.createArrayNode().add("qip-viewer");

    assertTrue(
        service.validateCapturePropertyValue("http-trigger", "roles", roles).isEmpty());
  }

  private static JsonNode findAlternative(JsonNode alternatives, String title, String name) {
    for (JsonNode alt : alternatives) {
      if (title.equals(alt.path("title").asText(null))
          || name.equals(alt.path("name").asText(null))) {
        return alt;
      }
    }
    return null;
  }

  private static boolean requiredContains(JsonNode alternative, String property) {
    return arrayContains(alternative.path("required"), property);
  }

  private static boolean arrayContains(JsonNode array, String value) {
    if (!array.isArray()) {
      return false;
    }
    for (JsonNode item : array) {
      if (value.equals(item.asText())) {
        return true;
      }
    }
    return false;
  }
}
