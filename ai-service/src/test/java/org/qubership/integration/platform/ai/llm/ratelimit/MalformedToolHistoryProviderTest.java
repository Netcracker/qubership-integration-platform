package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntime;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.productpipeline.knowledge.DefaultKnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.SidecarKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.patch.*;
import org.qubership.integration.platform.ai.skill.executor.SkillRunStatus;
import org.qubership.integration.platform.ai.skill.orchestration.*;
import org.qubership.integration.platform.ai.skill.workspace.*;

/** Reproduces provider history validation after a native compiler argument refusal. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class MalformedToolHistoryProviderTest {
  private static final String CAPABILITY = "cip-security-generator";
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock SidecarKnowledgeClient knowledge;
  @InjectMock DefaultKnowledgeContextProvider knowledgeContext;
  @Inject CompilerSkillRuntime runtime;
  @Inject GraphPatchExecutionContextStore contexts;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject CaptureSession captures;
  @Inject ChainPlanStore plans;
  @Inject ObjectMapper json;
  @Inject AppConfig config;
  @TempDir Path evidence;
  private final String conversation = "malformed-history-" + UUID.randomUUID();

  @AfterEach
  void clear() {
    contexts.clear(conversation, CAPABILITY);
    feedback.clearPatch(conversation, CAPABILITY);
    feedback.endScope(conversation, CAPABILITY);
  }

  @Test
  void nativeInvalidJsonFeedbackReachesTheImmediateNextProviderAndGenuineCorrectionIsAdmitted() throws Exception {
    var fixture = FakeKnowledgeClient.defaultFixture();
    when(knowledgeContext.forConversation(anyString())).thenReturn(fixture.context());
    when(knowledge.context(any(), any())).thenAnswer(call -> fixture.context(call.getArgument(0), call.getArgument(1)));
    var graph = new ChainPlanGraph("1.0", new ChainSection("Receive", "Receive a request"), List.of(
        new ChainPlanNode("entry", "http-trigger", "Receive", null, 1, List.of(
            new PlanProperty("contextPath", "/receive"), new PlanProperty("httpMethodRestrict", "POST"),
            new PlanProperty("externalRoute", "false"), new PlanProperty("accessControlType", "NONE"))),
        new ChainPlanNode("respond", "script", "Respond", null, 2,
            List.of(new PlanProperty("script", "return 'ok'")))),
        List.of(new ChainPlanEdge("receive-response", "entry", "respond", null)));
    plans.put(conversation, graph);
    var workspace = new InMemorySkillWorkspace(conversation);
    workspace.put(SkillArtifact.of(SkillArtifactType.CHAIN_PLAN_GRAPH, "input",
        new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    contexts.set(conversation, CAPABILITY, new GraphPatchExecutionContext("run", CAPABILITY, "", null,
        "", "2026.1", null, List.of(), graph,
        GraphPatchOwnershipPolicy.denyAll().withAdditionalProperties(Map.of("http-trigger", Set.of("externalRoute"))),
        "attempt"));
    String malformed = "{\"patch\":{\"patchId\":\"rejected\",\"rationale\":\"keep \\\"quoted\\\" text\"} trailing}";
    String corrected = "{\"patch\":{\"patchId\":\"corrected\",\"ownerCapabilityId\":\"" + CAPABILITY
        + "\",\"propertyPatches\":[{\"operation\":\"UPDATE\",\"targetNodeId\":\"entry\","
        + "\"key\":\"externalRoute\",\"scalarValue\":\"false\"}],\"usedKnowledgeRefs\":[]}}";
    var originalResponse = new AtomicReference<dev.langchain4j.agent.tool.ToolExecutionRequest>();
    var rejectedAudit = new AtomicReference<Path>();
    var rejectedAuditBytes = new AtomicReference<byte[]>();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("rejected-json", request -> {}, "captureGraphPatch", malformed),
        Turn.tool("real-correction", request -> {
          var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
              .map(ToolExecutionResultMessage.class::cast).filter(tool -> "rejected-json".equals(tool.id()))
              .findFirst().orElseThrow();
          assertTrue(result.text().startsWith("INVALID_JSON /:"), result.text());
          assertEquals(feedback.lastPatchFailure(conversation, CAPABILITY).orElseThrow().summary(), result.text());
          var call = request.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
              .flatMap(message -> message.toolExecutionRequests().stream())
              .filter(tool -> "rejected-json".equals(tool.id())).findFirst().orElseThrow();
          try {
            var rejected = json.readTree(call.arguments()).path("rejectedToolCall");
            assertEquals("REJECTED", rejected.path("status").asText());
            assertEquals("INVALID_JSON", rejected.path("reason").asText());
            assertEquals(malformed, rejected.path("rawArguments").asText());
          } catch (java.io.IOException invalidHistory) {
            throw new InvalidRequestException("Provider rejected malformed historical function arguments", invalidHistory);
          }
          assertTrue(request.parameters().toolSpecifications().stream()
              .anyMatch(tool -> "captureGraphPatch".equals(tool.name()) && tool.parameters().properties().containsKey("patch")));
          assertTrue(request.messages().toString().contains("Current ChainPlanGraph JSON:"));
          try (var files = Files.list(evidence.resolve("provider-boundary"))) {
            var records = files.toList();
            var saved = records.stream().filter(file -> file.getFileName().toString().endsWith("-response.json"))
                .findFirst().orElseThrow();
            assertEquals(malformed, json.readTree(saved.toFile()).path("value").path("message")
                .path("toolExecutionRequests").get(0).path("arguments").asText());
            rejectedAudit.set(saved);
            rejectedAuditBytes.set(Files.readAllBytes(saved));
            boolean effectiveRecorded = false;
            for (var record : records) {
              if (!record.getFileName().toString().endsWith("-request.json")) continue;
              for (var message : json.readTree(record.toFile()).path("request").path("messages")) {
                for (var historic : message.path("toolExecutionRequests")) {
                  if ("rejected-json".equals(historic.path("id").asText())) {
                    assertEquals(call.arguments(), historic.path("arguments").asText());
                    effectiveRecorded = true;
                  }
                }
              }
            }
            assertTrue(effectiveRecorded, "The existing boundary recorder must capture the effective request.");
          } catch (java.io.IOException missingAudit) { throw new AssertionError(missingAudit); }
        }, "captureGraphPatch", corrected));
    var upstream = new dev.langchain4j.model.chat.StreamingChatModel() {
      @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        script.chat(request, new StreamingChatResponseHandler() {
          @Override public void onPartialResponse(String text) { handler.onPartialResponse(text); }
          @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
            if (originalResponse.get() == null) originalResponse.set(response.aiMessage().toolExecutionRequests().getFirst());
            handler.onCompleteResponse(response);
          }
          @Override public void onError(Throwable error) { handler.onError(error); }
        });
      }
    };
    Files.writeString(evidence.resolve("plan.json"), json.writeValueAsString(Map.of("conversationId", conversation)));
    var recordedConfig = mock(AppConfig.class, RETURNS_DEEP_STUBS);
    when(recordedConfig.llm()).thenReturn(config.llm());
    when(recordedConfig.e2e().recoveryControlDirectory()).thenReturn(java.util.Optional.of(evidence.toString()));
    var boundary = new RateLimitStreamingChatModel(upstream, recordedConfig, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
    doAnswer(call -> {
      boundary.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var context = new SkillRunContext(conversation, CAPABILITY, CipKnowledgePackFixturePaths.PACK_DIR,
        SkillSubgraph.BUILD_CHAIN, 1, false, "", "", "run-" + conversation);

    org.jboss.logmanager.MDC.put("conversationId", conversation);
    try {
      runtime.runStreaming(context, workspace, CAPABILITY).collect().asList().await().atMost(Duration.ofSeconds(20));
    } finally { org.jboss.logmanager.MDC.remove("conversationId"); }
    var result = runtime.run(context, workspace, CAPABILITY).await().atMost(Duration.ofSeconds(20));

    script.assertComplete();
    assertEquals(2, script.requests().size(), "The inner tool loop must deliver the feedback without an outer retry.");
    assertEquals(SkillRunStatus.COMPLETED, result.status(), result.message());
    assertEquals(malformed, originalResponse.get().arguments(), "The original model response stays immutable.");
    assertArrayEquals(rejectedAuditBytes.get(), Files.readAllBytes(rejectedAudit.get()), "The original audit stays immutable.");
    var accepted = captures.get(CaptureKey.capability(CaptureSlot.GRAPH_PATCH, conversation, CAPABILITY),
        GraphPatch.class).orElseThrow();
    assertEquals("corrected", accepted.patchId());
    assertTrue(feedback.lastPatchFailure(conversation, CAPABILITY).isEmpty());
  }
}
