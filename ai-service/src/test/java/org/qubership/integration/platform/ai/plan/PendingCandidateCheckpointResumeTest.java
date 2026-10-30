package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Joins native candidate retention to resume behavior after an explicitly seeded internal halt. */
class PendingCandidateCheckpointResumeTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String CHAT = "pending-candidate-checkpoint";
  private static final String STAGE = "requirement-discovery";

  @Test
  void alreadyParkedUnsupportedCandidateSurvivesReloadRetryAndReviseWithoutCalls() throws Exception {
    var blobs = new InMemoryArtifactBlobStore();
    var clock = Clock.systemUTC();
    var artifacts = new CompilationArtifacts(blobs, JSON, clock);
    var sessions = new CompilationSessions(blobs, JSON, clock);
    var archive = new ConversationArchive(blobs, JSON);
    var conversations = conversations(archive);
    ObjectNode fixture;
    try (var input = getClass().getResourceAsStream("/recovery/mini-topology-first-capture.json")) {
      assertNotNull(input);
      fixture = (ObjectNode) JSON.readTree(input);
    }
    String source = fixture.path("source").asText();
    var input = archive.beginInput(CHAT, source, null);
    archive.resolveInput(CHAT, input, source);
    var drafts = new RequirementDraftStore(artifacts, sessions, conversations);
    var tools = captureTools(drafts, conversations);
    drafts.beginTurn(CHAT, source);
    var initial = JSON.treeToValue(fixture.path("draft"), DraftInput.class);
    var rejected = call(tools, "captureRequirementDraft", Map.of("draft", initial));
    assertFalse(rejected.accepted());
    assertTrue(rejected.issues().stream().anyMatch(issue -> "INVALID_ELEMENT_REFERENCE".equals(issue.code())));

    var pending = call(tools, "readRequirementDraft", Map.of());
    assertNotNull(pending.pendingProposal());
    assertEquals(initial, pending.pendingProposal().draft());
    assertEquals(rejected.issues(), pending.pendingProposal().issues());
    assertEquals(input.generation(), pending.pendingProposal().sourceGeneration());
    assertEquals(input.turnId(), pending.pendingProposal().sourceTurnId());
    assertEquals("captureRequirementDraft", pending.pendingProposal().toolName());
    assertEquals(artifacts.latest(sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow().reference(),
        pending.pendingProposal().attempt());
    assertNull(pending.pendingProposal().base());
    assertNull(pending.draft());
    assertNull(pending.revision());
    assertEquals(RequirementCaptureResult.Readiness.EMPTY, pending.readiness());

    String runId = sessions.active(CHAT);
    var profile = new ProductPipelineProfile(1, "pending-checkpoint", "1", List.of(), List.of(
        new ProfileStage(STAGE, STAGE, List.of(), List.of(new ArtifactTypeRef("requirement-draft", 2)),
            null, null, new RetryPolicy(0, 1L))), new TerminalPolicy(STAGE, "PLAN_APPROVED"), List.of(STAGE));
    var manifest = new RunManifest(runId, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile-sha", "baseline", "baseline-sha", List.of(new DependencyClosureEntry(STAGE, "1", "stage-sha")),
        "closure-sha", new KnowledgePackageRef("knowledge", "1", "1.0.0", "checksum", "CERTIFIED", "certificate"),
        "24.4", List.of(new ArtifactTypeRef("requirement-draft", 2)), null);
    var command = new StartOrResumeCommand(CHAT, runId, profile, manifest);
    var runs = new ProductPipelineRunStore(blobs, JSON, clock);
    var producerCalls = new AtomicInteger();
    var providerCalls = new AtomicInteger();
    var provider = mock(FailureNarrativeAgent.class, invocation -> {
      providerCalls.incrementAndGet();
      throw new AssertionError("A parked checkpoint must not call the diagnosis provider.");
    });
    StageCapability park = new StageCapability() {
      public String capabilityId() { return STAGE; }
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        producerCalls.incrementAndGet();
        // Seed the halt through the real park path; semantic-gap detection is outside this test.
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
            StageOutcomeClass.INTERNAL_FAILURE, "No available result discharges the retained semantic obligation.")));
      }
    };
    var capabilities = new StageCapabilityRegistry(List.of(park));
    var recovery = new RecoveryAttemptLedger();
    var productArtifacts = new ProductPipelineArtifactStore(artifacts);
    var support = CreateChainTestOrchestrator.supportBuilder(runs, productArtifacts, capabilities, clock)
        .failureNarrative(new FailureNarrative(provider)).recoveryLedger(recovery).build();
    support.bootstrap(command, "checkpoint-flow");
    var cause = new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, rejected.issues().stream()
        .map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true)).toList(), "");
    var key = recovery.key(STAGE, cause, "", runs.load(runId).orElseThrow().transitions());
    for (String reason : List.of(recovery.recordRepair(key, ""), recovery.recordCleanRebuild(key, ""))) {
      var current = runs.load(runId).orElseThrow();
      long revision = current.run().runRevision();
      runs.commit(revision, new LogicalCommit(runId, revision, RunStatus.RUNNING, STAGE, current.run().stages(),
          new StageAttempt("spent-" + revision, STAGE, revision + 1, StageStatus.RUNNING,
              clock.instant(), clock.instant(), List.of(), null),
          new RunTransition(revision, revision + 1, RunStatus.RUNNING, RunStatus.RUNNING, STAGE, clock.instant(), reason)));
    }
    var parked = assertInstanceOf(StageDecision.WaitForInput.class,
        support.stageExecutor().execute(runId, STAGE).await().indefinitely().decision());
    assertEquals(PipelineGates.RECOVERY_INTERNAL, PipelineGates.gateOf(parked.prompt()).orElseThrow());
    assertEquals(1, producerCalls.get());
    producerCalls.set(0);

    var config = mock(AppConfig.class);
    when(config.llm()).thenReturn(mock(AppConfig.LlmConfig.class));
    var modelBudget = new LlmRunBudget(blobs, JSON, config);
    // A persisted reservation makes request/token preservation observable without a transport call.
    modelBudget.reserve(runId, 17, 23);
    var budgetBefore = modelBudget.state(runId);
    var checkpoint = runs.load(runId).orElseThrow();
    String replayPrompt = PipelineGates.tagHaltIdentity(parked.prompt(), ToolCallFingerprints.failureSignature(
        PipelineGates.recoveryTechnicalDetailsOf(parked.prompt()).orElseThrow()));
    assertEquals(replayPrompt, checkpoint.transitions().getLast().reason());
    var sourceBefore = archive.read(CHAT);
    var before = new LinkedHashMap<String, byte[]>();
    blobs.list("").forEach(path -> before.put(path, blobs.get(path).orElseThrow()));
    assertEquals(1, recovery.repairsUsed(checkpoint.transitions(), key, InputOrigin.TRUSTED));
    assertEquals(1, recovery.cleanRebuildsUsed(checkpoint.transitions(), key));
    assertTrue(artifacts.history(runId, Kind.APPROVAL_RECORD).isEmpty());

    var restoredArtifacts = new CompilationArtifacts(blobs, JSON, clock);
    var restoredRuns = new ProductPipelineRunStore(blobs, JSON, clock);
    var restoredSupport = CreateChainTestOrchestrator.supportBuilder(restoredRuns,
        new ProductPipelineArtifactStore(restoredArtifacts), capabilities, clock)
        .failureNarrative(new FailureNarrative(provider)).recoveryLedger(new RecoveryAttemptLedger()).build()
        .withLlmBudget(new LlmRunBudget(blobs, JSON, config));
    var runtime = new CreateChainTestOrchestrator(restoredSupport, restoredRuns);
    assertWait(runtime.startOrResume(command).collect().asList().await().indefinitely(), replayPrompt);
    for (String action : List.of(PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION)) {
      assertWait(runtime.acceptInput(new AcceptInputCommand(runId, action)).collect().asList().await().indefinitely(),
          replayPrompt);
    }
    var restoredArchive = new ConversationArchive(blobs, JSON);
    var restoredConversations = conversations(restoredArchive);
    var restoredDrafts = new RequirementDraftStore(restoredArtifacts,
        new CompilationSessions(blobs, JSON, clock), restoredConversations);
    restoredDrafts.beginTurn(CHAT, source);
    var restored = call(captureTools(restoredDrafts, restoredConversations), "readRequirementDraft", Map.of());

    assertEquals(pending, restored);
    assertEquals(checkpoint, restoredRuns.load(runId).orElseThrow());
    assertEquals(sourceBefore, restoredArchive.read(CHAT));
    assertTrue(restoredDrafts.get(CHAT).isEmpty());
    assertTrue(restoredDrafts.latestApprovedRevision(CHAT).isEmpty());
    assertEquals(budgetBefore, new LlmRunBudget(blobs, JSON, config).state(runId));
    assertEquals(0, producerCalls.get());
    assertEquals(0, providerCalls.get());
    assertEquals(before.keySet(), new java.util.HashSet<>(blobs.list("")));
    before.forEach((path, bytes) -> assertArrayEquals(bytes, blobs.get(path).orElseThrow(), path));
  }

  @Test
  void rejectedCapabilityDoesNotAuthorizeTheNextProjection() throws Exception {
    var blobs = new InMemoryArtifactBlobStore();
    var clock = Clock.systemUTC();
    var artifacts = new CompilationArtifacts(blobs, JSON, clock);
    var sessions = new CompilationSessions(blobs, JSON, clock);
    var archive = new ConversationArchive(blobs, JSON);
    var conversations = conversations(archive);
    ObjectNode fixture;
    try (var stream = getClass().getResourceAsStream("/recovery/mini-topology-first-capture.json")) {
      assertNotNull(stream);
      fixture = (ObjectNode) JSON.readTree(stream);
    }
    String source = fixture.path("source").asText();
    var opened = archive.beginInput(CHAT, source, null);
    archive.resolveInput(CHAT, opened, source);
    var drafts = new RequirementDraftStore(artifacts, sessions, conversations);
    var tools = captureTools(drafts, conversations);
    drafts.beginTurn(CHAT, source);
    var initial = JSON.treeToValue(fixture.path("draft"), DraftInput.class);
    var rejected = call(tools, "captureRequirementDraft", Map.of("draft", initial));
    assertFalse(rejected.accepted());
    var candidate = localScript(initial);
    var saved = call(tools, "captureRequirementDraft", Map.of("draft", candidate));

    assertTrue(saved.accepted(), saved.toString());
    assertNull(drafts.repairConstraints(CHAT));
    assertEquals(candidate, drafts.get(CHAT).orElseThrow().authoredDraft());
    assertTrue(drafts.pendingProposal(CHAT).isEmpty());
  }

  @Test
  void replayAfterRebuildKeepsTheBaselineAndDropsThePendingProjection() throws Exception {
    var blobs = new InMemoryArtifactBlobStore();
    var clock = Clock.systemUTC();
    var artifacts = new CompilationArtifacts(blobs, JSON, clock);
    var sessions = new CompilationSessions(blobs, JSON, clock);
    var archive = new ConversationArchive(blobs, JSON);
    var conversations = conversations(archive);
    String author = "Submit one task before Friday.";
    var input = archive.beginInput(CHAT, author, null);
    archive.resolveInput(CHAT, input, author);
    var drafts = new RequirementDraftStore(artifacts, sessions, conversations);
    var tools = captureTools(drafts, conversations);
    drafts.beginTurn(CHAT, author);
    var fact = new RequirementCaptureInput.FactInput("invalid id", List.of(),
        RequirementCaptureInput.FactKind.GOAL, RequirementCaptureInput.Polarity.POSITIVE, "Submit one task",
        null, null, null, new RequirementCaptureInput.ClaimEvidenceInput(input.turnId(), "Submit one task"));
    var candidate = new DraftInput(new RequirementCaptureInput.FlowInput(List.of(), List.of()),
        List.of(fact), List.of(), List.of(), new RequirementCaptureInput.DraftSettings(false, null));
    var rejected = call(tools, "captureRequirementDraft", Map.of("draft", candidate));
    assertFalse(rejected.accepted(), rejected.toString());
    assertTrue(drafts.semanticBaseline(CHAT).isEmpty(), "A rejected candidate does not create a claim");
    assertTrue(drafts.pendingProposal(CHAT).isPresent());
    assertNull(drafts.repairConstraints(CHAT));
    assertTrue(drafts.get(CHAT).isEmpty());
    int protectionCount = artifacts.history(sessions.active(CHAT), Kind.REQUIREMENT_CAPTURE_PROTECTION).size();

    drafts.rebuildPendingProjection(CHAT);

    var restoredArtifacts = new CompilationArtifacts(blobs, JSON, clock);
    var restoredSessions = new CompilationSessions(blobs, JSON, clock);
    var restoredDrafts = new RequirementDraftStore(restoredArtifacts, restoredSessions, conversations(archive));
    restoredDrafts.beginTurn(CHAT, author);
    assertTrue(restoredDrafts.pendingProposal(CHAT).isEmpty());
    assertTrue(restoredDrafts.rejectedCaptureRevision(CHAT).isEmpty());
    assertTrue(restoredDrafts.semanticBaseline(CHAT).isEmpty());
    assertTrue(restoredDrafts.get(CHAT).isEmpty());
    assertTrue(restoredDrafts.latestRevision(CHAT).isEmpty());
    assertNull(restoredDrafts.repairConstraints(CHAT));
    assertEquals(protectionCount, restoredArtifacts.history(restoredSessions.active(CHAT),
        Kind.REQUIREMENT_CAPTURE_PROTECTION).size());
    assertEquals(input.turnId(), archive.read(CHAT).activeTurns().getFirst().id());
  }

  private static ConversationService conversations(ConversationArchive archive) {
    var conversations = mock(ConversationService.class);
    when(conversations.archive()).thenReturn(archive);
    return conversations;
  }

  private static ToolProviderResult captureTools(RequirementDraftStore drafts, ConversationService conversations) {
    var schemas = DeterministicElementSchemaService.createForUnitTests(JSON);
    drafts.elementSchemas = schemas;
    var tools = new RequirementCaptureTools(drafts, new RequirementDraftTool(drafts), JSON);
    tools.elementSchemas = schemas;
    tools.conversations = conversations;
    return new RequirementCaptureToolProvider(tools, JSON, conversations).provideTools(null);
  }

  private static RequirementCaptureResult call(ToolProviderResult tools, String name, Map<String, ?> arguments)
      throws Exception {
    return JSON.readValue(tools.toolExecutorByName(name).execute(ToolExecutionRequest.builder()
        .id(name).name(name).arguments(JSON.writeValueAsString(arguments)).build(), CHAT), RequirementCaptureResult.class);
  }

  private static void assertWait(List<PipelineSignal> signals, String prompt) {
    assertEquals(1, signals.size());
    var waiting = assertInstanceOf(PipelineSignal.WaitingForInput.class, signals.getFirst());
    assertEquals(STAGE, waiting.stageId());
    assertEquals(prompt, waiting.prompt());
  }

  private static DraftInput localScript(DraftInput initial) throws Exception {
    ObjectNode result = JSON.valueToTree(initial);
    var flow = (ObjectNode) result.path("flow");
    flow.set("interactions", JSON.createArrayNode().add(JSON.valueToTree(initial.flow().interactions().getFirst())));
    flow.putArray("transitions");
    result.set("capabilities", JSON.createArrayNode().add(JSON.valueToTree(initial.capabilities().getFirst())));
    for (var fact : result.withArray("facts")) {
      if (List.of("script-name", "script-behavior").contains(fact.path("sourceFactId").asText())) {
        ((ObjectNode) fact).putArray("interactionIds").add("entry");
      }
      if ("script-name".equals(fact.path("sourceFactId").asText())) {
        ((ObjectNode) fact.path("elementIntent")).put("ownerInteractionId", "entry");
      }
    }
    return JSON.treeToValue(result, DraftInput.class);
  }
}
