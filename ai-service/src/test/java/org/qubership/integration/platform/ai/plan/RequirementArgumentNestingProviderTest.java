package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
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
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.AmendmentInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RequirementArgumentNestingProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @InjectMock RateLimitStreamingChatModel provider;

  @ParameterizedTest
  @ValueSource(strings = {"amendments", "addFacts", "removeQuestionIds", "setCapabilities"})
  void losslessUniqueNestingReachesTheNativeCaptureAndTheFollowingModelRequest(String field) throws Exception {
    String chat = "argument-nesting-" + UUID.randomUUID();
    String source = "Receive internal POST /argument-nesting with externalRoute=false and accessControlType=NONE. Proceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    var initial = initial(source);
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
    ObjectNode malformed = json.valueToTree(Map.of("changes", update));
    malformed.set(field, ((ObjectNode) malformed.path("changes")).remove(field));
    String raw = json.writeValueAsString(malformed);
    var script = new ScriptedStreamingChatModel(
        Turn.tool("initialize", request -> {}, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", initial))),
        Turn.tool("misplaced-field", request -> {
          assertTrue(lastTool(request, "captureRequirementDraft").path("accepted").asBoolean());
          var schema = request.parameters().toolSpecifications().stream()
              .filter(spec -> "updateRequirementDraft".equals(spec.name())).findFirst().orElseThrow().parameters();
          assertEquals(Boolean.FALSE, schema.additionalProperties());
          assertTrue(((dev.langchain4j.model.chat.request.json.JsonObjectSchema) schema.properties().get("changes"))
              .required().contains(field));
        }, "updateRequirementDraft", raw),
        Turn.tool("native-result-after-structural-correction", request -> {
          var result = lastTool(request, "updateRequirementDraft");
          assertTrue(result.path("accepted").asBoolean(), result.toString());
          assertEquals(json.valueToTree(initial), result.path("draft"));
          assertEquals(initial, drafts.get(chat).orElseThrow().authoredDraft());
          var attempt = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          assertEquals(json.valueToTree(update), attempt.payload().path("arguments"));
          var binding = attempt.payload().path("argumentBinding");
          assertEquals("misplaced-field", binding.path("toolCallId").asText());
          assertEquals(raw, binding.path("rawArguments").asText());
          assertEquals(json.valueToTree(Map.of("changes", update)), binding.path("effectiveArguments"));
          assertEquals(json.valueToTree(Map.of("/" + field, "/changes/" + field)), binding.path("relocations"));
          var original = request.messages().stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
              .flatMap(message -> message.toolExecutionRequests().stream())
              .filter(call -> "misplaced-field".equals(call.id())).findFirst().orElseThrow();
          assertEquals(raw, original.arguments());
          assertEquals(attempt, artifacts.get(attempt.compilationId(), attempt.reference()).orElseThrow());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("continued", request -> {
          assertTrue(lastTool(request, "finishRequirementDiscoveryTurn").path("accepted").asBoolean());
        }, "The requirements are ready."));
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      script.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters())
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    assertEquals(4, script.requests().size());
    assertEquals(RequirementDiscoveryDirective.CONTINUE, drafts.turnDirective(chat));
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid-type", "unknown-grant", "conflict", "duplicate", "invalid-enum",
      "forged-amendment", "native-reference"})
  void structuralCorrectionStillRunsEveryNativeCheckAndPreservesItsRefusal(String mode) throws Exception {
    String chat = "argument-rejection-" + UUID.randomUUID();
    String source = "Receive internal POST /argument-nesting with externalRoute=false and accessControlType=NONE. Proceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    var initial = initial(source);
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
    ObjectNode malformed = json.valueToTree(Map.of("changes", update));
    ObjectNode changes = (ObjectNode) malformed.path("changes");
    String expected = switch (mode) {
      case "unknown-grant" -> {
        malformed.put("grant", true);
        yield "UNEXPECTED_FIELD";
      }
      case "conflict" -> {
        malformed.set("amendments", json.createArrayNode());
        yield "UNEXPECTED_FIELD";
      }
      case "invalid-enum" -> {
        changes.remove("setCapabilities");
        malformed.set("setCapabilities", json.valueToTree(List.of(new CapabilityInput("entry", "invented-capability",
            HttpMode.CUSTOM, HttpMethod.POST, "/argument-nesting", null, null, null))));
        yield "INVALID_ENUM";
      }
      case "native-reference" -> {
        changes.remove("removeQuestionIds");
        malformed.set("removeQuestionIds", json.valueToTree(List.of("absent-question")));
        yield "UNKNOWN_ENTITY";
      }
      case "forged-amendment" -> {
        changes.remove("amendments");
        malformed.set("amendments", json.valueToTree(List.of(
            new AmendmentInput("/settings/idsRequested", "invented-source", source))));
        yield "INVALID_AUTHOR_AMENDMENT";
      }
      default -> {
        changes.remove("amendments");
        malformed.put("amendments", false);
        yield "duplicate".equals(mode) ? "DUPLICATE_JSON_KEY" : "INVALID_TYPE";
      }
    };
    String encoded = json.writeValueAsString(malformed);
    String raw = "duplicate".equals(mode) ? encoded.substring(0, encoded.length() - 1) + ",\"amendments\":[]}" : encoded;
    var rejected = new java.util.concurrent.atomic.AtomicReference<CompilationArtifacts.Revision>();
    var base = new java.util.concurrent.atomic.AtomicReference<CompilationArtifacts.Reference>();
    var sourceBefore = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("initialize", request -> {}, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", initial))),
        Turn.tool("invalid-nesting", request -> {
          assertTrue(lastTool(request, "captureRequirementDraft").path("accepted").asBoolean());
          base.set(drafts.latestRevision(chat).orElseThrow().reference());
          sourceBefore.set(json.valueToTree(conversations.archive().read(chat)));
        }, "updateRequirementDraft", raw),
        Turn.tool("native-refusal", request -> {
          var result = lastTool(request, "updateRequirementDraft");
          assertEquals(false, result.path("accepted").asBoolean(), result.toString());
          assertEquals(false, result.path("changed").asBoolean());
          assertTrue(java.util.stream.StreamSupport.stream(result.path("issues").spliterator(), false)
              .anyMatch(issue -> expected.equals(issue.path("code").asText())), result.toString());
          assertEquals(initial, drafts.get(chat).orElseThrow().authoredDraft());
          assertEquals(base.get(), drafts.latestRevision(chat).orElseThrow().reference());
          assertEquals(sourceBefore.get(), json.valueToTree(conversations.archive().read(chat)));
          var attempt = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          rejected.set(attempt);
          assertEquals(result.path("issues"), attempt.payload().path("issues"));
          var binding = attempt.payload().path("argumentBinding");
          if (!binding.isNull()) {
            assertEquals(raw, binding.path("rawArguments").asText());
            assertEquals("invalid-nesting", binding.path("toolCallId").asText());
          } else {
            assertEquals(raw, attempt.payload().path("arguments").asText());
          }
        }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", update))),
        Turn.tool("ordinary-correction", request -> {
          assertTrue(lastTool(request, "updateRequirementDraft").path("accepted").asBoolean());
          assertEquals(initial, drafts.get(chat).orElseThrow().authoredDraft());
          assertEquals(rejected.get(), artifacts.get(rejected.get().compilationId(), rejected.get().reference()).orElseThrow());
          assertTrue(artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().payload().path("argumentBinding").isNull());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("continued", request -> {
          assertTrue(lastTool(request, "finishRequirementDiscoveryTurn").path("accepted").asBoolean());
        }, "The requirements are ready."));
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      script.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters())
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    assertEquals(5, script.requests().size());
    assertEquals(RequirementDiscoveryDirective.CONTINUE, drafts.turnDirective(chat));
  }

  private DraftInput initial(String source) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /argument-nesting", null, null, null)), List.of()),
        List.of(new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, source, null, null,
            new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
                "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false"),
                    new PlanProperty("accessControlType", "NONE")), List.of()))),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/argument-nesting", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private JsonNode lastTool(ChatRequest request, String name) {
    try {
      var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      assertEquals(name, result.toolName());
      return json.readTree(result.text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }
}
