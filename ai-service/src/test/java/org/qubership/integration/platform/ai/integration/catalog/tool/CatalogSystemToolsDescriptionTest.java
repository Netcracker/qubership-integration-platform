package org.qubership.integration.platform.ai.integration.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.Tool;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class CatalogSystemToolsDescriptionTest {

  @Test
  void listingDescriptionsUseStoredInteractionIdentity() {
    for (Method method : CatalogSystemTools.class.getDeclaredMethods()) {
      Tool annotation = method.getAnnotation(Tool.class);
      if (annotation == null || "describeBoundOperation".equals(method.getName())) {
        continue;
      }
      String description = String.join("\n", annotation.value());
      assertTrue(description.contains("interactionId"), method.getName() + ": " + description);
      assertTrue(description.contains("RequirementFlow"), method.getName() + ": " + description);
      assertFalse(description.contains("serviceCallId"), method.getName() + ": " + description);
      assertFalse(description.contains("SERVICE_CALL binding"), method.getName() + ": " + description);
    }

    assertTrue(CatalogSystemReadTool.LISTING_IS_NOT_A_BINDING.contains("interactionId"));
    assertFalse(CatalogSystemReadTool.LISTING_IS_NOT_A_BINDING.contains("serviceCallId"));
  }

  @Test
  void searchCatalogSystemsWaitsForStoredFlow() throws Exception {
    Tool annotation =
        CatalogSystemTools.class
            .getDeclaredMethod("searchCatalogSystems", String.class)
            .getAnnotation(Tool.class);
    String description = String.join("\n", annotation.value());
    assertTrue(description.contains("Capture RequirementFlow first"), description);
    assertTrue(description.contains("After the flow is stored"), description);
    assertFalse(description.contains("Call FIRST"), description);
  }
}
