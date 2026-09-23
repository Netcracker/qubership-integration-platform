package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
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
    Set<String> fields = new java.util.LinkedHashSet<>();
    collect(capture, capture.definitions(), fields);
    assertTrue(fields.containsAll(Set.of("fieldMapping", "sourceInteractionId",
        "sourcePath", "targetInteractionId", "targetPath", "expression")),
        fields.toString());
  }

  private static void collect(JsonSchemaElement schema,
      java.util.Map<String, JsonSchemaElement> definitions, Set<String> fields) {
    if (schema instanceof JsonReferenceSchema reference) {
      collect(definitions.get(reference.reference()), definitions, fields);
    } else if (schema instanceof JsonObjectSchema object) {
      object.properties().forEach((name, child) -> {
        fields.add(name);
        collect(child, definitions, fields);
      });
    } else if (schema instanceof JsonArraySchema array) {
      collect(array.items(), definitions, fields);
    }
  }
}
