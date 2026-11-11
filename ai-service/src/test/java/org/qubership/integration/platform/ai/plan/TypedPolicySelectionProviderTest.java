package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures.withNativeAnchors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict.Decision;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class TypedPolicySelectionProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject RequirementCaptureTools tools;
  @Inject ConversationService conversations;
  @Inject PolicyChoiceSelection choices;
  @Inject ArtifactBlobStore blobs;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void exactTypedChoicePathsReachTheProviderWithoutProseOrConsumedSourceAuthority(boolean intentOnPolicyFact)
      throws Exception {
    var h = new Fixture(intentOnPolicyFact);
    var sourceBefore = json.valueToTree(conversations.archive().read(h.chat));
    var base = drafts.latestRevision(h.chat).orElseThrow().reference();
    var limitsBefore = limits();
    var durableBeforePrompt = persistedState();
    assertEquals(List.of("/facts/policy/policyConflict/decision", h.propertyPath()), choices.pendingAmendmentPaths(h.chat));
    assertTrue(choices.pendingAmendmentPaths("foreign-" + UUID.randomUUID()).isEmpty());
    String prompt = prompts.wrap(h.chat, h.quote, "en");
    assertEquals(durableBeforePrompt, persistedState(), "Presenting paths must not write authority or source state.");
    var accepted = new AtomicReference<CompilationArtifacts.Reference>();
    var consumedSource = new AtomicReference<JsonNode>();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("parent-amendment", request -> {
          String body = userText(request);
          assertTrue(body.contains("Current typed-policy amendment paths"), body);
          assertTrue(body.contains(h.propertyPath()), body);
          assertTrue(body.contains("/facts/policy/policyConflict/decision"), body);
          assertTrue(body.contains(h.source.turnId()), body);
          assertTrue(body.contains(h.quote), body);
          assertTrue(body.contains(base.artifactId()), body);
          assertTrue(body.contains("Preserve the policy fact's original text as historical source context"), body);
          assertTrue(body.contains("the typed decision records the current choice"), body);
          assertTrue(body.contains("not its parent fact, elementIntent, or requestedProperties list"), body);
          assertTrue(body.contains("do not repeat them with later addFacts"), body);
        }, "updateRequirementDraft", h.arguments(h.update(false, true, h.parentPath()))),
        Turn.tool("prose-amendment", request -> {
          assertRefused(request, base);
          assertTrue(lastTool(request).path("issues").findValuesAsText("path").contains("/changes/amendments/0"));
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(h.chat)));
        }, "updateRequirementDraft", h.arguments(h.update(true, true, h.propertyPath(), "/facts/policy/text"))),
        Turn.tool("exact-choice", request -> {
          assertRefused(request, base);
          assertTrue(lastTool(request).path("issues").findValuesAsText("path").contains("/changes/amendments/1"));
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(h.chat)));
          assertEquals(h.initial, drafts.get(h.chat).orElseThrow().authoredDraft());
        }, "updateRequirementDraft", h.arguments(h.update(false, true, h.propertyPath()))),
        Turn.tool("consumed-amendment", request -> {
          JsonNode result = lastTool(request);
          assertTrue(result.path("accepted").asBoolean(), result.toString());
          assertTrue(result.path("changed").asBoolean(), result.toString());
          assertTrue(result.path("issues").isEmpty());
          var revision = drafts.latestRevision(h.chat).orElseThrow().reference();
          accepted.set(revision);
          assertEquals(json.valueToTree(revision), result.path("revision"));
          assertFalse(drafts.isApproved(h.chat, revision));
          var saved = drafts.get(h.chat).orElseThrow().authoredDraft();
          assertEquals(h.expected(), saved);
          assertTrue(saved.openQuestions().isEmpty());
          assertEquals(h.initial.facts().getFirst().text(), saved.facts().getFirst().text());
          assertTrue(conversations.archive().pendingInputs(h.chat).isEmpty());
          consumedSource.set(json.valueToTree(conversations.archive().read(h.chat)));
          var durableBeforeRead = persistedState();
          assertTrue(choices.pendingAmendmentPaths(h.chat).isEmpty());
          String after = prompts.wrap(h.chat, h.quote, "en");
          assertFalse(after.contains("Current typed-policy amendment paths"), after);
          assertEquals(durableBeforeRead, persistedState());
        }, "updateRequirementDraft", h.arguments(h.update(false, false, h.propertyPath()))),
        Turn.text("consumed-source-refused", request -> {
          assertRefused(request, accepted.get());
          assertEquals(h.expected(), drafts.get(h.chat).orElseThrow().authoredDraft());
          assertEquals(consumedSource.get(), json.valueToTree(conversations.archive().read(h.chat)));
          assertTrue(choices.pendingAmendmentPaths(h.chat).isEmpty());
        }, "The selected policy value is saved; the original policy wording remains source context."));
    doAnswer(call -> {
      script.chat(call.getArgument(0, ChatRequest.class), call.getArgument(1, StreamingChatResponseHandler.class));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(h.chat, prompt, new InvocationParameters()).collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    assertEquals(5, script.requests().size());
    assertEquals(limitsBefore, limits());
  }

  private void assertRefused(ChatRequest request, CompilationArtifacts.Reference base) {
    JsonNode result = lastTool(request);
    assertFalse(result.path("accepted").asBoolean(), result.toString());
    assertFalse(result.path("changed").asBoolean(), result.toString());
    assertTrue(result.path("issues").findValuesAsText("code").contains("INVALID_AUTHOR_AMENDMENT"), result.toString());
    assertEquals(json.valueToTree(base), result.path("revision"));
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      assertEquals("updateRequirementDraft", result.toolName());
      return json.readTree(result.text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private String userText(ChatRequest request) {
    return request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
        .map(UserMessage::singleText).reduce("", (first, next) -> first + "\n" + next);
  }

  private Map<String, String> persistedState() {
    var state = new LinkedHashMap<String, String>();
    for (String key : blobs.list("")) {
      var blob = blobs.getVersioned(key).orElseThrow();
      state.put(key, blob.version() + ":" + Base64.getEncoder().encodeToString(blob.content()));
    }
    return state;
  }

  private Map<String, Object> limits() {
    return Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun(), "recovery", RecoveryAttemptLedger.Limits.defaults());
  }

  private final class Fixture {
    final String chat = "typed-policy-provider-" + UUID.randomUUID();
    final boolean intentOnPolicyFact;
    final DraftInput initial;
    final ConversationArchive.Input source;
    final String quote;

    Fixture(boolean intentOnPolicyFact) throws Exception {
      this.intentOnPolicyFact = intentOnPolicyFact;
      String text = "Expose external POST /tasks with access control NONE. Present the RBAC policy choice and wait."
          + " If RBAC is selected, use exactly role operator. Preserve request IDs. Proceed without IDS.";
      var first = conversations.archive().beginInput(chat, text, null);
      conversations.archive().resolveInput(chat, first, text);
      var facts = new ArrayList<FactInput>();
      facts.add(new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
          "Requested NONE; company policy recommends RBAC. The author has not chosen. Role operator is already decided.",
          null, new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY,
              "accessControlType", "NONE", "RBAC", "choice", null), intentOnPolicyFact ? intent("NONE") : null));
      if (!intentOnPolicyFact) facts.add(config("NONE"));
      facts.add(new FactInput("sibling", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE, "Preserve request IDs."));
      initial = withNativeAnchors(new DraftInput(new FlowInput(List.of(
          new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /tasks", null, null, null)), List.of()), facts,
          List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
              "/tasks", null, null, null)),
          List.of(new QuestionInput("choice", List.of("entry"), "Keep NONE or use recommended RBAC?",
              new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
                  List.of(first.turnId())))),
          new DraftSettings(false, null)));
      drafts.beginTurn(chat, text);
      try (var ignored = ToolSession.open(chat)) {
        var captured = json.readValue(tools.captureRequirementDraft(initial), RequirementCaptureResult.class);
        assertTrue(captured.accepted(), captured.toString());
      }
      assertTrue(choices.pendingAmendmentPaths(chat).isEmpty());
      var offer = choices.pending(chat, 7).orElseThrow();
      var command = new ChatDecisionCommand();
      command.setAction(ChatEvent.USE_RECOMMENDED_ACTION);
      command.setArtifactType(PolicyChoiceSelection.ARTIFACT_TYPE);
      command.setArtifactHash(offer.hash());
      command.setRevision(7);
      quote = ChatDecisionService.transcriptMarker(command);
      source = conversations.archive().beginInput(chat, quote, json.valueToTree(command));
      conversations.archive().resolveInput(chat, source, quote, json.valueToTree(command));
      drafts.beginTurn(chat, quote);
      assertTrue(choices.confirm(chat, command, 7).isPresent());
    }

    String propertyPath() { return parentPath() + "/1/value"; }
    String parentPath() {
      return "/facts/" + (intentOnPolicyFact ? "policy" : "http-settings") + "/elementIntent/requestedProperties";
    }

    DraftUpdate update(boolean rewriteText, boolean closeQuestion, String... paths) {
      var updates = correction(rewriteText);
      var amendments = java.util.Arrays.stream(paths).map(path -> new AmendmentInput(path, source.turnId(), quote)).toList();
      return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), updates, List.of(),
          List.of(), List.of(), List.of(), List.of(), closeQuestion ? List.of("choice") : List.of(), null, amendments);
    }

    List<FactInput> correction(boolean rewriteText) {
      var old = initial.facts().getFirst();
      var policy = old.policyConflict();
      var selected = new FactInput(old.sourceFactId(), old.interactionIds(), old.kind(), old.polarity(),
          rewriteText ? old.text().replace("has not chosen", "has chosen RBAC") : old.text(), old.fieldMapping(),
          new RequirementPolicyConflict(policy.policyId(), policy.propertyKey(), policy.requestedValue(),
              policy.recommendedValue(), policy.questionId(), Decision.USE_RECOMMENDED),
          intentOnPolicyFact ? intent("RBAC") : null);
      return intentOnPolicyFact ? List.of(selected) : List.of(selected, config("RBAC"));
    }

    DraftInput expected() {
      return RequirementCaptureEditor.apply(initial, update(false, true, propertyPath())).draft();
    }

    String arguments(DraftUpdate update) throws Exception { return json.writeValueAsString(Map.of("changes", update)); }

    FactInput config(String access) {
      return new FactInput("http-settings", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
          "Expose POST /tasks to the external caller.", null, null, intent(access));
    }

    RequirementElementIntent intent(String access) {
      return new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
          "entry", null, null, null, List.of(new PlanProperty("externalRoute", "true"),
              new PlanProperty("accessControlType", access), new PlanProperty("roles", "[\"operator\"]")), List.of());
    }
  }
}
