package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutorFactory;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Checks the generated product tool schema and the actual argument boundary. */
@QuarkusTest
class ProductRequirementBriefSchemaTest {

  private static final String CONVERSATION = "product-brief-schema-test";

  @Inject ProductRequirementBriefTool tool;
  @Inject QuarkusToolExecutorFactory executorFactory;
  @Inject CaptureSession captureSession;
  @Inject org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore feedback;

  @AfterEach
  void clear() {
    ProductCapabilityCaptureContext.unbind(CONVERSATION);
    feedback.clearPlan(CONVERSATION);
    captureSession.clear(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION));
  }

  @Test
  void schemaExposesOnlyModelOwnedBriefFields() {
    JsonObjectSchema root = info().toolSpecification().parameters();
    Set<String> fields = new LinkedHashSet<>();
    collect(root, root.definitions(), fields);

    assertTrue(fields.containsAll(Set.of("goal", "summary", "inputs", "assumptions",
        "citations", "mappingIntents", "sourceRef", "targetRef", "rules")), fields.toString());
    for (String owned : Set.of("facts", "constraints", "flow", "catalogBindings",
        "approvedDraftText", "approvedDraftReference", "mappingIntentId", "sourcePort",
        "targetPort", "status")) {
      assertFalse(fields.contains(owned), owned + " leaked into the product tool schema");
    }
  }

  @Test
  void generatedMapperAcceptsNarrowCapture() {
    bind();

    String result = execute("""
        {"capture":{"goal":"Greetings","summary":"Return Hello world!",
          "inputs":["GET /greetings"],"assumptions":[],"citations":[],"mappingIntents":[]}}
        """);

    assertTrue(result.contains("Requirement brief captured"), result);
    RequirementBrief brief = captured();
    assertEquals(RequirementFactFixtures.greetingsApprovedDraft().facts(), brief.facts());
  }

  @Test
  void rejectsServerOwnedFieldBeforePublishingCandidate() {
    bind();

    String result = execute("""
        {"capture":{"goal":"Greetings","summary":"Return Hello world!","facts":[]}}
        """);

    assertTrue(result.contains("UNEXPECTED_FIELD"), result);
    assertTrue(result.contains("/capture/facts"), result);
    assertFalse(result.contains("\"accepted\":true"), result);
    assertTrue(ProductCapabilityCaptureContext.binding(CONVERSATION)
        .orElseThrow().briefCandidate().get() == null);
  }

  @Test
  void rejectsDuplicateKeyAndWrongType() {
    bind();

    String duplicate = execute("""
        {"capture":{"goal":"Greetings","goal":"Other","summary":"Hello"}}
        """);
    String wrongType = execute("""
        {"capture":{"goal":"Greetings","inputs":"GET /greetings"}}
        """);

    assertTrue(duplicate.contains("DUPLICATE_JSON_KEY"), duplicate);
    assertTrue(wrongType.contains("INVALID_TYPE"), wrongType);
    assertTrue(captureSession.get(
        CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class).isEmpty());
  }

  @Test
  void rejectsMissingRequiredArrayBeforeRecordDefaultsHideIt() {
    bind();
    String result = execute("""
        {"capture":{"goal":"Greetings","summary":"Return Hello", "inputs":["GET /greetings"],
          "citations":[]}}
        """);
    assertTrue(result.contains("/capture/assumptions"), result);
    assertTrue(ProductCapabilityCaptureContext.binding(CONVERSATION)
        .orElseThrow().briefCandidate().get() == null);
  }

  @Test
  void lateNativeCallsCannotChangeTheNewAttempt() {
    bind();
    var oldBinding = ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow();
    var oldParameters = ProductCapabilityCaptureContext.invocationParameters(oldBinding);
    bind();
    var current = ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow();
    feedback.recordPlanToolArgumentsFailure(CONVERSATION, "current attempt feedback");
    var before = feedback.lastPlanFailure(CONVERSATION).orElseThrow();
    String malformed = execute("{broken", oldParameters);
    String valid = execute("""
        {"capture":{"goal":"Obsolete","summary":"Obsolete","inputs":["GET"],
          "assumptions":[],"citations":[]}}
        """, oldParameters);
    ProductCapabilityCaptureContext.unbind(oldBinding);
    assertTrue(malformed.contains("inactive attempt"), malformed);
    assertTrue(valid.contains("inactive attempt"), valid);
    assertEquals(before, feedback.lastPlanFailure(CONVERSATION).orElseThrow());
    assertEquals(current, ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow());
    assertTrue(current.briefCandidate().get() == null);
    assertTrue(captureSession.get(CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class).isEmpty());
  }

  @Test
  void publishedSchemaMakesMappingIntentsOptional() {
    var root = ProductRequirementBriefTool.specification(info()).parameters();
    var schema = root.properties().get("capture");
    if (schema instanceof JsonReferenceSchema ref) {
      schema = root.definitions().get(ref.reference());
    }
    var capture = (JsonObjectSchema) schema;
    assertFalse(capture.required().contains("mappingIntents"), capture.toString());
    assertTrue(capture.required().containsAll(List.of("goal", "summary", "inputs", "assumptions", "citations")),
        capture.toString());
  }

  private void bind() {
    ProductCapabilityCaptureContext.bindAnalysis("run", CONVERSATION,
        RequirementFactFixtures.greetingsApprovedDraft(), payload -> {});
  }

  private RequirementBrief captured() {
    return captureSession.get(
        CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, CONVERSATION),
        RequirementBrief.class).orElseThrow();
  }

  private String execute(String arguments) {
    return execute(arguments, ProductCapabilityCaptureContext.invocationParameters(
        ProductCapabilityCaptureContext.binding(CONVERSATION).orElseThrow()));
  }

  private String execute(String arguments, dev.langchain4j.invocation.InvocationParameters parameters) {
    ToolMethodCreateInfo method = info();
    QuarkusToolExecutor executor = executorFactory.create(new QuarkusToolExecutor.Context(
        tool, method.invokerClassName(), method.methodName(),
        method.argumentMapperClassName(), method.executionModel(), method.returnBehavior(),
        false, method));
    try {
      return executor.executeWithContext(ToolExecutionRequest.builder()
          .id("call-1").name(method.toolSpecification().name()).arguments(arguments).build(),
          InvocationContext.builder().chatMemoryId(CONVERSATION)
              .invocationParameters(parameters)
              .build()).resultText();
    } catch (CaptureValidationException end) {
      return end.getMessage();
    }
  }

  private static ToolMethodCreateInfo info() {
    List<ToolMethodCreateInfo> methods =
        ToolsRecorder.getMetadata().get(ProductRequirementBriefTool.class.getName());
    return methods.stream().filter(method -> "captureRequirementBrief".equals(method.methodName()))
        .findFirst().orElseThrow();
  }

  private static void collect(
      JsonSchemaElement schema, java.util.Map<String, JsonSchemaElement> definitions,
      Set<String> fields) {
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
