package org.qubership.integration.platform.ai.llm.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StringifiedToolArgumentsNormalizerTest {

  @Test
  void invalidPatchFeedbackUsesTheConversationBehindTheSkillMemoryId() {
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore();
    var gateway = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(
        new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(), feedback);
    var boundary = new StringifiedToolArgumentsNormalizer(objectMapper, null, null, null, gateway);
    var specification = ToolSpecification.builder().name("captureGraphPatch").parameters(JsonObjectSchema.builder()
        .addProperty("patch", JsonObjectSchema.builder().addStringProperty("patchId").required("patchId").build())
        .build()).build();
    var method = new ToolMethodCreateInfo("captureGraphPatch", "invoker", specification,
        "mapper", ToolMethodCreateInfo.ExecutionModel.BLOCKING, ReturnBehavior.TO_LLM, null, null);
    var executor = org.mockito.Mockito.mock(io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor.class);
    org.mockito.Mockito.when(executor.getMethodCreateInfo()).thenReturn(method);
    var invocation = org.mockito.Mockito.mock(dev.langchain4j.invocation.InvocationContext.class);
    org.mockito.Mockito.when(invocation.chatMemoryId()).thenReturn(
        org.qubership.integration.platform.ai.compiler.CompilerSkillMemoryIds.forSkill("patch-shape-test", "generator"));
    org.jboss.logmanager.MDC.put(org.qubership.integration.platform.ai.compiler.CompilerSkillMdc.CAPABILITY_ID, "generator");
    try {
      boundary.wrap(ToolExecutionRequest.builder().id("call-1").name("captureGraphPatch")
          .arguments("{\"patch\":{}}").build(), invocation,
          (call, context) -> { throw new AssertionError("Invalid capture reached argument binding"); }, executor);
      org.junit.jupiter.api.Assertions.assertTrue(feedback.lastPatchFailure("patch-shape-test", "generator")
          .orElseThrow().summary().contains("MISSING_FIELD /patch/patchId"));
    } finally {
      org.jboss.logmanager.MDC.remove(org.qubership.integration.platform.ai.compiler.CompilerSkillMdc.CAPABILITY_ID);
    }
  }

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final StringifiedToolArgumentsNormalizer normalizer =
      new StringifiedToolArgumentsNormalizer(objectMapper);

  @Test
  void unwrapsOneJsonLayerForObjectAndArrayParameters() throws Exception {
    ToolExecutionRequest request =
        request("{\"object\":\"{\\\"id\\\":7}\",\"array\":\"[1,2]\"}");

    ToolExecutionRequest normalized =
        normalizer.normalize(
            request,
            method(
                Map.of(
                    "object", JsonObjectSchema.builder().build(),
                    "array", JsonArraySchema.builder().build())));

    JsonNode arguments = objectMapper.readTree(normalized.arguments());
    assertEquals(7, arguments.get("object").get("id").intValue());
    assertEquals(2, arguments.get("array").size());
  }

  @Test
  void rejectsNamingShapeBeforeBindingAndRecordsActionableFeedback() throws Exception {
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore();
    var gateway = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(
        new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(), feedback);
    var boundary = new StringifiedToolArgumentsNormalizer(objectMapper, null, null, null, gateway);
    var capture = JsonObjectSchema.builder().addIntegerProperty("schemaVersion").addStringProperty("chainName")
        .addProperty("labels", JsonArraySchema.builder().items(JsonObjectSchema.builder()
            .addStringProperty("roleId").addStringProperty("label").build()).build())
        .addProperty("sourceRequirementFactIds", JsonArraySchema.builder().items(new JsonStringSchema()).build())
        .addProperty("knowledgeCitations", JsonArraySchema.builder()
            .items(dev.langchain4j.model.chat.request.json.JsonRawSchema.from("{}")).build()).build();
    var specification = ToolSpecification.builder().name("captureNamingManifest")
        .parameters(JsonObjectSchema.builder().addProperty("capture", capture).build()).build();
    var method = new ToolMethodCreateInfo("captureNamingManifest", "invoker", specification,
        "mapper", ToolMethodCreateInfo.ExecutionModel.BLOCKING, ReturnBehavior.TO_LLM, null, null);
    var executor = org.mockito.Mockito.mock(io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor.class);
    org.mockito.Mockito.when(executor.getMethodCreateInfo()).thenReturn(method);
    org.qubership.integration.platform.ai.chat.ToolSession.bind("shape-test");
    try {
      var result = boundary.wrap(ToolExecutionRequest.builder().id("call-1").name("captureNamingManifest")
              .arguments("{\"capture\":{\"labels\":[]}}").build(), null,
          (call, context) -> { throw new AssertionError("Invalid capture reached argument binding"); }, executor);
      org.junit.jupiter.api.Assertions.assertTrue(result.resultText().contains("MISSING_FIELD /capture/chainName"));
      org.junit.jupiter.api.Assertions.assertTrue(feedback.lastPlanFailure("shape-test").orElseThrow()
          .summary().contains("/capture/chainName"));
      var accepted = boundary.wrap(ToolExecutionRequest.builder().id("call-2").name("captureNamingManifest")
              .arguments("{\"capture\":{\"chainName\":\"Exact name\",\"labels\":[]}}").build(), null,
          (call, context) -> dev.langchain4j.service.tool.ToolExecutionResult.builder().resultText("bound").build(), executor);
      assertEquals("bound", accepted.resultText());
    } finally {
      org.qubership.integration.platform.ai.chat.ToolSession.clear();
    }
  }

  @Test
  void leavesDeclaredStringsAndMalformedJsonUntouched() {
    ToolExecutionRequest request =
        request("{\"script\":\"{\\\"id\\\":7}\",\"capture\":\"{broken\"}");

    ToolExecutionRequest normalized =
        normalizer.normalize(
            request,
            method(
                Map.of(
                    "script", JsonStringSchema.builder().build(),
                    "capture", JsonObjectSchema.builder().build())));

    assertEquals(request, normalized);
  }

  @Test
  void leavesAmbiguousStringOrObjectParametersUntouched() {
    ToolExecutionRequest request = request("{\"value\":\"{\\\"id\\\":7}\"}");

    ToolExecutionRequest normalized =
        normalizer.normalize(
            request,
            method(
                Map.of(
                    "value",
                    JsonAnyOfSchema.builder()
                        .anyOf(
                            JsonStringSchema.builder().build(),
                            JsonObjectSchema.builder().build(),
                            new JsonNullSchema())
                        .build())));

    assertEquals(request, normalized);
  }

  private static ToolExecutionRequest request(String arguments) {
    return ToolExecutionRequest.builder().id("call-1").name("capture").arguments(arguments).build();
  }

  private static ToolMethodCreateInfo method(Map<String, JsonSchemaElement> properties) {
    return new ToolMethodCreateInfo(
        "capture",
        "invoker",
        ToolSpecification.builder()
            .name("capture")
            .parameters(JsonObjectSchema.builder().addProperties(properties).build())
            .build(),
        "mapper",
        ToolMethodCreateInfo.ExecutionModel.BLOCKING,
        ReturnBehavior.TO_LLM,
        null,
        null);
  }
}
