package org.qubership.integration.platform.ai.catalog.descriptor;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CatalogDescriptorPatchValidatorTest {

  @Inject CatalogDescriptorResourceLoader catalogDescriptorResourceLoader;

  @Inject CatalogDescriptorPatchValidator catalogDescriptorPatchValidator;

  @Test
  void serviceCallMissingOperationTabFailsLikeRuntimeCatalog() {
    var desc = catalogDescriptorResourceLoader.load("service-call").orElseThrow();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("retryCount", 0);
    props.put("retryDelay", 5000);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> catalogDescriptorPatchValidator.validateAndThrow("service-call", desc, props));
    assertTrue(ex.getMessage().contains("Operation"), ex.getMessage());
  }

  @Test
  void httpTriggerMissingEndpointBranchFailsLikeRuntimeCatalog() {
    var desc = catalogDescriptorResourceLoader.load("http-trigger").orElseThrow();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("accessControlType", "NONE");
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> catalogDescriptorPatchValidator.validateAndThrow("http-trigger", desc, props));
    assertTrue(ex.getMessage().contains("Endpoint"), ex.getMessage());
    assertTrue(ex.getMessage().contains(CatalogDescriptorPatchValidator.SOURCE), ex.getMessage());
  }

  @Test
  void httpTriggerContextPathSatisfiesEndpointTab() {
    var desc = catalogDescriptorResourceLoader.load("http-trigger").orElseThrow();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("accessControlType", "NONE");
    props.put("contextPath", "/api/demo");
    assertDoesNotThrow(
        () -> catalogDescriptorPatchValidator.validateAndThrow("http-trigger", desc, props));
  }

  @Test
  void httpTriggerIntegrationOperationPathSatisfiesEndpointTab() {
    var desc = catalogDescriptorResourceLoader.load("http-trigger").orElseThrow();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("accessControlType", "NONE");
    props.put("integrationOperationPath", "/ops/hello");
    assertDoesNotThrow(
        () -> catalogDescriptorPatchValidator.validateAndThrow("http-trigger", desc, props));
  }

  @Test
  void handleValidationActionTitleInsteadOfConstFailsAllowedValues() {
    var desc = catalogDescriptorResourceLoader.load("http-trigger").orElseThrow();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("accessControlType", "NONE");
    props.put("contextPath", "/x");
    props.put("handleValidationAction", "Default");
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> catalogDescriptorPatchValidator.validateAndThrow("http-trigger", desc, props));
    assertTrue(ex.getMessage().contains("allowedValues"), ex.getMessage());
  }
}
