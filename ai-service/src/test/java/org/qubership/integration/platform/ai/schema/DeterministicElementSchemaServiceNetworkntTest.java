package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(NetworkntValidationTestProfile.class)
class DeterministicElementSchemaServiceNetworkntTest {

  @Inject DeterministicElementSchemaService deterministicElementSchemaService;

  @Inject ElementPatchValidationRouter elementPatchValidationRouter;

  @Inject ObjectMapper objectMapper;

  @Test
  void routerUsesNetworkntForPilotTypes() {
    assertTrue(elementPatchValidationRouter.useNetworknt("service-call"));
    assertTrue(elementPatchValidationRouter.useNetworknt("http-trigger"));
    assertFalse(elementPatchValidationRouter.useNetworknt("script"));
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
  void validateElementPatchScriptUsesLegacyEngine() throws Exception {
    String patch = "{\"properties\":{\"script\":\"// noop\"}}";
    String json = deterministicElementSchemaService.validateElementPatch("script", patch);
    JsonNode root = objectMapper.readTree(json);
    assertNull(root.get("error"), json);
    assertFalse(root.get("errors").toString().contains("Unknown property"), json);
  }
}
