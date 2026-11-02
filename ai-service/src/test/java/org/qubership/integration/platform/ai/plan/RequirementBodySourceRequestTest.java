package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.*;
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
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.RequestedElementValues;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementBodySourceRequestTest {
  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock RequirementDraftTool adapter;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualProviderReceivesBodyAuthoritySchemaAndNativeCorrectionBeforeContinuation(boolean literal) throws Exception {
    String chat = "source-body-provider-" + UUID.randomUUID();
    String body = "  exchange.in.body = 'source 😀'  \n\n";
    String source = literal ? "Receive POST /work. Use this exact code for prepare:\n```groovy\n" + body + "```\n"
        : "Receive POST /work. Generate a local Groovy script returning the requested result.";
    var turn = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, turn, source);
    drafts.beginTurn(chat, source);
    ProductCapabilityCaptureContext.bindDiscovery("run-" + chat, chat, ignored -> {});
    when(adapter.canonicalBindings(any(), nullable(RequirementDraft.class), anyString())).thenReturn(List.of());
    var initial = RequirementBodyCaptureProviderTest.input(literal ? body : "Groovy", null);
    var intent = initial.facts().getFirst().elementIntent();
    var expected = RequirementBodySources.resolve(intent, List.of(intent),
        AuthorSourceEvidence.packet(conversations.archive().read(chat))).binding();
    var corrected = RequirementBodyCaptureProviderTest.input(literal ? body : "Groovy", expected);
    var limits = List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(), config.llm().maxActiveTimePerRun());
    var scripted = new ScriptedStreamingChatModel(
        Turn.tool("missing-source-authority", request -> {
          var specification = request.toolSpecifications().stream().filter(tool -> "captureRequirementDraft".equals(tool.name()))
              .findFirst().orElseThrow();
          var draft = (JsonObjectSchema) specification.parameters().properties().get("draft");
          var fact = (JsonObjectSchema) ((JsonArraySchema) draft.properties().get("facts")).items();
          var element = ((JsonAnyOfSchema) fact.properties().get("elementIntent")).anyOf().stream()
              .filter(JsonObjectSchema.class::isInstance).map(JsonObjectSchema.class::cast).findFirst().orElseThrow();
          assertTrue(element.required().contains("bodyBinding"));
          assertTrue(element.properties().get("bodyBinding").description().contains("Never downgrade an author body"));
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", initial))),
        Turn.tool("source-bound-correction", request -> {
          var refused = lastTool(request);
          assertFalse(refused.path("accepted").asBoolean(), refused.toString());
          assertTrue(refused.path("issues").toString().contains("BODY_BINDING_REQUIRED"), refused.toString());
          assertTrue(refused.path("issues").toString().contains(expected.authority().name()), refused.toString());
          assertTrue(refused.path("issues").toString().contains(turn.generation()), refused.toString());
          assertTrue(drafts.get(chat).isEmpty());
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", corrected))),
        Turn.tool("continue-after-native-capture", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals(corrected, drafts.get(chat).orElseThrow().authoredDraft());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("ready", request -> assertTrue(lastTool(request).path("accepted").asBoolean()), "Requirements are ready."));
    doAnswer(call -> { scripted.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class)); return null; })
        .when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    try {
      gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters()).collect().asList().await().atMost(Duration.ofSeconds(30));
      scripted.assertComplete();
      assertEquals(4, scripted.requests().size());
      var brief = new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "")
          .withFacts(RequirementCaptureProjection.facts(drafts.get(chat).orElseThrow().authoredDraft()));
      var graph = RequirementBodyCaptureProviderTest.graph(literal ? null : body);
      graph = RequestedElementValues.seedBodies(graph, brief.facts());
      assertEquals(body, RequirementBodyCaptureProviderTest.property(RequestedElementValues.apply(graph, brief.facts())));
      assertTrue(CompilerQualityValidator.validateApprovedValues(graph, brief).isEmpty());
      assertEquals(source, conversations.archive().read(chat).activeTurns().getFirst().message().content());
      assertEquals(limits, List.of(config.llm().maxRequestsPerRun(), config.llm().maxTokensPerRun(), config.llm().maxActiveTimePerRun()));
    } finally { ProductCapabilityCaptureContext.unbind(); }
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow().text());
    } catch (Exception failure) { throw new AssertionError(failure); }
  }
}
