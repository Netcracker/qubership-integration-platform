package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.*;
import org.qubership.integration.platform.ai.productpipeline.runtime.*;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.store.*;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class RequirementReadyRepairSettlementTest {
  @Test
  void nativeReadyRepairCheckpointSurvivesReloadAndPlanningContinuationWithoutAnotherGather() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var clock = Clock.systemUTC();
    var config = mock(AppConfig.class, RETURNS_DEEP_STUBS);
    when(config.conversation().idleTimeout()).thenReturn(Duration.ofHours(1));
    when(config.llm().maxRequestsPerRun()).thenReturn(40);
    when(config.llm().maxTokensPerRun()).thenReturn(1_000_000L);
    when(config.llm().maxActiveTimePerRun()).thenReturn(Duration.ofMinutes(30));
    var conversations = new ConversationService(config, new ConversationArchive(blobs, json));
    var nativeArtifacts = new CompilationArtifacts(blobs, json, clock);
    var sessions = new CompilationSessions(blobs, json, clock);
    var drafts = new RequirementDraftStore(nativeArtifacts, sessions, conversations);
    drafts.elementSchemas = DeterministicElementSchemaService.createForUnitTests(json);
    var tools = new RequirementCaptureTools(drafts, new RequirementDraftTool(drafts), json);
    tools.elementSchemas = drafts.elementSchemas;
    tools.conversations = conversations;
    String chat = "settled-ready-chat", run = "settled-ready-run", stage = "requirement-discovery";
    String source = "Create a private POST /settled-ready endpoint. After entry run exactly this script: "
        + "exchange.message.body = 'receipt-ready'\nProceed without IDS.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    drafts.beginTurn(chat, source);
    var endpoint = new FactInput("endpoint", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Private POST /settled-ready endpoint.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
            "entry", null, null, null, List.of(new PlanProperty("externalRoute", "false")), List.of()));
    var invalid = draft(endpoint, "entry");
    var corrected = draft(endpoint, "receipt-script");
    RequirementCaptureResult refused;
    try (var ignored = ToolSession.open(chat)) {
      refused = json.readValue(tools.captureRequirementDraft(invalid), RequirementCaptureResult.class);
    }
    assertFalse(refused.accepted());
    assertTrue(refused.issues().stream().anyMatch(issue -> "INVALID_ELEMENT_REFERENCE".equals(issue.code())));
    var originalAttempt = nativeArtifacts.latest(sessions.active(chat), Kind.REQUIREMENT_CAPTURE_ATTEMPT).orElseThrow();
    var calls = new AtomicInteger();
    var discovery = new RequirementDiscoveryCapability(null, drafts, (cid, text) -> {
      calls.incrementAndGet();
      assertTrue(text.contains("<current-native-repair-evidence>"));
      var accepted = decode(json, tools.captureRequirementDraft(corrected));
      assertTrue(accepted.path("accepted").asBoolean(), accepted.toString());
      var finished = decode(json, tools.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY));
      assertTrue(finished.path("accepted").asBoolean(), finished.toString());
      return Multi.createFrom().empty();
    }, null);
    var profile = new ProductPipelineProfile(1, "native-ready", "2", List.of(),
        List.of(new ProfileStage(stage, stage, List.of(), List.of(new ArtifactTypeRef("requirement-draft", 1)),
            null, null, new RetryPolicy(0, 1L))), new TerminalPolicy(stage, "PLAN_APPROVED"), List.of(stage));
    var manifest = new RunManifest(run, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile-hash", "baseline", "baseline-hash", List.of(), "closure",
        new KnowledgePackageRef("knowledge", "1", "1", "checksum", "CERTIFIED", "certificate"),
        "2026.1", List.of(), null);
    var start = new StartOrResumeCommand(chat, run, profile, manifest);
    var runs = new ProductPipelineRunStore(blobs, json, clock);
    var artifacts = new ProductPipelineArtifactStore(nativeArtifacts);
    var registry = new StageCapabilityRegistry(List.of(discovery));
    var budget = new LlmRunBudget(blobs, json, config);
    budget.reserve(run, 12, 8);
    var budgetBefore = budget.state(run);
    var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts, registry, clock).build().withLlmBudget(budget);
    var initial = support.bootstrap(start, "ready-flow");
    var attrs = new LinkedHashMap<String, Object>();
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "The captured local element reference was rejected.");
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, stage);
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, "CONTRACT_FAILURE");
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, refused.issues().toString());
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, "CONTRACT_SHAPE");
    attrs.put(ProductPipelineRunSupport.PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR, "original-failure");
    attrs.put(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR, stage);
    attrs.put("mappingRepairAutomatic", "true");
    String originalFailure = ProductPipelineRunSupport.haltEvidence(attrs, null);
    var ledger = new RecoveryAttemptLedger();
    var cause = org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause.of(
        org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode.CONTRACT_SHAPE);
    var key = ledger.key(stage, cause, "original-input", initial.transitions());
    runs.commit(initial.run().runRevision(), LogicalCommit.statusChange(initial.run(), StageStatus.RUNNING,
        initial.run().stages(), new StageAttempt("failed-native-capture", stage, 2, StageStatus.FAILED,
            clock.instant(), clock.instant(), List.of(), originalFailure),
        new RunTransition(1, 2, RunStatus.RUNNING, RunStatus.RUNNING, stage, clock.instant(),
            ledger.recordCleanRebuild(key, "original-input"))));
    support.restoreForExternalWorkflow(start).collect().asList().await().indefinitely();
    var spentBefore = ledger.remaining(runs.load(run).orElseThrow().transitions(), key, InputOrigin.ABSENT);

    var ready = support.stageExecutor().execute(run, stage).await().indefinitely();
    var wait = assertInstanceOf(StageDecision.WaitForInput.class, ready.decision());
    assertEquals(PipelineGates.REQUIREMENT_DRAFT_READY, PipelineGates.gateOf(wait.prompt()).orElseThrow());
    assertEquals(1, calls.get());
    var checkpoint = runs.load(run).orElseThrow();
    var readyAttempt = checkpoint.attempts().getLast();
    assertEquals(StageStatus.WAITING_FOR_INPUT, readyAttempt.outcome());
    assertFalse(StageRepairEvidence.haltRecorded(ProductPipelineRunSupport.readHaltEvidence(readyAttempt.failureEvidence())));
    assertFalse(StageRepairEvidence.haltRecorded(support.runAttributes(run)));
    assertTrue(checkpoint.attempts().stream().anyMatch(attempt -> originalFailure.equals(attempt.failureEvidence())));
    var accepted = drafts.latestRevision(chat).orElseThrow();
    var constraints = json.valueToTree(drafts.repairConstraints(chat));
    var sourceAfterReady = json.valueToTree(conversations.archive().read(chat));

    var reloadedConversations = new ConversationService(config, new ConversationArchive(blobs, json));
    var reloadedNative = new CompilationArtifacts(blobs, json, clock);
    var reloadedDrafts = new RequirementDraftStore(reloadedNative, new CompilationSessions(blobs, json, clock), reloadedConversations);
    reloadedDrafts.elementSchemas = drafts.elementSchemas;
    var resumed = new RequirementDiscoveryCapability(null, reloadedDrafts, (cid, text) -> {
      calls.incrementAndGet();
      throw new AssertionError("The validated ready continuation must not make another Gather/provider invocation.");
    }, null);
    var reloadedRuns = new ProductPipelineRunStore(blobs, json, clock);
    var restored = CreateChainTestOrchestrator.supportBuilder(reloadedRuns,
        new ProductPipelineArtifactStore(reloadedNative), new StageCapabilityRegistry(List.of(resumed)), clock)
        .build().withLlmBudget(budget);
    restored.restoreForExternalWorkflow(start).collect().asList().await().indefinitely();
    assertFalse(StageRepairEvidence.haltRecorded(restored.runAttributes(run)));
    assertTrue(String.valueOf(restored.runAttributes(run).getOrDefault(
        ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR, "")).isBlank());
    restored.recordInput(new AcceptInputCommand(run, ChatEvent.CONTINUE_TO_PLANNING_ACTION,
        "normal-planning-control", "normal-planning-hash", InputOrigin.TRUSTED))
        .collect().asList().await().indefinitely();
    var continued = restored.stageExecutor().execute(run, stage).await().indefinitely();
    assertInstanceOf(StageDecision.Complete.class, continued.decision());
    assertEquals(1, calls.get());
    assertEquals(accepted, reloadedDrafts.latestRevision(chat).orElseThrow());
    assertEquals(constraints, json.valueToTree(reloadedDrafts.repairConstraints(chat)));
    assertEquals(sourceAfterReady, json.valueToTree(reloadedConversations.archive().read(chat)));
    assertEquals(originalAttempt, reloadedNative.get(originalAttempt.compilationId(), originalAttempt.reference()).orElseThrow());
    assertTrue(reloadedRuns.load(run).orElseThrow().attempts().stream()
        .anyMatch(attempt -> originalFailure.equals(attempt.failureEvidence())));
    assertEquals(spentBefore, ledger.remaining(reloadedRuns.load(run).orElseThrow().transitions(), key, InputOrigin.ABSENT));
    assertEquals(budgetBefore, budget.state(run));
  }

  private static DraftInput draft(FactInput endpoint, String reference) {
    var script = new FactInput("script", List.of("entry"), FactKind.BEHAVIOR, Polarity.POSITIVE,
        "Run exactly this script: exchange.message.body = 'receipt-ready'", null, null,
        new RequirementElementIntent(reference, "script", RequirementElementIntent.Placement.LOCAL_NODE,
            "entry", null, null, null, List.of(new PlanProperty("script", "exchange.message.body = 'receipt-ready'")),
            List.of("receipt-ready")));
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "POST /settled-ready", null, null, null)), List.of()), List.of(endpoint, script),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST,
            "/settled-ready", null, null, null)), List.of(), new DraftSettings(false, null));
  }

  private static com.fasterxml.jackson.databind.JsonNode decode(ObjectMapper json, String value) {
    try {
      return json.readTree(value);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }
}
