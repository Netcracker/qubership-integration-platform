package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutorFactory;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

@QuarkusTest
class DesignPlanCaptureSchemaTest {

  private static final String METHOD = "captureDesignPlan";
  private static final String CONVERSATION = "plan-schema-boundary";

  @Inject DesignPlanCaptureTool bean;
  @Inject QuarkusToolExecutorFactory executorFactory;

  @AfterEach
  void cleanup() {
    DesignPlanCaptureSession.unbind(CONVERSATION);
    ToolSession.clear();
  }

  @Test
  void generatedSchemaExposesOnlyModelOwnedTypedFields() {
    assertNotNull(bean);
    Map<String, JsonSchemaElement> fields = fields(parameters());

    for (String required : List.of("notes", "targetKind", "targetId", "summary")) {
      assertTrue(fields.containsKey(required), required + " is missing from the tool schema");
    }
    for (String serverOwned :
        List.of(
            "apiRelease",
            "schemaVersion",
            "contractId",
            "semanticRevisionId",
            "semanticRevisionHash",
            "steps",
            "stepId",
            "owner",
            "claims",
            "dependsOnStepIds")) {
      assertFalse(fields.containsKey(serverOwned), serverOwned + " leaked into the tool schema");
    }
    assertEquals(
        Set.of(
            "ENTRY_POINT",
            "SERVICE_CALL",
            "MAPPING_INTENT",
            "REGION",
            "BEHAVIOR_NODE",
            "ELEMENT_NODE",
            "CATALOG_BINDING"),
        new LinkedHashSet<>(enumValues(fields.get("targetKind"))));
  }

  @Test
  void generatedBoundaryAcceptsSmallCaptureAndRejectsServerOwnedFields() {
    bind();
    assertTrue(execute("""
        {"capture":{"steps":[]}}
        """).contains("UNEXPECTED_FIELD"));
    assertTrue(DesignPlanCaptureSession.binding(CONVERSATION)
        .orElseThrow().candidate().get() == null);

    String accepted = execute("""
        {"capture":{"notes":[]}}
        """);
    assertTrue(accepted.contains("HANDOFF"), accepted);
    assertNotNull(DesignPlanCaptureSession.binding(CONVERSATION)
        .orElseThrow().candidate().get());
  }

  @Test
  void generatedBoundaryRejectsDuplicateKeysAndWrongTypes() {
    bind();
    assertTrue(execute("""
        {"capture":{"notes":[],"notes":[]}}
        """).contains("DUPLICATE_JSON_KEY"));
    assertTrue(execute("""
        {"capture":{"notes":"text"}}
        """).contains("INVALID_TYPE"));
    assertTrue(DesignPlanCaptureSession.binding(CONVERSATION)
        .orElseThrow().candidate().get() == null);
  }

  private void bind() {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DesignPlanCaptureSession.bind(CONVERSATION, revision, "revision-hash", "2026.1",
        DesignPlanTestFixtures.brief(), DesignPlanTestFixtures.planningPin(revision));
    ToolSession.bind(CONVERSATION);
  }

  private String execute(String arguments) {
    ToolMethodCreateInfo method = info();
    QuarkusToolExecutor executor = executorFactory.create(new QuarkusToolExecutor.Context(
        bean, method.invokerClassName(), method.methodName(),
        method.argumentMapperClassName(), method.executionModel(), method.returnBehavior(),
        false, method));
    return executor.execute(ToolExecutionRequest.builder().id("call-1")
        .name(method.toolSpecification().name()).arguments(arguments).build(), CONVERSATION);
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
    return info().toolSpecification().parameters();
  }

  private static ToolMethodCreateInfo info() {
    List<ToolMethodCreateInfo> methods =
        ToolsRecorder.getMetadata().get(DesignPlanCaptureTool.class.getName());
    if (methods == null) {
      throw new IllegalStateException(
          "No generated tool metadata for " + DesignPlanCaptureTool.class.getName());
    }
    return methods.stream()
        .filter(method -> METHOD.equals(method.methodName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No generated tool metadata for " + METHOD));
  }
}
