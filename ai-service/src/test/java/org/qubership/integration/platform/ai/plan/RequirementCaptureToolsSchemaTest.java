package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RequirementCaptureToolsSchemaTest {

  @Test
  void offersOnlyTheFourRequirementTools() {
    List<ToolMethodCreateInfo> tools = ToolsRecorder.getMetadata()
        .get(RequirementCaptureTools.class.getName());
    assertNotNull(tools);
    assertEquals(Set.of("captureRequirementDraft", "updateRequirementDraft",
        "readRequirementDraft", "finishRequirementDiscoveryTurn"),
        tools.stream().map(ToolMethodCreateInfo::methodName).collect(java.util.stream.Collectors.toSet()));
    JsonObjectSchema capture = tools.stream()
        .filter(info -> "captureRequirementDraft".equals(info.methodName()))
        .findFirst().orElseThrow().toolSpecification().parameters();
    assertTrue(capture.properties().containsKey("draft"));
  }
}
