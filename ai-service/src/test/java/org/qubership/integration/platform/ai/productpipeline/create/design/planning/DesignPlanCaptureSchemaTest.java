package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DesignPlanCaptureSchemaTest {

  private static final String METHOD = "captureDesignPlan";

  @Inject DesignPlanCaptureTool bean;

  @Test
  void generatedSchemaExposesOnlyModelOwnedTypedFields() {
    assertNotNull(bean);
    Map<String, JsonSchemaElement> fields = fields(parameters());

    for (String required :
        List.of(
            "steps",
            "stepId",
            "summary",
            "owner",
            "kind",
            "id",
            "claims",
            "targetKind",
            "targetId",
            "role",
            "dependsOnStepIds")) {
      assertTrue(fields.containsKey(required), required + " is missing from the tool schema");
    }
    for (String serverOwned :
        List.of(
            "apiRelease",
            "schemaVersion",
            "contractId",
            "semanticRevisionId",
            "semanticRevisionHash")) {
      assertFalse(fields.containsKey(serverOwned), serverOwned + " leaked into the tool schema");
    }
    assertEquals(List.of("SKILL", "APIHUB_TOOL"), enumValues(fields.get("kind")));
    assertEquals(List.of("PRODUCER", "REFERENCE"), enumValues(fields.get("role")));
    assertEquals(
        Set.of(
            "ENTRY_POINT",
            "SERVICE_CALL",
            "MAPPING_INTENT",
            "REGION",
            "BEHAVIOR_NODE",
            "CATALOG_BINDING"),
        new LinkedHashSet<>(enumValues(fields.get("targetKind"))));
  }

  private static List<String> enumValues(JsonSchemaElement element) {
    return ((JsonEnumSchema) element).enumValues();
  }

  private static Map<String, JsonSchemaElement> fields(JsonSchemaElement root) {
    Map<String, JsonSchemaElement> fields = new LinkedHashMap<>();
    collect(root, fields);
    return fields;
  }

  private static void collect(
      JsonSchemaElement element, Map<String, JsonSchemaElement> fields) {
    if (element instanceof JsonObjectSchema object) {
      object
          .properties()
          .forEach(
              (name, child) -> {
                fields.put(name, child);
                collect(child, fields);
              });
    } else if (element instanceof JsonArraySchema array) {
      collect(array.items(), fields);
    }
  }

  private static JsonObjectSchema parameters() {
    List<ToolMethodCreateInfo> methods =
        ToolsRecorder.getMetadata().get(DesignPlanCaptureTool.class.getName());
    if (methods == null) {
      throw new IllegalStateException(
          "No generated tool metadata for " + DesignPlanCaptureTool.class.getName());
    }
    return methods.stream()
        .filter(method -> METHOD.equals(method.methodName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No generated tool metadata for " + METHOD))
        .toolSpecification()
        .parameters();
  }
}
