package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.chat.service.ChatExecutionService;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModel;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.RequirementAnalysisCapture;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.RequirementCaptureTools;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeSidecarApi;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

/** Real controller/card/source/admission handoff with inference replaced at provider entry only. */
@QuarkusTest
@TestProfile(PolicyChoiceNativeIT.LocalServices.class)
@EnabledIfSystemProperty(named = "qip.policy.evidence", matches = ".+")
class PolicyChoiceNativeIT {
  public static class LocalServices extends TestArtifactBlobStoreProducer.Profile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.rest-client.catalog-api.url", "http://localhost:8091",
          "quarkus.rest-client.knowledge-sidecar.url", "http://localhost:8095",
          "qip.knowledge.sidecar.url", "http://localhost:8095");
    }
  }

  private static final Duration WAIT = Duration.ofSeconds(90);
  private static final String ROUTE = "/policy-native-check";
  private static final String SOURCE = "Create external POST " + ROUTE
      + " with externalRoute=true and accessControlType=NONE. If RBAC is selected use exactly role qip-viewer. "
      + "Check policy handling. Proceed without IDS.";
  private static final String APPROVAL_REPLY = "Approve the selected RBAC endpoint requirements?";

  @Inject ObjectMapper json;
  @Inject CreateChainApplicationFacade facade;
  @Inject ChatDecisionService decisions;
  @Inject ChatExecutionService chat;
  @Inject PolicyChoiceSelection choices;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject ArtifactBlobStore blobs;
  @Inject ProductPipelineRunStore runs;
  @Inject ToolCallFingerprintStore credits;
  @Inject LlmRunBudget budget;
  @Inject AppConfig config;
  @Inject @RestClient CatalogRestClient catalog;
  @Inject @RestClient KnowledgeSidecarApi knowledge;
  @InjectMock RateLimitStreamingChatModel streaming;
  @InjectMock RateLimitChatModel synchronous;

  private Path evidence;
  private String conversation;
  private final List<JsonNode> requests = new ArrayList<>();
  private final List<Map<String, Object>> synchronousReplies = new ArrayList<>();

  @BeforeEach
  void freshEvidenceAndRealServices(TestInfo info) throws Exception {
    Path root = Path.of(System.getProperty("qip.policy.evidence")).toAbsolutePath();
    Files.createDirectories(root);
    evidence = root.resolve(info.getTestMethod().orElseThrow().getName());
    if (!info.getDisplayName().equals(info.getTestMethod().orElseThrow().getName() + "()")) {
      evidence = evidence.resolve(info.getDisplayName().replaceAll("[^A-Za-z0-9_-]", "_"));
    }
    Files.createDirectories(evidence.getParent());
    assertFalse(Files.exists(evidence), "Use a fresh evidence namespace; previous evidence is immutable.");
    Files.createDirectory(evidence);
    requests.clear();
    synchronousReplies.clear();
    conversation = "native-policy-" + UUID.randomUUID();
    for (Object bean : List.of(facade, decisions, chat, choices, tools, drafts, conversations, artifacts, sessions, runs)) {
      assertFalse(org.mockito.Mockito.mockingDetails(ClientProxy.unwrap(bean)).isMock(),
          "Application services must remain real: " + bean.getClass().getName());
    }
    var ready = knowledge.ready();
    var pack = knowledge.activePackage().packageRef();
    assertNotNull(ready.status());
    assertEquals("CERTIFIED", pack.certificationStatus());
    assertEquals("CIP@1.0.0", pack.packageKey());
    var systems = catalog.searchSystems(new CatalogSystemSearchRequest("Salesforce WFM"));
    assertFalse(systems.isEmpty(), "The actual read-only catalog must be reachable.");
    save("services.json", Map.of("knowledgeReady", ready, "knowledgePackage", pack, "catalogSystems", systems));
    save("limits.json", Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "activeTime", config.llm().maxActiveTimePerRun().toString(),
        "scope", "Unchanged service limits; scripted model transport does not prove production model accounting."));
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      remember("synchronous", request);
      assertEquals(1, request.messages().size(), "Only the recorded locale and approval prompts are scripted.");
      String prompt = assertInstanceOf(UserMessage.class, request.messages().getFirst()).singleText();
      String reply;
      String purpose;
      if (prompt.startsWith("Identify the language used for the prose instructions")) {
        assertTrue(synchronousReplies.isEmpty(), "Locale detection must run once before the product run.");
        assertTrue(prompt.contains(SOURCE), prompt);
        assertTrue(runs.loadByConversation(conversation).isEmpty());
        purpose = "locale";
        reply = "en";
      } else {
        assertEquals(List.of("locale"), synchronousReplies.stream().map(item -> item.get("purpose")).toList(),
            "Unexpected or repeated synchronous inference: " + prompt);
        var run = runs.loadByConversation(conversation).orElseThrow();
        assertEquals(RunStatus.WAITING_FOR_APPROVAL, run.run().status());
        assertEquals("requirement-analysis", run.run().currentStageId());
        var revision = artifacts.latest(run.run().runId(), Kind.REQUIREMENT_BRIEF).orElseThrow();
        var brief = artifacts.payload(revision, RequirementBrief.class);
        String reference = brief.summary().strip() + "\n" + brief.approvedDraftText().strip();
        String expected = """
            Write a short chat question asking the user to approve the current pipeline stage candidate \
            (stage id for context only, do not emphasize it): requirement-analysis.
            Write in the pinned response locale en. This locale is authoritative; do not infer a
            different language from the reference text. Preserve product terms:
            ---
            %s
            ---
            Ask the approval question only. Do not tell the reader which word to type or reply with. Reply with \
            only the user-facing question text. No markdown fences, no quotes, no preamble.\
            """.formatted(reference);
        assertEquals(expected, prompt, "An unrecognized synchronous prompt must never reach a paid provider.");
        assertEquals("Accept the validated RBAC policy on the external endpoint.", brief.summary());
        purpose = "requirement-analysis-approval";
        reply = APPROVAL_REPLY;
      }
      synchronousReplies.add(Map.of("requestSequence", requests.size(), "purpose", purpose, "response", reply));
      saveUnchecked("synchronous-scripted-responses.json", synchronousReplies);
      return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
    }).when(synchronous).chat(any(ChatRequest.class));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"separate-facts", "combined-policy-anchor-fact"})
  void validatedCardAmendsTheOwnedPropertyAtomicallyAndReachesTheActualAnalysisApproval(String representation) throws Exception {
    boolean combined = "combined-policy-anchor-fact".equals(representation);
    var source = archive(SOURCE);
    DraftInput unresolved = policyDraft(source.turnId(), combined);
    var resolvedFacts = unresolved.facts().stream().map(fact -> "policy".equals(fact.sourceFactId())
        ? policyFact(RequirementPolicyConflict.Decision.USE_RECOMMENDED, combined, "RBAC")
        : "access".equals(fact.sourceFactId()) ? accessFact("RBAC") : fact).toList();
    var resolved = new DraftInput(unresolved.flow(), resolvedFacts, unresolved.capabilities(), List.of(), unresolved.settings());
    String propertyPath = "/facts/" + (combined ? "policy" : "access") + "/elementIntent/requestedProperties/1/value";
    save("representation.json", Map.of("shape", representation, "unresolved", unresolved, "expectedResolved", resolved,
        "exactAmendmentPaths", List.of("/facts/policy/policyConflict/decision", propertyPath)));
    var initial = questionScript(unresolved);
    install(initial);
    save("start-events.json", facade.start(new StartCreateChainCommand(conversation, SOURCE, UUID.randomUUID().toString(),
        InputOrigin.TRUSTED)).collect().asList().await().atMost(WAIT));
    initial.assertComplete();
    assertWaitingPolicy(unresolved);
    var card = decisions.openDecision(conversation).orElseThrow();
    assertEquals(PolicyChoiceSelection.ARTIFACT_TYPE, card.artifactType());
    assertEquals(List.of(ChatEvent.KEEP_REQUESTED_ACTION, ChatEvent.USE_RECOMMENDED_ACTION), card.actions());
    save("policy-card.json", card);
    save("waiting-state.json", state());

    // An obsolete card cannot change protected content or invoke another provider request.
    JsonNode beforeStale = state();
    int callCount = requests.size();
    var stale = command(card, ChatEvent.USE_RECOMMENDED_ACTION);
    stale.setRevision(card.revision() - 1);
    var staleRequest = new org.qubership.integration.platform.ai.chat.model.ChatRequest();
    staleRequest.setConversationId(conversation);
    staleRequest.setMessage("");
    staleRequest.setDecision(stale);
    save("stale-command-sse.json", chat.streamV1Sse(staleRequest).collect().asList().await().atMost(WAIT));
    JsonNode afterStale = state();
    for (String field : List.of("run", "acceptedDraft", "acceptedReference", "confirmedAnswers", "trustedChoices",
        "captureCredits", "protection", "budget")) assertEquals(beforeStale.get(field), afterStale.get(field), field);
    var staleSource = conversations.archive().read(conversation);
    var staleTurn = staleSource.activeTurns().stream()
        .filter(turn -> turn.id().equals(ConversationArchive.latestInputId(staleSource))).findFirst().orElseThrow();
    assertEquals("READY", staleTurn.status());
    assertNull(staleTurn.consumedDraftReference());
    assertEquals(stale.getRevision(), staleTurn.metadata().findValue("resolvedDecision").path("revision").asLong());
    assertEquals(callCount, requests.size());
    save("stale-source-state.json", afterStale);
    var currentCard = decisions.openDecision(conversation).orElseThrow();
    assertEquals(PolicyChoiceSelection.ARTIFACT_TYPE, currentCard.artifactType());

    var selection = new AtomicReference<PolicyChoiceSelection.TrustedSelection>();
    var atomicUpdate = new AtomicReference<ObjectNode>();
    var acceptedCapture = new AtomicReference<Reference>();
    var handoffDraft = new AtomicReference<RequirementDraft>();
    var consumedDraft = new AtomicReference<Reference>();
    var selected = new ScriptedStreamingChatModel(
        new Turn("atomic-policy-amendment", request -> {
          assertAdvertised(request, "updateRequirementDraft");
          var trusted = choices.trusted(conversation);
          assertEquals(1, trusted.size());
          selection.set(trusted.getFirst());
          var choice = selection.get().selection();
          assertEquals(RequirementPolicyConflict.Decision.USE_RECOMMENDED, choice.decision());
          assertEquals("RBAC", choice.selectedValue());
          assertNotEquals(source.turnId(), choice.sourceTurnId());
          assertNotEquals(staleTurn.id(), choice.sourceTurnId());
          var clicked = conversations.archive().read(conversation).activeTurns().stream()
              .filter(turn -> turn.id().equals(choice.sourceTurnId())).findFirst().orElseThrow();
          assertNotNull(clicked.metadata().findValue("resolvedDecision"));
          String prompt = request.messages().toString();
          for (String required : List.of("updateRequirementDraft", "accessControlType", "entry", "RBAC",
              choice.sourceTurnId(), clicked.message().content())) assertTrue(prompt.contains(required), prompt);
          assertEquals(unresolved, drafts.get(conversation).orElseThrow().authoredDraft());
          var update = emptyUpdate();
          update.withArray("updateFacts").add(json.valueToTree(policyFact(RequirementPolicyConflict.Decision.USE_RECOMMENDED, combined, "RBAC")));
          if (!combined) update.withArray("updateFacts").add(json.valueToTree(accessFact("RBAC")));
          update.withArray("removeQuestionIds").add("policy-choice");
          update.withArray("amendments").add(json.valueToTree(new AmendmentInput(
              "/facts/policy/policyConflict/decision", choice.sourceTurnId(), clicked.message().content())));
          update.withArray("amendments").add(json.valueToTree(new AmendmentInput(
              propertyPath, choice.sourceTurnId(), clicked.message().content())));
          atomicUpdate.set(update);
          saveUnchecked("validated-selection.json", selection.get());
          saveUnchecked("atomic-model-update.json", update);
        }, handler -> emitTool(handler, "atomic-policy-amendment", "updateRequirementDraft",
            Map.of("changes", atomicUpdate.get()))),
        Turn.tool("continue", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertTrue(lastTool(request).path("issues").isEmpty(), lastTool(request).toString());
          var current = drafts.get(conversation).orElseThrow();
          assertEquals(resolved, current.authoredDraft());
          assertEquals("RBAC", accessValue(current.authoredDraft()));
          assertEquals(RequirementPolicyConflict.Decision.USE_RECOMMENDED,
              drafts.confirmedAnswers(conversation).getFirst().policy().decision());
          assertTrue(current.authoredDraft().openQuestions().isEmpty());
          acceptedCapture.set(drafts.latestRevision(conversation).orElseThrow().reference());
          saveUnchecked("native-admission.json", lastTool(request));
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("discovery-finished", request -> {
          assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          handoffDraft.set(drafts.get(conversation).orElseThrow());
          assertTrue(handoffDraft.get().readyForPlan());
        }, "The selected policy and native property are saved. Continue to normal analysis."),
        Turn.tool("actual-analysis", request -> {
          assertNotNull(handoffDraft.get());
          var run = runs.loadByConversation(conversation).orElseThrow();
          assertEquals("requirement-analysis", run.run().currentStageId());
          Reference reference = null;
          var lineage = new ArrayList<Object>();
          for (String stageId : List.of("requirement-discovery", "uploaded-spec-import", "import-stage")) {
            var published = run.run().stages().stream().filter(stage -> stageId.equals(stage.stageId()))
                .findFirst().orElseThrow();
            assertEquals(StageStatus.SUCCEEDED, published.status(), stageId);
            var output = published.outputRefs().stream().filter(ref -> ref.kind() == Kind.REQUIREMENT_DRAFT)
                .findFirst().orElseThrow();
            var revision = artifacts.get(run.run().runId(), output).orElseThrow();
            assertEquals(acceptedCapture.get().contentHash(), output.contentHash(), stageId);
            assertEquals(handoffDraft.get(), artifacts.payload(revision, RequirementDraft.class), stageId);
            if (reference != null) assertTrue(revision.inputs().contains(reference), stageId + ": " + revision.inputs());
            lineage.add(Map.of("stageId", stageId, "reference", output, "inputs", revision.inputs()));
            reference = output;
          }
          consumedDraft.set(reference);
          assertTrue(request.messages().toString().contains(reference.artifactId()), request.messages().toString());
          var admitted = artifacts.payload(artifacts.get(run.run().runId(), reference).orElseThrow(), RequirementDraft.class);
          assertEquals(handoffDraft.get(), admitted);
          assertEquals("RBAC", accessValue(admitted.authoredDraft()));
          saveUnchecked("published-draft-lineage.json", lineage);
          saveUnchecked("actual-analysis-entry-state.json", state());
        }, "captureRequirementBrief", json.writeValueAsString(Map.of("capture", new RequirementAnalysisCapture(
            "Check policy handling.", List.of("External POST " + ROUTE), List.of(),
            "Accept the validated RBAC policy on the external endpoint.", List.of(), List.of())))));
    install(selected);
    var click = new org.qubership.integration.platform.ai.chat.model.ChatRequest();
    click.setConversationId(conversation);
    click.setMessage("");
    click.setDecision(command(currentCard, ChatEvent.USE_RECOMMENDED_ACTION));
    save("typed-click-sse.json", chat.streamV1Sse(click).collect().asList().await().atMost(WAIT));
    selected.assertComplete();
    var run = runs.loadByConversation(conversation).orElseThrow();
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run.run().status(), run.toString());
    assertEquals("requirement-analysis", run.run().currentStageId());
    var approval = decisions.openDecision(conversation).orElseThrow();
    assertNotEquals(PolicyChoiceSelection.ARTIFACT_TYPE, approval.artifactType());
    assertEquals(APPROVAL_REPLY, approval.question());
    assertEquals(List.of("locale", "requirement-analysis-approval"),
        synchronousReplies.stream().map(item -> item.get("purpose")).toList());
    var briefRevision = artifacts.latest(run.run().runId(), Kind.REQUIREMENT_BRIEF).orElseThrow();
    var brief = artifacts.payload(briefRevision, RequirementBrief.class);
    assertEquals(consumedDraft.get().artifactId(), brief.approvedDraftReference());
    assertEquals(handoffDraft.get().facts(), brief.facts());
    assertTrue(briefRevision.inputs().contains(consumedDraft.get()));
    assertNotNull(acceptedCapture.get());
    assertNotNull(selection.get());
    assertEquals(0, credits.softCreditsUsed(conversation));
    assertEquals(0, budget.state(run.run().runId()).grants());
    assertNull(budget.state(run.run().runId()).refusal());
    save("approval-card.json", approval);
    save("final-state.json", state());
    save("proof.json", Map.of("controllerWait", "WAITING_FOR_INPUT/STAGE_CLARIFICATION", "typedSelection", selection.get(),
        "atomicNativeCapture", acceptedCapture.get(), "consumedPipelineDraft", consumedDraft.get(),
        "brief", briefRevision.reference(), "nextStage", "requirement-analysis", "finalStatus", run.run().status(),
        "scope", "Real CDI/controller/card/archive/tools/validators and real read-only catalog/knowledge; scripted inference; in-memory storage; no construction or runtime claim."));
    finishEvidence();
  }

  @Test
  void anOppositeRawReplyCannotSelectTheRecommendedOption() throws Exception {
    var source = archive(SOURCE);
    DraftInput unresolved = policyDraft(source.turnId());
    var first = questionScript(unresolved);
    install(first);
    facade.start(new StartCreateChainCommand(conversation, SOURCE, UUID.randomUUID().toString(), InputOrigin.TRUSTED))
        .collect().asList().await().atMost(WAIT);
    first.assertComplete();
    assertWaitingPolicy(unresolved);
    save("policy-card.json", decisions.openDecision(conversation).orElseThrow());
    var base = drafts.latestRevision(conversation).orElseThrow().reference();
    var reply = archive("Keep NONE.");
    var update = emptyUpdate();
    update.withArray("updateFacts").add(json.valueToTree(policyFact(RequirementPolicyConflict.Decision.USE_RECOMMENDED)));
    update.withArray("updateFacts").add(json.valueToTree(accessFact("RBAC")));
    update.withArray("removeQuestionIds").add("policy-choice");
    update.withArray("amendments").add(json.valueToTree(new AmendmentInput(
        "/facts/access/elementIntent/requestedProperties/1/value", reply.turnId(), "Keep NONE.")));
    int before = requests.size();
    drafts.beginTurn(conversation, "Keep NONE.");
    try (var ignored = ToolSession.open(conversation)) {
      var refusal = json.readTree(tools.updateRequirementDraft(json.treeToValue(update, DraftUpdate.class)));
      assertFalse(refusal.path("accepted").asBoolean(), refusal.toString());
      assertTrue(refusal.path("issues").toString().contains("POLICY_RESPONSE_REQUIRED"), refusal.toString());
      assertEquals(base, drafts.latestRevision(conversation).orElseThrow().reference());
      assertEquals(unresolved, drafts.get(conversation).orElseThrow().authoredDraft());
      save("raw-reply-native-refusal.json", refusal);
    }
    assertEquals(before, requests.size());
    assertWaitingPolicy(unresolved);
    assertTrue(choices.trusted(conversation).isEmpty());
    assertTrue(drafts.confirmedAnswers(conversation).isEmpty());
    assertTrue(artifacts.history(runs.loadByConversation(conversation).orElseThrow().run().runId(), Kind.REQUIREMENT_BRIEF).isEmpty());
    assertEquals(List.of("locale"), synchronousReplies.stream().map(item -> item.get("purpose")).toList());
    save("final-state.json", state());
    save("negative-scope.json", Map.of("proof", "Native capture refuses an opposite model proposal from a fresh raw reply without a validated card selection.",
        "controller", "The initial real controller remains waiting; this bounded negative does not dispatch another controller turn."));
    finishEvidence();
  }

  private ScriptedStreamingChatModel questionScript(DraftInput unresolved) throws Exception {
    return new ScriptedStreamingChatModel(
        Turn.tool("policy-question", request -> {}, "captureRequirementDraft",
            json.writeValueAsString(Map.of("draft", unresolved))),
        Turn.tool("stay-for-choice", request -> assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString()),
            "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask-policy", request -> {}, "Keep NONE or use recommended RBAC?"));
  }

  private void assertWaitingPolicy(DraftInput unresolved) {
    var run = runs.loadByConversation(conversation).orElseThrow();
    assertEquals(RunStatus.WAITING_FOR_INPUT, run.run().status(), run.toString());
    assertEquals("requirement-discovery", run.run().currentStageId());
    var pending = assertInstanceOf(PendingAction.Clarify.class, facade.snapshot(conversation).orElseThrow().pendingAction());
    assertEquals(PipelineGates.STAGE_CLARIFICATION, pending.gateId());
    assertEquals(unresolved, drafts.get(conversation).orElseThrow().authoredDraft());
  }

  private ConversationArchive.Input archive(String text) {
    var input = conversations.archive().beginInput(conversation, text, null);
    conversations.archive().resolveInput(conversation, input, text);
    return input;
  }

  private void install(ScriptedStreamingChatModel script) {
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      remember("streaming", request);
      script.chat(request, call.getArgument(1));
      return null;
    }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  private void remember(String provider, ChatRequest request) {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("sequence", requests.size() + 1);
    snapshot.put("provider", provider);
    snapshot.put("messages", request.messages().stream().map(Object::toString).toList());
    snapshot.put("toolNames", request.parameters().toolSpecifications() == null ? List.of()
        : request.parameters().toolSpecifications().stream().map(spec -> spec.name()).toList());
    snapshot.put("stateAtProviderEntry", state());
    // The live ChatRequest message list is mutated after entry; retain immutable values immediately.
    requests.add(json.valueToTree(snapshot));
    saveUnchecked("provider-entry-requests.json", requests);
  }

  private JsonNode state() {
    var value = new LinkedHashMap<String, Object>();
    value.put("conversationId", conversation);
    value.put("source", conversations.archive().read(conversation));
    value.put("acceptedDraft", drafts.get(conversation).orElse(null));
    value.put("acceptedReference", drafts.latestRevision(conversation).map(revision -> revision.reference()).orElse(null));
    value.put("confirmedAnswers", drafts.confirmedAnswers(conversation));
    value.put("trustedChoices", choices.trusted(conversation));
    value.put("captureCredits", credits.softFingerprints(conversation));
    var run = runs.loadByConversation(conversation);
    value.put("run", run.orElse(null));
    value.put("budget", run.map(doc -> budget.state(doc.run().runId())).orElse(null));
    var compilation = sessions.current(conversation).map(session -> session.compilationId());
    value.put("captureCompilation", compilation.orElse(null));
    value.put("protection", compilation.map(id -> artifacts.history(id, Kind.REQUIREMENT_CAPTURE_PROTECTION)).orElse(List.of()));
    var hashes = new TreeMap<String, Object>();
    for (String key : blobs.list("")) {
      var blob = blobs.getVersioned(key).orElseThrow();
      hashes.put(key, Map.of("version", blob.version(), "sha256", digest(blob.content())));
    }
    value.put("blobHashes", hashes);
    return json.valueToTree(value);
  }

  private DraftInput policyDraft(String sourceTurnId) {
    return policyDraft(sourceTurnId, false);
  }

  private DraftInput policyDraft(String sourceTurnId, boolean combined) {
    var facts = new ArrayList<FactInput>();
    facts.add(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Check policy handling."));
    if (!combined) facts.add(accessFact("NONE"));
    facts.add(policyFact(null, combined, "NONE"));
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "POST " + ROUTE, "External policy endpoint", null, null)), List.of()),
        List.copyOf(facts),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, ROUTE, null, null, null)),
        List.of(new QuestionInput("policy-choice", List.of("entry"), "Keep NONE or use recommended RBAC?",
            new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
                List.of(sourceTurnId)))), new DraftSettings(false, null));
  }

  private FactInput policyFact(RequirementPolicyConflict.Decision decision) {
    return policyFact(decision, false, null);
  }

  private FactInput policyFact(RequirementPolicyConflict.Decision decision, boolean combined, String accessControl) {
    return new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Requested NONE; corporate RBAC recommendation.", null,
        new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE",
            "RBAC", "policy-choice", decision), combined ? accessFact(accessControl).elementIntent() : null);
  }

  private FactInput accessFact(String value) {
    return new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "External route uses NONE; if RBAC is selected use qip-viewer.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "true"),
                new PlanProperty("accessControlType", value), new PlanProperty("roles", "[\"qip-viewer\"]")), List.of()));
  }

  private String accessValue(DraftInput draft) {
    return draft.facts().stream().map(FactInput::elementIntent).filter(java.util.Objects::nonNull)
        .filter(intent -> "entry".equals(intent.elementRef()) && "entry".equals(intent.ownerInteractionId()))
        .flatMap(intent -> intent.requestedProperties().stream()).filter(property -> "accessControlType".equals(property.key()))
        .findFirst().orElseThrow().value();
  }

  private ChatDecisionCommand command(ChatEvent.Decision card, String action) {
    var command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactType(card.artifactType());
    command.setArtifactHash(card.artifactHash());
    command.setRevision(card.revision());
    return command;
  }

  private ObjectNode emptyUpdate() {
    var node = json.createObjectNode();
    for (String name : List.of("addInteractions", "updateInteractions", "removeInteractionIds", "addTransitions",
        "removeTransitions", "addFacts", "updateFacts", "removeFactIds", "setCapabilities",
        "removeCapabilityInteractionIds", "addQuestions", "updateQuestions", "removeQuestionIds", "amendments")) node.putArray(name);
    node.putNull("settings");
    return node;
  }

  private void emitTool(StreamingChatResponseHandler handler, String id, String name, Object arguments) {
    try {
      handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
          .id(id).name(name).arguments(json.writeValueAsString(arguments)).build())).build());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private void assertAdvertised(ChatRequest request, String tool) {
    assertTrue(request.parameters().toolSpecifications().stream().anyMatch(spec -> tool.equals(spec.name())));
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((first, last) -> last).orElseThrow().text());
    } catch (Exception error) {
      throw new AssertionError(request.messages().toString(), error);
    }
  }

  private void save(String name, Object value) throws Exception {
    json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(name).toFile(), value);
  }

  private void saveUnchecked(String name, Object value) {
    try {
      save(name, value);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private void finishEvidence() throws Exception {
    save("provider-entry-requests.json", requests);
    save("inference-counts.json", Map.of("total", requests.size(),
        "synchronous", requests.stream().filter(request -> "synchronous".equals(request.path("provider").asText())).count(),
        "streaming", requests.stream().filter(request -> "streaming".equals(request.path("provider").asText())).count(),
        "scriptedSynchronousResponses", synchronousReplies,
        "scope", "Recorded provider calls; mocked transports do not establish production token/time accounting."));
    var run = runs.loadByConversation(conversation).orElseThrow();
    var captures = sessions.current(conversation).map(session -> session.compilationId()).orElseThrow();
    var history = new LinkedHashMap<String, Object>();
    for (Kind kind : Kind.values()) {
      var revisions = new ArrayList<>(artifacts.history(run.run().runId(), kind));
      if (!captures.equals(run.run().runId())) revisions.addAll(artifacts.history(captures, kind));
      if (!revisions.isEmpty()) history.put(kind.name(), revisions);
    }
    save("artifact-history.json", history);
    var hashes = new TreeMap<String, String>();
    try (var files = Files.list(evidence)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) hashes.put(file.getFileName().toString(), digest(Files.readAllBytes(file)));
    }
    save("manifest.json", Map.of("conversationId", conversation, "runId", run.run().runId(), "finalEvidenceHashes", hashes,
        "scope", "Native CDI/controller/card/source/admission and analysis approval; scripted inference only; storage is isolated in memory; construction and runtime NOT_RUN."));
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }
}
