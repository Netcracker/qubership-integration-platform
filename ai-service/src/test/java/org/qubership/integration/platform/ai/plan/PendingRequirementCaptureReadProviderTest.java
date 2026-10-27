package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
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
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class PendingRequirementCaptureReadProviderTest {
  private static final String LITERAL = "pending-literal-" + "v".repeat(1_300) + "-complete";
  private static final String SCRIPT = "def required = '" + LITERAL + "'\nexchange.message.body = required\n";

  @Inject ObjectMapper json;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject ArtifactBlobStore blobs;
  @Inject GatherRequirementsAgent gather;
  @Inject GatherRequirementsPromptBuilder prompts;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;

  @Test
  void inapplicableFreshPolicyAndOwnerFindingReachTheFirstRequestBeforeCorrectionContinues() throws Exception {
    String chat = "policy-applicability-provider-" + UUID.randomUUID();
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Receive POST /policy-applicability with externalRoute=false and accessControlType=NONE.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false"),
                new PlanProperty("accessControlType", "NONE")), List.of()));
    var policy = new FactInput("access-policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "The internal HTTP route uses accessControlType=NONE.", null,
        new RequirementPolicyConflict(
            org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator.EXTERNAL_RBAC_POLICY,
            "accessControlType", "NONE", "RBAC", "access-choice", null));
    var scriptFact = new FactInput("local-script", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "After entry, run this exact script:\n```groovy\n" + SCRIPT + "```\n", null, null,
        new RequirementElementIntent("local-script", "script", RequirementElementIntent.Placement.LOCAL_NODE,
            "missing-owner", null, "entry", null, List.of(new PlanProperty("script", SCRIPT)), List.of(LITERAL)));
    var proposed = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /policy-applicability", null, null, null)), List.of()), List.of(endpoint, policy, scriptFact),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/policy-applicability", null, null, null)), List.of(new RequirementCaptureInput.QuestionInput(
                "access-choice", List.of("entry"), "Retain the requested access control or use the corporate recommendation?")),
        new DraftSettings(false, null));
    String source = endpoint.text() + "\n" + policy.text() + "\n" + scriptFact.text() + "\nProceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    var oldIntent = scriptFact.elementIntent();
    int scriptOffset = source.indexOf(SCRIPT);
    assertTrue(scriptOffset >= 0);
    var body = new RequirementBodyBinding(RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY,
        oldIntent.elementRef(), "script", RequirementBodyBinding.NativeBodyKind.GROOVY_SCRIPT,
        input.generation(), input.turnId(), scriptOffset, scriptOffset + SCRIPT.length(), RequirementBodyBinding.Encoding.RAW);
    var correctedScript = new FactInput(scriptFact.sourceFactId(), scriptFact.interactionIds(), scriptFact.kind(),
        scriptFact.polarity(), scriptFact.text(), null, null,
        new RequirementElementIntent(oldIntent.elementRef(), oldIntent.elementType(), oldIntent.placement(), "entry",
            oldIntent.successorElementRef(), oldIntent.predecessorElementRef(), oldIntent.parentElementRef(),
            oldIntent.requestedName(), oldIntent.requestedProperties(), oldIntent.exactValues(), body));
    var correctedPolicy = new FactInput(policy.sourceFactId(), policy.interactionIds(), policy.kind(), policy.polarity(),
        policy.text(), policy.fieldMapping(), null, policy.elementIntent());
    var corrected = new DraftInput(proposed.flow(), List.of(endpoint, correctedPolicy, correctedScript),
        proposed.capabilities(), List.of(), proposed.settings());
    var sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var rejected = new AtomicReference<JsonNode>();
    var attempt = new AtomicReference<Revision>();
    var beforeRead = new AtomicReference<Map<String, String>>();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("capture-both-defects", request -> assertLocalFactIdSchemas(request),
            "captureRequirementDraft", json.writeValueAsString(Map.of("draft", proposed))),
        Turn.tool("first-next-refusal", request -> {
          var refusal = lastTool(request, "captureRequirementDraft");
          rejected.set(refusal);
          assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
          assertTrue(java.util.stream.StreamSupport.stream(refusal.path("issues").spliterator(), false)
              .anyMatch(issue -> "INVALID_ELEMENT_OWNER".equals(issue.path("code").asText())
                  && "/draft/facts/local-script/elementIntent".equals(issue.path("path").asText())), refusal.toString());
          assertTrue(java.util.stream.StreamSupport.stream(refusal.path("issues").spliterator(), false)
              .anyMatch(issue -> "INVALID_POLICY_CONFLICT".equals(issue.path("code").asText())
                  && "/draft/facts/access-policy/policyConflict".equals(issue.path("path").asText())), refusal.toString());
          var saved = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          attempt.set(saved);
          assertEquals(refusal.path("issues"), saved.payload().path("issues"));
          assertEquals(json.valueToTree(proposed), saved.payload().path("arguments"));
          assertEquals(input.generation(), saved.payload().path("sourceGeneration").asText());
          assertEquals(input.turnId(), saved.payload().path("sourceTurnId").asText());
          assertTrue(saved.payload().path("acceptedDraftReference").isNull());
          assertTrue(drafts.get(chat).isEmpty());
          assertTrue(drafts.rejectedCaptureFacts(chat).isEmpty());
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
          beforeRead.set(persistedState());
        }, "readRequirementDraft", "{}"),
        Turn.tool("correct-the-full-pending-proposal", request -> {
          var read = lastTool(request, "readRequirementDraft");
          assertEquals("EMPTY", read.path("readiness").asText());
          assertTrue(read.path("draft").isNull());
          var pending = read.path("pendingProposal");
          assertEquals(json.valueToTree(proposed), pending.path("draft"));
          assertEquals(json.valueToTree(attempt.get().reference()), pending.path("attempt"));
          assertEquals(rejected.get().path("issues"), pending.path("issues"));
          assertEquals(input.generation(), pending.path("sourceGeneration").asText());
          assertEquals(input.turnId(), pending.path("sourceTurnId").asText());
          assertTrue(pending.path("base").isNull());
          assertEquals(beforeRead.get(), persistedState());
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", corrected))),
        Turn.tool("continue-after-native-correction", request -> {
          var accepted = lastTool(request, "captureRequirementDraft");
          assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
          assertEquals(json.valueToTree(corrected), accepted.path("draft"));
          assertEquals(corrected, drafts.get(chat).orElseThrow().authoredDraft());
          assertTrue(drafts.rejectedCaptureFacts(chat).isEmpty());
          assertEquals(attempt.get(), artifacts.get(attempt.get().compilationId(), attempt.get().reference()).orElseThrow());
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("native-continuation", request -> {
          var continuation = lastTool(request, "finishRequirementDiscoveryTurn");
          assertTrue(continuation.path("accepted").asBoolean(), continuation.toString());
        }, "The corrected requirements are ready to continue."));
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
    assertEquals(corrected, drafts.get(chat).orElseThrow().authoredDraft());
    JsonNode consumedSource = sourceBefore.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) consumedSource.path("turns").get(0))
        .put("consumedDraftReference", drafts.latestRevision(chat).orElseThrow().artifactId());
    assertEquals(consumedSource, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(limitsBefore, limits());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "true,true"})
  void rejectedOnlyProposalReachesTheProviderWithoutBecomingAnAcceptedBase(
      boolean invalidReference, boolean updateWithoutBase) throws Exception {
    String chat = "pending-read-" + UUID.randomUUID();
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Use POST /pending-capture with externalRoute=false.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false")), List.of()));
    var scriptFact = new FactInput("local-script", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "After entry, run this exact script:\n" + SCRIPT, null, null,
        new RequirementElementIntent(invalidReference ? "entry" : "local-script", "script",
            RequirementElementIntent.Placement.LOCAL_NODE, invalidReference ? "entry" : "missing-owner",
            null, "entry", null, List.of(new PlanProperty("script", SCRIPT)), List.of(LITERAL)));
    var proposed = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "POST /pending-capture", null, null, null)), List.of()), List.of(endpoint, scriptFact),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/pending-capture", null, null, null)), List.of(), new DraftSettings(false, null));
    String source = endpoint.text() + "\n" + scriptFact.text() + "\nProceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    JsonNode sourceBefore = json.valueToTree(conversations.archive().read(chat));
    var limitsBefore = limits();
    var rejected = new AtomicReference<JsonNode>();
    var attempt = new AtomicReference<Revision>();
    var persistedBeforeRead = new AtomicReference<Map<String, String>>();
    var constraintsBeforeRead = new AtomicReference<JsonNode>();
    var latestDiagnostic = new AtomicReference<Revision>();
    var emptyUpdate = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
    String nextTool = updateWithoutBase ? "updateRequirementDraft" : "readRequirementDraft";
    var turns = new java.util.ArrayList<Turn>(List.of(
        Turn.tool("capture", request -> {
          assertLocalFactIdSchemas(request);
          String description = request.parameters().toolSpecifications().stream()
              .filter(spec -> "captureRequirementDraft".equals(spec.name())).findFirst().orElseThrow().description();
          assertTrue(description.contains("until a draft is accepted"), description);
          assertTrue(description.contains("After initialization is accepted"), description);
          assertTrue(description.contains("call captureRequirementDraft again"), description);
          assertFalse(description.contains("Call once for initialization"), description);
          assertTrue(request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
              .map(UserMessage::singleText).anyMatch(text -> text.contains(LITERAL)));
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", proposed))),
        Turn.tool("read", request -> {
          JsonNode refusal = lastTool(request, "captureRequirementDraft");
          rejected.set(refusal);
          assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
          assertEquals("EMPTY", refusal.path("readiness").asText());
          assertTrue(refusal.path("draft").isNull());
          assertTrue(refusal.path("revision").isNull());
          String expectedCode = invalidReference ? "INVALID_ELEMENT_REFERENCE" : "INVALID_ELEMENT_OWNER";
          JsonNode issue = java.util.stream.StreamSupport.stream(refusal.path("issues").spliterator(), false)
              .filter(value -> expectedCode.equals(value.path("code").asText())).findFirst().orElseThrow();
          if (invalidReference) {
            assertEquals("/draft/facts/local-script/elementIntent/elementRef", issue.path("path").asText());
            String message = issue.path("message").asText();
            assertTrue(message.contains("elementRef='entry'"), message);
            assertTrue(message.contains("ownerInteractionId='entry'"), message);
            assertTrue(message.contains("business interaction IDs=[entry]"), message);
            assertTrue(message.contains("A LOCAL_NODE elementRef must differ from every business interaction ID."), message);
          }
          Revision immutable = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
              Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
          attempt.set(immutable);
          var payload = immutable.payload();
          assertFalse(payload.path("accepted").asBoolean());
          assertEquals("captureRequirementDraft", payload.path("toolName").asText());
          assertEquals(input.generation(), payload.path("sourceGeneration").asText());
          assertEquals(input.turnId(), payload.path("sourceTurnId").asText());
          assertTrue(payload.path("acceptedDraftReference").isNull());
          assertEquals(json.valueToTree(proposed), payload.path("arguments"));
          assertEquals(refusal.path("issues"), payload.path("issues"));
          assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
          assertTrue(drafts.latestRevision(chat).isEmpty());
          assertFalse(drafts.wasCapturedThisTurn(chat));
          constraintsBeforeRead.set(json.valueToTree(drafts.repairConstraints(chat)));
          persistedBeforeRead.set(persistedState());
        }, nextTool, updateWithoutBase ? json.writeValueAsString(Map.of("changes", emptyUpdate)) : "{}")));
    java.util.function.Consumer<ChatRequest> checkNextResult = request -> {
      JsonNode result = lastTool(request, nextTool);
      if (updateWithoutBase) {
        assertFalse(result.path("accepted").asBoolean(), result.toString());
        JsonNode issue = result.path("issues").get(0);
        assertEquals("DRAFT_NOT_FOUND", issue.path("code").asText());
        assertEquals("/changes", issue.path("path").asText());
        assertTrue(issue.path("message").asText().startsWith(
            "No accepted requirement draft exists. captureRequirementDraft initializes the draft;"
            + " updateRequirementDraft requires an accepted base."
            + " If initialization was rejected, correct the full candidate and call captureRequirementDraft again."),
            issue.path("message").asText());
        Revision failedUpdate = artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
            Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
        assertEquals("updateRequirementDraft", failedUpdate.payload().path("toolName").asText());
        assertTrue(failedUpdate.payload().path("acceptedDraftReference").isNull());
        assertEquals(input.generation(), failedUpdate.payload().path("sourceGeneration").asText());
        assertEquals(input.turnId(), failedUpdate.payload().path("sourceTurnId").asText());
        assertEquals(json.valueToTree(emptyUpdate), failedUpdate.payload().path("arguments"));
        assertEquals(result.path("issues"), failedUpdate.payload().path("issues"));
        latestDiagnostic.set(failedUpdate);
        persistedBeforeRead.set(persistedState());
      } else {
        JsonNode pending = result.path("pendingProposal");
        assertTrue(pending.isObject(), result.toString());
        assertEquals(json.valueToTree(proposed), pending.path("draft"));
        assertEquals(json.valueToTree(attempt.get().reference()), pending.path("attempt"));
        assertEquals(input.generation(), pending.path("sourceGeneration").asText());
        assertEquals(input.turnId(), pending.path("sourceTurnId").asText());
        assertTrue(pending.path("base").isNull());
        assertEquals("captureRequirementDraft", pending.path("toolName").asText());
        assertEquals(rejected.get().path("issues"), pending.path("issues"));
        assertTrue(pending.path("draft").toString().contains(LITERAL));
        assertEquals(persistedBeforeRead.get(), persistedState(),
            "Read must not append or rewrite durable authority, source, or approvals.");
      }
      assertEquals("EMPTY", result.path("readiness").asText());
      assertTrue(result.path("draft").isNull(), "Pending content must stay separate from the accepted draft.");
      assertTrue(result.path("revision").isNull());
      assertFalse(result.path("changed").asBoolean());
      assertTrue(drafts.latestRevision(chat).isEmpty());
      assertTrue(drafts.get(chat).isEmpty());
      assertFalse(drafts.wasCapturedThisTurn(chat));
      assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
      assertEquals(constraintsBeforeRead.get(), json.valueToTree(drafts.repairConstraints(chat)));
      assertEquals(attempt.get(), artifacts.get(attempt.get().compilationId(), attempt.get().reference()).orElseThrow());
    };
    turns.add(updateWithoutBase
        ? Turn.tool("read-after-rejected-update", checkNextResult, "readRequirementDraft", "{}")
        : Turn.text("pending-proposal", checkNextResult,
            "The rejected proposal is available for correction; it is not an accepted requirement draft."));
    if (updateWithoutBase) {
      turns.add(Turn.text("original-pending-proposal", request -> {
        var result = lastTool(request, "readRequirementDraft");
        var pending = result.path("pendingProposal");
        assertTrue(pending.isObject(), result.toString());
        assertEquals(json.valueToTree(proposed), pending.path("draft"));
        assertEquals(json.valueToTree(attempt.get().reference()), pending.path("attempt"));
        assertEquals(input.generation(), pending.path("sourceGeneration").asText());
        assertEquals(input.turnId(), pending.path("sourceTurnId").asText());
        assertTrue(pending.path("base").isNull());
        assertEquals("captureRequirementDraft", pending.path("toolName").asText());
        assertEquals(rejected.get().path("issues"), pending.path("issues"));
        assertTrue(pending.path("draft").toString().contains(LITERAL));
        assertEquals("EMPTY", result.path("readiness").asText());
        assertTrue(result.path("draft").isNull());
        assertTrue(result.path("revision").isNull());
        assertFalse(result.path("changed").asBoolean());
        assertTrue(drafts.latestRevision(chat).isEmpty());
        assertTrue(drafts.get(chat).isEmpty());
        assertFalse(drafts.wasCapturedThisTurn(chat));
        assertEquals(sourceBefore, json.valueToTree(conversations.archive().read(chat)));
        assertEquals(constraintsBeforeRead.get(), json.valueToTree(drafts.repairConstraints(chat)));
        assertEquals(attempt.get(), artifacts.get(attempt.get().compilationId(), attempt.get().reference()).orElseThrow());
        assertEquals(latestDiagnostic.get(), artifacts.latest(sessions.current(chat).orElseThrow().compilationId(),
            Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow());
        assertEquals(persistedBeforeRead.get(), persistedState(), "Read must not mutate any durable state or credit.");
      }, "The complete original proposal remains available without creating an accepted base."));
    }
    var script = new ScriptedStreamingChatModel(turns.toArray(Turn[]::new));
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      script.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    gather.chat(chat, prompts.wrap(chat, source, "en"), new InvocationParameters())
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    assertEquals(updateWithoutBase ? 4 : 3, script.requests().size());
    assertEquals(limitsBefore, limits());
  }

  private void assertLocalFactIdSchemas(ChatRequest request) {
    for (String[] field : List.of(
        new String[] {"captureRequirementDraft", "draft", "facts"},
        new String[] {"updateRequirementDraft", "changes", "addFacts"},
        new String[] {"updateRequirementDraft", "changes", "updateFacts"})) {
      var tool = request.parameters().toolSpecifications().stream()
          .filter(spec -> field[0].equals(spec.name())).findFirst().orElseThrow();
      var input = org.junit.jupiter.api.Assertions.assertInstanceOf(JsonObjectSchema.class,
          tool.parameters().properties().get(field[1]));
      var facts = org.junit.jupiter.api.Assertions.assertInstanceOf(JsonArraySchema.class,
          input.properties().get(field[2]));
      var fact = org.junit.jupiter.api.Assertions.assertInstanceOf(JsonObjectSchema.class, facts.items());
      var id = org.junit.jupiter.api.Assertions.assertInstanceOf(JsonStringSchema.class,
          fact.properties().get("sourceFactId"));
      assertTrue(fact.required().contains("sourceFactId"));
      assertTrue(id.description().contains(RequirementCaptureEditor.localIdPattern()), id.description());
      assertTrue(id.description().contains("Stable local fact ID"), id.description());
      assertTrue(id.description().contains("complete value"), id.description());
    }
  }

  private JsonNode lastTool(ChatRequest request, String tool) {
    try {
      var result = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((left, right) -> right).orElseThrow();
      assertEquals(tool, result.toolName());
      return json.readTree(result.text());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
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
}
