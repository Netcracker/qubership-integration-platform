package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.CatalogToolResultTestSupport;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ElementSchemaToolsTest {

  @Inject ElementSchemaTools elementSchemaTools;

  @Inject ObjectMapper objectMapper;

  @Test
  void describeElementPatchSchemaReturnsJson() throws Exception {
    String out = elementSchemaTools.describeElementPatchSchema("service-call");
    JsonNode root = CatalogToolResultTestSupport.requireSuccess(objectMapper, out);
    JsonNode data = root.get("data");
    assertNotNull(data);
    assertEquals("service-call", data.get("elementType").asText());
  }

  @Test
  void describeElementPropertyReturnsJson() throws Exception {
    String out = elementSchemaTools.describeElementProperty("service-call", "retryCount");
    JsonNode root = CatalogToolResultTestSupport.requireSuccess(objectMapper, out);
    JsonNode data = root.get("data");
    assertNotNull(data);
    assertTrue(data.has("elementType") || data.has("path") || data.size() > 0, out);
  }

  @Test
  void getElementSchemaDocumentationReturnsEnvelope() throws Exception {
    String out = elementSchemaTools.getElementSchemaDocumentation("service-call");
    JsonNode root = objectMapper.readTree(out);
    assertTrue(root.has("ok"));
    assertEquals("getElementSchemaDocumentation", root.get("tool").asText());
    if (root.path("ok").asBoolean()) {
      assertTrue(root.get("data").get("documentation").isTextual());
    } else {
      assertTrue(root.has("error"));
    }
  }
}
