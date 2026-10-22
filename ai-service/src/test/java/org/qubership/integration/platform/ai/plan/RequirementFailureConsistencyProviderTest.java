package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementFailureConsistencyProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject RequirementCaptureTools tools;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock RequirementDraftTool adapter;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeHttpConflictReachesTheNextProviderRequestAndOnlyModeIsCorrected(boolean lateBinding) throws Exception {
    String chat = "http-consistency-provider-" + UUID.randomUUID();
    String source = "Receive POST /work. Use errorThrowing=false on Remote request, then Render result. "
        + "Return every HTTP response through that local script to the result delivery. Preserve all success and failure fields. No IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    ProductCapabilityCaptureContext.bindDiscovery("run-" + chat, chat, ignored -> {});
    var original = RequirementFailureConsistencyAdmissionTest.input();
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(), config.llm().maxActiveTimePerRun());
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    if (lateBinding) {
      try (var ignored = ToolSession.open(chat)) {
        assertTrue(json.readTree(tools.captureRequirementDraft(original)).path("accepted").asBoolean());
      }
    }
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString()))
        .thenReturn(RequirementFailureConsistencyAdmissionTest.bindings());
    var corrected = RequirementFailureConsistencyAdmissionTest.withMode(original, ServiceCallFailureMode.INLINE_RESPONSE);
    var script = new ScriptedStreamingChatModel(
        lateBinding
            ? Turn.tool("finish-after-resolution", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}")
            : Turn.tool("capture-conflicting-mode", request -> {}, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", original))),
        Turn.tool("native-conflict-feedback", request -> {
          var refusal = lastTool(request);
          assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
          assertTrue(refusal.path("issues").toString().contains("FAILURE_MODE_CONFLICT"), refusal.toString());
          assertTrue(refusal.path("issues").toString().contains("/draft/flow/interactions/remote/failureMode"));
          var attempt = artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          assertEquals(refusal.path("issues"), attempt.payload().path("issues"));
          if (lateBinding) assertEquals(original, drafts.get(chat).orElseThrow().authoredDraft());
          else assertEquals("PROPAGATE", attempt.payload().at("/arguments/flow/interactions/1/failureMode").asText());
        }, lateBinding ? "updateRequirementDraft" : "captureRequirementDraft", json.writeValueAsString(lateBinding
            ? Map.of("changes", RequirementFailureConsistencyAdmissionTest.update(RequirementFailureConsistencyAdmissionTest.mode(ServiceCallFailureMode.INLINE_RESPONSE)))
            : Map.of("draft", corrected))),
        Turn.tool("corrected-mode", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals(corrected, drafts.get(chat).orElseThrow().authoredDraft());
          assertEquals(original.facts(), drafts.get(chat).orElseThrow().authoredDraft().facts());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("continued", request -> assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString()), "Requirements are ready."));
    doAnswer(call -> { script.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1)); return null; })
        .when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    try {
      gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters())
          .collect().asList().await().atMost(Duration.ofSeconds(30));
      script.assertComplete();
      assertEquals(4, script.requests().size());
      var sourceAfter = json.valueToTree(conversations.archive().read(chat));
      assertFalse(sourceAfter.at("/turns/0/consumedDraftReference").isNull());
      ((com.fasterxml.jackson.databind.node.ObjectNode) sourceBefore.at("/turns/0"))
          .set("consumedDraftReference", sourceAfter.at("/turns/0/consumedDraftReference"));
      assertEquals(sourceBefore, sourceAfter, "Admission acknowledges the source without changing its content or generation");
      assertEquals(limitsBefore, List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(), config.llm().maxActiveTimePerRun()));
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow().text());
    } catch (Exception failure) { throw new AssertionError(failure); }
  }
}
