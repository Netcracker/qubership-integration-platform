package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RequirementDraftCaptureBoundaryTest {

  private static final String METHOD = "captureRequirementDraft";

  @Test
  void generatedSchemaDoesNotOfferTopLevelCatalogBinding() {
    JsonObjectSchema parameters = createInfo().toolSpecification().parameters();
    JsonObjectSchema capture =
        (JsonObjectSchema) parameters.properties().values().iterator().next();
    assertFalse(
        capture.properties().containsKey("catalogBinding"),
        "catalogBinding must not be a top-level capture property: " + capture.properties().keySet());
  }

  private static ToolMethodCreateInfo createInfo() {
    List<ToolMethodCreateInfo> infos =
        ToolsRecorder.getMetadata().get(RequirementDraftTool.class.getName());
    if (infos == null) {
      throw new IllegalStateException(
          "No generated tool metadata for " + RequirementDraftTool.class.getName());
    }
    return infos.stream()
        .filter(info -> METHOD.equals(info.methodName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No generated tool metadata for " + METHOD));
  }
}
