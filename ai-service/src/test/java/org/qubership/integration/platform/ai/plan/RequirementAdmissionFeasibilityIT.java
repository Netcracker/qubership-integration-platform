package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Explicit local feasibility probe; admission, source, schema, and catalog services are not mocked. */
@QuarkusTest
@TestProfile(RequirementAdmissionFeasibilityIT.LocalCatalog.class)
@EnabledIfSystemProperty(named = "qip.step0.evidence", matches = ".+")
class RequirementAdmissionFeasibilityIT {
  public static class LocalCatalog extends TestArtifactBlobStoreProducer.Profile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.rest-client.catalog-api.url",
          System.getProperty("qip.step0.catalog", "http://localhost:8091"));
    }
  }

  @Inject ObjectMapper json;
  @Inject RequirementCaptureTools tools;
  @Inject RequirementDraftStore drafts;
  @Inject PolicyChoiceSelection policyChoices;
  @Inject ConversationService conversations;
  @Inject DeterministicElementSchemaService schemas;
  @Inject RequirementDiscoveryCapability discovery;
  @Inject AppConfig config;
  @Inject ArtifactBlobStore blobs;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject ConversationApiResolutions resolutions;
  @Inject ProductPipelineRunStore runs;
  @Inject ProductPipelineRunSupport runSupport;
  @Inject LlmRunBudget runBudget;
  @Inject ToolCallFingerprintStore captureCredits;
  @Inject @RestClient CatalogRestClient catalog;
  @InjectMock RateLimitStreamingChatModel provider;

  private final Path evidence = Path.of(System.getProperty("qip.step0.evidence", "."));
  private final Map<ScriptedStreamingChatModel, List<Object>> receivedRequests = new java.util.IdentityHashMap<>();
  private final Map<String, JsonNode> diagnostics = new LinkedHashMap<>();

  @BeforeEach
  void useFreshEvidenceNamespace() throws Exception {
    Path manifest = evidence.resolve("manifest.json");
    assertFalse(Files.exists(manifest) && json.readTree(manifest.toFile()).has("finalEvidenceHashes"),
        "Pinned evidence is immutable. Copy source fixtures into a fresh evidence directory.");
    assertTrue(Files.isRegularFile(evidence.resolve("pinned-sources.json")),
        "Copy the pinned source fixtures and their hash manifest into the evidence directory.");
    for (JsonNode pin : json.readTree(evidence.resolve("pinned-sources.json").toFile())) {
      Path fixture = evidence.resolve(Path.of(pin.path("copy").asText()).getFileName());
      assertEquals(pin.path("sha256").asText(), sha256(Files.readAllBytes(fixture)), fixture.toString());
    }
  }

  @Test
  void savedInterpretationRepairsAndAuthorValueProtectionUseTheInjectedPath() throws Exception {
    JsonNode fixture = json.readTree(evidence.resolve("saved-c03.json").toFile());
    var health = catalog.searchSystems(new CatalogSystemSearchRequest("Salesforce WFM"));
    assertFalse(health.isEmpty(), "The actual catalog must be available");
    assertTrue(schemas.hasElementSchema("async-api-trigger"));
    assertTrue(schemas.hasElementSchema("http-sender"));
    assertInjected(tools, "elementSchemas", schemas);
    assertInjected(tools, "conversations", conversations);
    var results = new ArrayList<Object>();
    for (String variant : List.of("remove-generated-capability", "reclassify-fact", "change-explicit-chain-name")) {
      var input = (ObjectNode) fixture.path("draft").deepCopy();
      var update = emptyUpdate();
      if (variant.equals("remove-generated-capability")) {
        update.withArray("removeCapabilityInteractionIds").add("createTask");
      } else {
        var fact = (ObjectNode) input.path("facts").get(0).deepCopy();
        if (variant.equals("reclassify-fact")) fact.put("kind", "BEHAVIOR");
        else ((ObjectNode) fact.path("elementIntent")).put("requestedName", "Unauthorized replacement");
        update.withArray("updateFacts").add(fact);
      }
      String expected = variant.equals("remove-generated-capability") ? "AUTHOR_AMENDMENT_REQUIRED"
          : variant.equals("reclassify-fact") ? "REPAIR_FACT_LOSS" : "ELEMENT_LITERAL_SOURCE_MISMATCH";
      String chat = "step0-" + variant + "-" + UUID.randomUUID();
      var originalRef = new java.util.concurrent.atomic.AtomicReference<String>();
      var script = new ScriptedStreamingChatModel(
          Turn.tool("initial", request -> assertTrue(requestText(request).contains(
              input.path("facts").get(0).path("elementIntent").path("requestedName").asText())),
              "captureRequirementDraft", json.writeValueAsString(Map.of("draft", input))),
          Turn.tool("repair", request -> {
            assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
            originalRef.set(drafts.latestRevision(chat).orElseThrow().artifactId());
          }, "updateRequirementDraft", json.writeValueAsString(Map.of("changes", update))),
          Turn.tool("finish", request -> {
            assertFalse(lastTool(request).path("accepted").asBoolean());
            assertTrue(lastTool(request).path("issues").toString().contains(expected), lastTool(request).toString());
            assertEquals(originalRef.get(), drafts.latestRevision(chat).orElseThrow().artifactId());
            inspectRejectedCandidate(chat, request);
          }, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
          Turn.text("settled", request -> {}, "Native refusal retained."));
      StageOutcome outcome = execute(chat, fixture.path("source").asText(), script);
      assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
      assertEquals(json.treeToValue(input, RequirementCaptureInput.DraftInput.class),
          drafts.get(chat).orElseThrow().authoredDraft());
      save(variant, chat, script, outcome);
      results.add(Map.of("variant", variant, "nativeRejection", expected, "acceptedUnchanged", true,
          "sourceGeneration", conversations.archive().read(chat).generation(),
          "outcome", outcome.outcomeClass(), "liveCatalogSystems", health));
    }
    json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("saved-boundary-results.json").toFile(), results);
  }

  @Test
  void invalidInternalIdentityCanBeRepairedAndFinishThroughNativeContinuation() throws Exception {
    var fixture = json.readTree(evidence.resolve("id-control.json").toFile());
    var valid = (ObjectNode) fixture.path("draft");
    var invalid = valid.deepCopy();
    ((ObjectNode) invalid.path("facts").get(0)).put("sourceFactId", "chain name");
    String chat = "step0-id-control-" + UUID.randomUUID();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("invalid", request -> {}, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", invalid))),
        Turn.tool("corrected", request -> {
          assertFalse(lastTool(request).path("accepted").asBoolean());
          assertTrue(lastTool(request).path("issues").toString().contains("INVALID_VALUE"), lastTool(request).toString());
          assertTrue(lastTool(request).path("issues").toString().contains("[A-Za-z][A-Za-z0-9_-]{0,63}"),
              "The next provider request must contain the complete native identity constraint.");
        }, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", valid))),
        Turn.tool("continue", request -> assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString()),
            "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("done", request -> assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString()),
            "Ready for the normal next stage."));
    var outcome = execute(chat, fixture.path("source").asText(), script);
    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass(), outcome.toString());
    assertTrue(drafts.get(chat).orElseThrow().readyForPlan());
    assertEquals(json.treeToValue(valid, RequirementCaptureInput.DraftInput.class),
        drafts.get(chat).orElseThrow().authoredDraft());
    save("id-control", chat, script, outcome);
  }

  @Test
  void modeledPolicyAnswersDoNotEstablishIndependentAuthorAuthority() throws Exception {
    for (String variant : List.of("policy-recommended-reply", "policy-conflicting-reply")) {
      String chat = variant + "-" + UUID.randomUUID();
      String source = "Create external POST /policy-check with externalRoute=true and accessControlType=NONE. "
          + "If RBAC is selected use exactly role qip-viewer. Proceed without IDS.";
      var sourceInput = archiveSource(chat, source);
      var unresolved = policyDraft(sourceInput.turnId());
      var first = new ScriptedStreamingChatModel(
          Turn.tool("question", request -> {}, "captureRequirementDraft",
              json.writeValueAsString(Map.of("draft", unresolved))),
          Turn.tool("wait", request -> assertTrue(lastTool(request).path("accepted").asBoolean(),
              lastTool(request).toString()), "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
          Turn.text("asked", request -> assertTrue(lastTool(request).path("accepted").asBoolean(),
              lastTool(request).toString()), "Keep NONE or use recommended RBAC?"));
      var awaiting = executeArchived(chat, source, first);
      assertEquals(StageOutcomeClass.NEEDS_INPUT, awaiting.outcomeClass(), awaiting.toString());
      save(variant + "-question", chat, first, awaiting);

      String reply = variant.equals("policy-recommended-reply") ? "Use recommended RBAC." : "Keep NONE.";
      var answer = emptyUpdate();
      answer.withArray("updateFacts").add(json.valueToTree(policyFact(RequirementPolicyConflict.Decision.USE_RECOMMENDED)));
      answer.withArray("removeQuestionIds").add("policy-choice");
      var script = new ScriptedStreamingChatModel(
          Turn.tool("modeled-answer", request -> {}, "updateRequirementDraft",
              json.writeValueAsString(Map.of("changes", answer))),
          Turn.tool("retain-unselected-question", request -> {
            assertFalse(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
            assertTrue(lastTool(request).path("issues").toString().contains("POLICY_RESPONSE_REQUIRED"));
            assertTrue(drafts.confirmedAnswers(chat).isEmpty());
            assertTrue(drafts.trustedPolicyChoices(chat).isEmpty());
            assertEquals(unresolved, drafts.get(chat).orElseThrow().authoredDraft());
            inspectRejectedCandidate(chat, request);
          }, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
          Turn.text("settled", request -> {}, "The persisted choice still requires a validated option selection."));
      var outcome = execute(chat, reply, script);
      assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
      assertTrue(outcome.recoveryCause().findings().stream().anyMatch(finding ->
          "AUTHOR_QUESTION_NOT_SUPPORTED".equals(finding.code())),
          "The old question has not reviewed the new reply; this is producer work, not proof of missing author data.");
      assertEquals(unresolved, drafts.get(chat).orElseThrow().authoredDraft());
      save(variant, chat, script, outcome);
    }
  }

  @Test
  void independentlySelectedPropertyCorrectionPassesNativePreservationChecksBeforeAdmissionChanges() throws Exception {
    String chat = "typed-policy-correction-" + UUID.randomUUID();
    String source = "Create external POST /policy-check with externalRoute=true and accessControlType=NONE. "
        + "If RBAC is selected use exactly role qip-viewer. Proceed without IDS.";
    var input = archiveSource(chat, source);
    var unresolved = policyDraft(input.turnId());
    var first = new ScriptedStreamingChatModel(
        Turn.tool("question", request -> {}, "captureRequirementDraft", json.writeValueAsString(Map.of("draft", unresolved))),
        Turn.tool("wait", request -> assertTrue(lastTool(request).path("accepted").asBoolean()),
            "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("asked", request -> {}, "Keep NONE or use recommended RBAC?"));
    var awaiting = executeArchived(chat, source, first);
    assertEquals(StageOutcomeClass.NEEDS_INPUT, awaiting.outcomeClass());
    save("typed-policy-question", chat, first, awaiting);
    var offer = policyChoices.pending(chat, 7).orElseThrow();
    var command = new ChatDecisionCommand();
    command.setArtifactType(PolicyChoiceSelection.ARTIFACT_TYPE);
    command.setArtifactHash(offer.hash());
    command.setRevision(7);
    command.setAction("use-recommended");
    String marker = ChatDecisionService.transcriptMarker(command);
    var selectedSource = archiveSource(chat, marker, command);
    var selected = policyChoices.confirm(chat, command, 7).orElseThrow();
    var correction = emptyUpdate();
    correction.withArray("updateFacts").add(json.valueToTree(policyFact(RequirementPolicyConflict.Decision.USE_RECOMMENDED)));
    correction.withArray("updateFacts").add(json.valueToTree(accessFact("RBAC")));
    correction.withArray("removeQuestionIds").add("policy-choice");
    var proposed = RequirementCaptureEditor.apply(unresolved, json.convertValue(correction, DraftUpdate.class)).draft();
    var brief = RequirementBriefProjector.project(new RequirementBrief("Check policy handling", List.of(), List.of(),
        List.of(), List.of(), "", null, "", RequirementCaptureProjection.facts(proposed))
        .withFlow(RequirementCaptureProjection.flow(proposed)));
    var graph = new ChainPlanGraph("1.0", new ChainSection("Policy.Check", ""), List.of(
        new ChainPlanNode("entry", "http-trigger", "Entry", null, null, accessProperties("RBAC"))), List.of());
    var consumers = new RequirementRevisionAdmission.NativeInputs(brief, graph);
    var script = new ScriptedStreamingChatModel(
        Turn.tool("selected-policy-correction", request -> assertTrue(requestText(request).contains(marker)),
            "updateRequirementDraft", json.writeValueAsString(Map.of("changes", correction))),
        Turn.tool("inspect-native-refusal", request -> {
          assertFalse(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
          assertEquals(unresolved, drafts.get(chat).orElseThrow().authoredDraft());
          assertTrue(drafts.confirmedAnswers(chat).isEmpty());
          assertEquals(selectedSource.turnId(), conversations.archive().pendingInputs(chat).getFirst().id());
          var report = inspectRejectedCandidate(chat, request, consumers, true);
          assertTrue(report.changedPaths().containsAll(List.of("/facts/access/elementIntent/requestedProperties/1/value",
              "/facts/policy/policyConflict/decision", "/openQuestions/policy-choice/text", "/recordOrder/openQuestions/0")),
              report.changedPaths().toString());
          assertTrue(report.findings().stream().anyMatch(finding -> finding.checker().equals("author-origin")
              && finding.evidenceReferences().contains(selected.reference())));
          for (String checker : List.of("RequirementBriefCoverageValidator", "RequestedElementValues",
              "CompilerQualityValidator", "CompilerSecurityValidator")) {
            assertTrue(report.findings().stream().anyMatch(finding -> checker.equals(finding.checker())
                && finding.outcome() == RequirementRevisionAdmission.PreservationOutcome.VERIFIED), checker);
          }
        }, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("settled", request -> {}, "Diagnostic preservation verified; live admission is still unchanged."));
    var outcome = executeArchived(chat, marker, script);
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass(), outcome.toString());
    assertEquals(unresolved, drafts.get(chat).orElseThrow().authoredDraft());
    assertEquals(selectedSource.turnId(), conversations.archive().pendingInputs(chat).getFirst().id());
    save("typed-policy-correction", chat, script, outcome);
  }

  private DraftInput policyDraft(String sourceTurnId) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "POST /policy-check", "External policy endpoint", null, null)), List.of()),
        List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Check policy handling."),
            accessFact("NONE"), policyFact(null)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/policy-check",
            null, null, null)),
        List.of(new QuestionInput("policy-choice", List.of("entry"), "Keep NONE or use recommended RBAC?",
            new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE,
                List.of(sourceTurnId)))), new DraftSettings(false, null));
  }

  private FactInput policyFact(RequirementPolicyConflict.Decision decision) {
    return new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "Requested NONE; corporate RBAC recommendation.", null,
        new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType", "NONE",
            "RBAC", "policy-choice", decision));
  }

  private FactInput accessFact(String accessControl) {
    return new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
        "External route uses NONE; if RBAC is selected use qip-viewer.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, accessProperties(accessControl), List.of()));
  }

  private List<PlanProperty> accessProperties(String accessControl) {
    return List.of(new PlanProperty("externalRoute", "true"), new PlanProperty("accessControlType", accessControl),
        new PlanProperty("roles", "[\"qip-viewer\"]"));
  }

  private StageOutcome execute(String chat, String source, ScriptedStreamingChatModel script) throws Exception {
    archiveSource(chat, source);
    return executeArchived(chat, source, script);
  }

  private ConversationArchive.Input archiveSource(String chat, String source) throws Exception {
    return archiveSource(chat, source, null);
  }

  private ConversationArchive.Input archiveSource(String chat, String source, ChatDecisionCommand command) throws Exception {
    var decision = command == null ? null : json.valueToTree(command);
    var input = conversations.archive().beginInput(chat, source, decision);
    conversations.archive().resolveInput(chat, input, source, decision);
    String sourceRead = conversations.archive().readSource(chat, input.turnId(), 0, 16000);
    assertEquals(source, json.readTree(sourceRead.substring(sourceRead.indexOf('\n') + 1)).path("rawText").asText());
    return input;
  }

  private StageOutcome executeArchived(String chat, String source, ScriptedStreamingChatModel script) throws Exception {
    var snapshots = new ArrayList<Object>();
    receivedRequests.put(script, snapshots);
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      // LangChain4j can append to the request's message list after this call.
      snapshots.add(requestSnapshot(request));
      script.chat(request, handler);
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    ProductPipelineProfile profile;
    try (var in = Files.newInputStream(Path.of("src/main/resources/product-pipelines/profiles/create-chain-v2.yaml"))) {
      profile = ProductPipelineProfileParser.parse(in);
    }
    var context = new StageExecutionContext("run-" + chat, chat, "requirement-discovery", "execution-1", "attempt-1",
        profile, null, List.of(), Map.of("userText", source));
    var signals = discovery.execute(context).collect().asList().await().atMost(Duration.ofSeconds(60));
    script.assertComplete();
    return signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).map(CapabilitySignal.Completed::outcome).findFirst().orElseThrow();
  }

  private record RejectedCandidate(Revision attemptRevision,
      RequirementDraftStore.RequirementCaptureAttempt attempt, Revision baseRevision, RequirementDraft base,
      RequirementCaptureInput.DraftUpdate changes, RequirementCaptureInput.DraftInput candidate,
      ConversationArchive.Document source) {}

  private RejectedCandidate rejectedCandidate(String chat, Reference attemptRef, RequirementDraftStore store,
      CompilationArtifacts repository, ConversationService sourceService) {
    String compilation = store.latestRevision(chat).orElseThrow().compilationId();
    Revision attemptRevision = repository.get(compilation, attemptRef).orElseThrow();
    var attempt = repository.payload(attemptRevision, RequirementDraftStore.RequirementCaptureAttempt.class);
    assertFalse(attempt.accepted());
    assertEquals("updateRequirementDraft", attempt.toolName());
    Revision baseRevision = repository.get(compilation, attempt.acceptedDraftReference()).orElseThrow();
    assertEquals(baseRevision.reference(), store.latestRevision(chat).orElseThrow().reference());
    RequirementDraft base = repository.payload(baseRevision, RequirementDraft.class);
    var changes = json.convertValue(attempt.arguments(), RequirementCaptureInput.DraftUpdate.class);
    var edit = RequirementCaptureEditor.apply(base.authoredDraft(), changes);
    assertTrue(edit.accepted(), edit.issues().toString());
    var source = sourceService.archive().read(chat);
    assertEquals(attempt.sourceGeneration(), source.generation());
    assertTrue(source.activeTurns().stream().anyMatch(turn -> turn.id().equals(attempt.sourceTurnId())
        && "READY".equals(turn.status())));
    return new RejectedCandidate(attemptRevision, attempt, baseRevision, base, changes, edit.draft(), source);
  }

  private void inspectRejectedCandidate(String chat, ChatRequest nextRequest) {
    inspectRejectedCandidate(chat, nextRequest, null);
  }

  private RequirementRevisionAdmission.PreservationReport inspectRejectedCandidate(String chat,
      ChatRequest nextRequest, RequirementRevisionAdmission.NativeInputs nativeInputs) {
    return inspectRejectedCandidate(chat, nextRequest, nativeInputs, false);
  }

  private RequirementRevisionAdmission.PreservationReport inspectRejectedCandidate(String chat,
      ChatRequest nextRequest, RequirementRevisionAdmission.NativeInputs nativeInputs, boolean expectVerified) {
    var pinned = drafts.rejectedCaptureRevision(chat).orElseThrow().reference();
    RejectedCandidate candidate = rejectedCandidate(chat, pinned, drafts, artifacts, conversations);
    assertEquals(json.valueToTree(candidate.changes()), lastToolCallArguments(nextRequest).path("changes"));
    var before = state(chat);
    var report = RequirementRevisionAdmission.evaluate(chat, pinned, drafts, artifacts, conversations, schemas, nativeInputs);
    if (expectVerified) assertEquals(RequirementRevisionAdmission.PreservationOutcome.VERIFIED, report.outcome(), report.toString());
    else assertNotEquals(RequirementRevisionAdmission.PreservationOutcome.VERIFIED, report.outcome());
    assertEquals(candidate.attemptRevision().reference(), report.attemptReference());
    assertEquals(candidate.baseRevision().reference(), report.baseReference());
    assertEquals(candidate.source().generation(), report.sourceGeneration());
    assertEquals(candidate.attempt().sourceTurnId(), report.sourceTurnId());
    assertFalse(report.changedPaths().isEmpty());
    assertFalse(report.findings().isEmpty());
    assertTrue(report.nativeFindings().stream().anyMatch(issue -> candidate.attempt().issues().stream()
        .anyMatch(retained -> retained.code().equals(issue.code()) && retained.path().equals(issue.path()))));
    assertEquals(before, state(chat), "Evaluating the rejected candidate must not mutate active state.");
    var reloadedArtifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    var reloadedSessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    var reloadedSource = new ConversationService(config, new ConversationArchive(blobs, json));
    var reloadedDrafts = new RequirementDraftStore(reloadedArtifacts, reloadedSessions, reloadedSource);
    RejectedCandidate reloaded = rejectedCandidate(chat, pinned, reloadedDrafts, reloadedArtifacts, reloadedSource);
    assertEquals(json.valueToTree(candidate), json.valueToTree(reloaded));
    assertEquals(drafts.confirmedAnswers(chat), reloadedDrafts.confirmedAnswers(chat));
    var reloadedReport = RequirementRevisionAdmission.evaluate(chat, pinned, reloadedDrafts, reloadedArtifacts,
        reloadedSource, schemas, nativeInputs);
    assertEquals(json.valueToTree(report), json.valueToTree(reloadedReport));
    var after = state(chat);
    assertEquals(before, after, "Reconstructing and reloading the candidate must not mutate active state.");
    var diagnostic = new LinkedHashMap<String, Object>();
    diagnostic.put("checker", report);
    diagnostic.put("checkerReloadEqual", true);
    diagnostic.putAll(Map.of("candidate", candidate, "before", before, "after", after,
        "candidateReloadEqual", true, "actualNextRequest", requestSnapshot(nextRequest),
        "checkerFindingsSentToModel", false,
        "durableRunLedger", "ABSENT: direct discovery capability; no run-controller resume claim",
        "providerAccounting", "Scripted provider replaces the budgeted transport; native budget remains unchanged"));
    diagnostics.put(chat, json.valueToTree(diagnostic));
    return report;
  }

  private JsonNode state(String chat) {
    var state = new LinkedHashMap<String, Object>();
    var accepted = drafts.latestRevision(chat).orElseThrow();
    state.put("compilation", sessions.current(chat).orElseThrow());
    state.put("acceptedRevision", accepted);
    state.put("draft", drafts.get(chat).orElseThrow());
    state.put("source", conversations.archive().read(chat));
    state.put("confirmedAnswers", drafts.confirmedAnswers(chat));
    state.put("bindings", resolutions.assessments(chat));
    state.put("approved", drafts.isApproved(chat, accepted.reference()));
    state.put("protection", drafts.repairConstraints(chat));
    state.put("capturedThisTurn", drafts.wasCapturedThisTurn(chat));
    state.put("directive", drafts.turnDirective(chat));
    state.put("lastRejection", drafts.lastCaptureRejection(chat).orElse(null));
    state.put("repairPaths", drafts.activeRequirementRepairPaths(chat));
    assertTrue(runs.load("run-" + chat).isEmpty());
    assertTrue(runs.loadByConversation(chat).isEmpty());
    state.put("durableRun", "ABSENT");
    state.put("runAttributes", runSupport.runAttributes("run-" + chat));
    state.put("modelBudget", runBudget.state("run-" + chat));
    state.put("captureCreditFingerprints", captureCredits.softFingerprints(chat));
    var persisted = new TreeMap<String, Object>();
    for (String key : blobs.list("")) {
      var blob = blobs.getVersioned(key).orElseThrow();
      persisted.put(key, Map.of("version", blob.version(), "sha256", sha256(blob.content())));
    }
    state.put("persistedBlobs", persisted);
    return json.valueToTree(state);
  }

  private JsonNode lastToolCallArguments(ChatRequest request) {
    try {
      var message = request.messages().stream().filter(dev.langchain4j.data.message.AiMessage.class::isInstance)
          .map(dev.langchain4j.data.message.AiMessage.class::cast).filter(message1 -> message1.hasToolExecutionRequests())
          .reduce((a, b) -> b).orElseThrow();
      var call = message.toolExecutionRequests().getLast();
      assertEquals("updateRequirementDraft", call.name());
      return json.readTree(call.arguments());
    } catch (Exception error) {
      throw new AssertionError(request.messages().toString(), error);
    }
  }

  private static Map<String, Object> requestSnapshot(ChatRequest request) {
    return Map.of("messages", request.messages().stream().map(Object::toString).toList(),
        "toolNames", request.parameters().toolSpecifications().stream().map(spec -> spec.name()).toList());
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private void save(String name, String chat, ScriptedStreamingChatModel script, StageOutcome outcome) throws Exception {
    json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(name + "-requests.json").toFile(),
        receivedRequests.get(script));
    json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(name + "-outcome.json").toFile(), Map.of(
        "runId", "run-" + chat, "outcome", outcome, "source", conversations.archive().read(chat),
        "draft", drafts.get(chat).orElseThrow(), "limits", Map.of("requests", config.llm().maxRequestsPerRun(),
            "tokens", config.llm().maxTokensPerRun(), "activeTime", config.llm().maxActiveTimePerRun().toString()),
        "scope", "Real injected discovery/agent/tools/validators; scripted inference, isolated in-memory blob storage"));
    if (diagnostics.containsKey(chat)) {
      json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(name + "-diagnostic.json").toFile(),
          diagnostics.get(chat));
    }
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      var message = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((a, b) -> b).orElseThrow();
      return json.readTree(message.text());
    } catch (Exception error) {
      throw new AssertionError(request.messages().toString(), error);
    }
  }

  private static String requestText(ChatRequest request) {
    return request.messages().toString();
  }

  private ObjectNode emptyUpdate() {
    var node = json.createObjectNode();
    for (String key : List.of("addInteractions", "updateInteractions", "removeInteractionIds", "addTransitions",
        "removeTransitions", "addFacts", "updateFacts", "removeFactIds", "setCapabilities",
        "removeCapabilityInteractionIds", "addQuestions", "updateQuestions", "removeQuestionIds", "amendments")) {
      node.putArray(key);
    }
    node.putNull("settings");
    return node;
  }

  private static void assertInjected(Object bean, String name, Object expected) throws Exception {
    var instance = ClientProxy.unwrap(bean);
    var field = instance.getClass().getDeclaredField(name);
    field.setAccessible(true);
    assertSame(ClientProxy.unwrap(expected), ClientProxy.unwrap(field.get(instance)));
  }
}
