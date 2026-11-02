package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementRejectedReferenceRepairProviderTest {
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
  void nativeReferenceRefusalReachesTheProviderAndItsExactCorrectionContinues(boolean predecessor) throws Exception {
    String chat = "reference-repair-provider-" + UUID.randomUUID();
    String source = RequirementRejectedReferenceRepairTest.SOURCE;
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    ProductCapabilityCaptureContext.bindDiscovery("run-" + chat, chat, ignored -> {});
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var fixture = new RequirementRejectedReferenceRepairTest.Fixture(predecessor);
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limits = List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(), config.llm().maxActiveTimePerRun());
    var scripted = new ScriptedStreamingChatModel(
        capture("original-identity", fixture.original(), request -> {}),
        capture("unproven-reference", fixture.proof(), request -> {
          assertIssue(request, predecessor ? "INVALID_VALUE" : "INVALID_ELEMENT_REFERENCE");
          assertTrue(artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION).isEmpty());
        }),
        capture("canonical-candidate", fixture.corrected(), request -> {
          var refusal = lastTool(request);
          assertIssue(request, "UNKNOWN_ELEMENT_REFERENCE");
          assertTrue(refusal.path("issues").toString().contains("/draft/facts/owner/elementIntent/" + fixture.field()),
              refusal.toString());
          assertTrue(refusal.path("repairConstraints").isMissingNode() || refusal.path("repairConstraints").isNull(),
              refusal.toString());
          var current = artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          assertFalse(current.payload().path("accepted").asBoolean());
          assertEquals(json.valueToTree(fixture.proof()), current.payload().path("arguments"));
          assertTrue(artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_PROTECTION).isEmpty());
        }),
        Turn.tool("continue", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals(fixture.corrected(), drafts.get(chat).orElseThrow().authoredDraft());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("ready", request -> assertTrue(lastTool(request).path("accepted").asBoolean()), "Requirements are ready."));
    doAnswer(call -> { scripted.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class)); return null; })
        .when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    try {
      gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters()).collect().asList().await().atMost(Duration.ofSeconds(30));
      scripted.assertComplete();
      assertEquals(5, scripted.requests().size());
      var after = json.valueToTree(conversations.archive().read(chat));
      ((com.fasterxml.jackson.databind.node.ObjectNode) sourceBefore.at("/turns/0"))
          .set("consumedDraftReference", after.at("/turns/0/consumedDraftReference"));
      assertEquals(sourceBefore, after);
      assertEquals(limits, List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(), config.llm().maxActiveTimePerRun()));
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  @org.junit.jupiter.api.Test
  void displayNameRefusalShowsCanonicalDeclarationsInTheActualNextRequestAndNativeCorrectionContinues() throws Exception {
    String chat = "declared-reference-provider-" + UUID.randomUUID();
    String source = RequirementRejectedReferenceRepairTest.SOURCE;
    var sourceInput = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, sourceInput, source);
    drafts.beginTurn(chat, source);
    ProductCapabilityCaptureContext.bindDiscovery("run-" + chat, chat, ignored -> {});
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var canonical = new RequirementRejectedReferenceRepairTest.Fixture(false).corrected();
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(canonical);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.at("/facts/1/elementIntent"))
        .put("successorElementRef", "Prepare work");
    var rejected = json.treeToValue(tree, RequirementCaptureInput.DraftInput.class);
    String raw = json.writeValueAsString(Map.of("draft", rejected));
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var rejectedRevision = new java.util.concurrent.atomic.AtomicReference<CompilationArtifacts.Revision>();
    var originalSchemas = new java.util.concurrent.atomic.AtomicReference<List<dev.langchain4j.agent.tool.ToolSpecification>>();
    var limits = List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(),
        config.llm().maxActiveTimePerRun(), config.capture().maxRepairAttempts());
    var scripted = new ScriptedStreamingChatModel(
        Turn.tool("name-as-reference", request -> originalSchemas.set(request.parameters().toolSpecifications()),
            "captureRequirementDraft", raw),
        capture("canonical-reference", canonical, request -> {
          assertIssue(request, "UNKNOWN_ELEMENT_REFERENCE");
          var refusal = lastTool(request);
          var issue = refusal.path("issues").get(0);
          assertEquals("/draft/facts/owner/elementIntent/successorElementRef", issue.path("path").asText());
          String message = issue.path("message").asText();
          assertTrue(message.contains("Rejected successorElementRef=\"Prepare work\""), message);
          assertTrue(message.contains("Canonical IDs recognized by this check: [\"prepare-step\"]"), message);
          assertTrue(message.contains("\"elementRef\":\"prepare-step\",\"requestedName\":\"Prepare work\",\"ownerInteractionId\":\"entry\""), message);
          var original = request.messages().stream().filter(dev.langchain4j.data.message.AiMessage.class::isInstance)
              .map(dev.langchain4j.data.message.AiMessage.class::cast)
              .flatMap(value -> value.toolExecutionRequests().stream())
              .filter(value -> "name-as-reference".equals(value.id())).findFirst().orElseThrow();
          assertEquals(raw, original.arguments(), "The actual next request must retain the entire rejected proposal.");
          assertTrue(request.messages().toString().contains(source), "The complete original source must reach the provider.");
          assertEquals(originalSchemas.get(), request.parameters().toolSpecifications());
          var revision = artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          rejectedRevision.set(revision);
          assertFalse(revision.payload().path("accepted").asBoolean());
          assertEquals(json.valueToTree(rejected), revision.payload().path("arguments"));
          assertTrue(drafts.get(chat).isEmpty());
        }),
        Turn.tool("continue-canonical", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals(canonical, drafts.get(chat).orElseThrow().authoredDraft());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("ready-canonical", request -> assertTrue(lastTool(request).path("accepted").asBoolean()), "Requirements are ready."));
    doAnswer(call -> { scripted.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class)); return null; })
        .when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    try {
      gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters()).collect().asList().await().atMost(Duration.ofSeconds(30));
      scripted.assertComplete();
      assertEquals(4, scripted.requests().size());
      assertNotNull(rejectedRevision.get());
      assertEquals(rejectedRevision.get(), artifacts.get(sessions.active(chat), rejectedRevision.get().reference()).orElseThrow());
      var after = json.valueToTree(conversations.archive().read(chat));
      ((com.fasterxml.jackson.databind.node.ObjectNode) sourceBefore.at("/turns/0"))
          .set("consumedDraftReference", after.at("/turns/0/consumedDraftReference"));
      assertEquals(sourceBefore, after);
      assertEquals(limits, List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(),
          config.llm().maxActiveTimePerRun(), config.capture().maxRepairAttempts()));
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  private Turn capture(String id, RequirementCaptureInput.DraftInput input, java.util.function.Consumer<ChatRequest> assertion) throws Exception {
    return Turn.tool(id, assertion, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", input)));
  }
  private void assertIssue(ChatRequest request, String code) {
    var refusal = lastTool(request);
    assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
    assertTrue(refusal.path("issues").toString().contains(code), refusal.toString());
  }
  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow().text());
    } catch (Exception failure) { throw new AssertionError(failure); }
  }
}
