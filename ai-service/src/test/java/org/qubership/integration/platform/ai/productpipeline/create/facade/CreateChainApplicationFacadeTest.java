package org.qubership.integration.platform.ai.productpipeline.create.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementAnalysisCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.SpecificationImportCapability;
import org.qubership.integration.platform.ai.productpipeline.create.UploadedSpecImportPassthrough;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.runtime.ImplementCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class CreateChainApplicationFacadeTest {

  private Fixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    fixture = Fixture.create();
  }

  @Test
  void startUsesTaskIdAsConversationIdAndCreatesOneBinding() {
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-start-1";

    List<CreateChainEvent> events =
        facade
            .start(new StartCreateChainCommand(taskId, "create greetings API"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertFalse(events.isEmpty());
    CreateChainExecutionSnapshot snapshot = facade.snapshot(taskId).orElseThrow();
    assertEquals(taskId, snapshot.taskId());
    assertEquals(taskId, fixture.runStore().loadByConversation(taskId).orElseThrow().run().conversationId());
    assertEquals(1, fixture.bindingStore().load(taskId).stream().count());
    assertTrue(
        snapshot.status() == CreateChainExecutionStatus.INPUT_REQUIRED
            || snapshot.status() == CreateChainExecutionStatus.WORKING);

    facade
        .start(new StartCreateChainCommand(taskId, "more detail"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(1, fixture.bindingCount(taskId));
    assertEquals(
        1,
        fixture.runStore().loadByConversation(taskId).stream().count());
  }

  @Test
  void startForwardsSkillProgressForTheInvokedDiscoverySkill() {
    CreateChainApplicationFacade facade = fixture.facade();

    List<CreateChainEvent> events =
        facade
            .start(new StartCreateChainCommand("task-skill-1", "create greetings API"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    List<CreateChainEvent.SkillProgress> skills =
        events.stream()
            .filter(CreateChainEvent.SkillProgress.class::isInstance)
            .map(CreateChainEvent.SkillProgress.class::cast)
            .toList();
    assertTrue(
        skills.stream()
            .anyMatch(
                skill ->
                    "brainstorming".equals(skill.skillId()) && "running".equals(skill.status())),
        () -> "expected brainstorming running, got: " + events);
    assertTrue(
        skills.stream()
            .anyMatch(
                skill ->
                    "brainstorming".equals(skill.skillId()) && "completed".equals(skill.status())),
        () -> "expected brainstorming completed, got: " + events);
  }

  @Test
  void continueClarificationReusesSameRun() {
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-clarify-1";

    // Force first discovery pass to wait for input.
    fixture = Fixture.createWithNeedingInputDiscovery();
    facade = fixture.facade();

    facade
        .start(new StartCreateChainCommand(taskId, ""))
        .collect()
        .asList()
        .await()
        .indefinitely();
    CreateChainExecutionSnapshot waiting = facade.snapshot(taskId).orElseThrow();
    assertEquals(CreateChainExecutionStatus.INPUT_REQUIRED, waiting.status());
    assertInstanceOf(CreateChainPendingAction.Clarify.class, waiting.pendingAction());
    String runId = waiting.runId();

    facade
        .continueWithInput(new ContinueCreateChainCommand(taskId, "create greetings API"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    CreateChainExecutionSnapshot after = facade.snapshot(taskId).orElseThrow();
    assertEquals(runId, after.runId());
    assertEquals(1, fixture.bindingCount(taskId));
    assertEquals(RunStatus.WAITING_FOR_APPROVAL,
        fixture.runStore().loadByConversation(taskId).orElseThrow().run().status());
  }

  @Test
  void typedFollowUpAtHaltStaysOnTheSameRunAndKeepsRetry() {
    fixture = Fixture.createWithRetryableFailureAfterInput();
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-halt-follow-up-1";
    String requirements = "create greetings API";

    facade
        .start(new StartCreateChainCommand(taskId, requirements))
        .collect()
        .asList()
        .await()
        .indefinitely();
    CreateChainExecutionSnapshot halted = facade.snapshot(taskId).orElseThrow();
    assertEquals(CreateChainExecutionStatus.INPUT_REQUIRED, halted.status());
    assertFalse(halted.finished());
    CreateChainPendingAction.Clarify haltCard =
        assertInstanceOf(CreateChainPendingAction.Clarify.class, halted.pendingAction());
    assertEquals(PipelineGates.STAGE_RETRY, haltCard.gateId());
    String runId = halted.runId();
    int attemptsAtHalt = fixture.discoveryAttempts();
    assertEquals(RunStatus.WAITING_FOR_INPUT, fixture.runStore().load(runId).orElseThrow().run().status());

    facade
        .continueWithInput(new ContinueCreateChainCommand(taskId, "use a different service"))
        .collect()
        .asList()
        .await()
        .indefinitely();

    CreateChainExecutionSnapshot afterFollowUp = facade.snapshot(taskId).orElseThrow();
    assertEquals(runId, afterFollowUp.runId());
    assertEquals(CreateChainExecutionStatus.INPUT_REQUIRED, afterFollowUp.status());
    assertFalse(afterFollowUp.finished());
    CreateChainPendingAction.Clarify stillHalted =
        assertInstanceOf(CreateChainPendingAction.Clarify.class, afterFollowUp.pendingAction());
    assertEquals(PipelineGates.STAGE_RETRY, stillHalted.gateId());
    assertEquals(
        "use a different service",
        fixture.runtime().support().haltFollowUpText(runId).orElseThrow());
    assertEquals(attemptsAtHalt, fixture.discoveryAttempts());
    assertEquals(RunStatus.WAITING_FOR_INPUT, fixture.runStore().load(runId).orElseThrow().run().status());

    facade
        .continueWithInput(new ContinueCreateChainCommand(taskId, PipelineGates.RETRY_ACTION))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(attemptsAtHalt + 1, fixture.discoveryAttempts());
    assertEquals(requirements, fixture.lastDiscoveryUserText());
    assertEquals("use a different service", fixture.lastDiscoveryFollowUp());
    CreateChainExecutionSnapshot afterRetry = facade.snapshot(taskId).orElseThrow();
    assertEquals(runId, afterRetry.runId());
    assertFalse(afterRetry.finished());
  }

  @Test
  void blankWaitSurfacesDraftOpenQuestions() {
    RequirementDraftStore drafts = new RequirementDraftStore();
    fixture = Fixture.createWithBlankNeedInputDiscovery(drafts);
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-clarify-questions-1";
    drafts.put(
        taskId,
        new RequirementDraft(
            false,
            "Create a new integration chain in QIP.",
            DraftDecision.NEEDS_INPUT,
            List.of(
                "What HTTP method and path should the chain expose?",
                "What response body should it return?"),
            "brainstorming",
            "integration-platform-skills"));

    List<CreateChainEvent> events =
        facade
            .start(new StartCreateChainCommand(taskId, ""))
            .collect()
            .asList()
            .await()
            .indefinitely();

    CreateChainEvent.Waiting waiting =
        events.stream()
            .filter(CreateChainEvent.Waiting.class::isInstance)
            .map(CreateChainEvent.Waiting.class::cast)
            .findFirst()
            .orElseThrow();
    CreateChainPendingAction.Clarify clarify =
        assertInstanceOf(CreateChainPendingAction.Clarify.class, waiting.pendingAction());
    assertEquals(
        "What HTTP method and path should the chain expose?\nWhat response body should it return?",
        clarify.reason());
    assertEquals(2, clarify.missingEvidence().size());
    assertEquals(
        "What HTTP method and path should the chain expose?", clarify.missingEvidence().get(0));

    CreateChainExecutionSnapshot snapshot = facade.snapshot(taskId).orElseThrow();
    CreateChainPendingAction.Clarify pending =
        assertInstanceOf(CreateChainPendingAction.Clarify.class, snapshot.pendingAction());
    assertEquals(clarify.reason(), pending.reason());
    assertEquals(clarify.missingEvidence(), pending.missingEvidence());
  }

  @Test
  void startSuppressesSilentWaitThenSurfacesDraftQuestions() {
    RequirementDraftStore drafts = new RequirementDraftStore();
    fixture = Fixture.createWithBlankNeedInputTimes(drafts, 2);
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-clarify-attach-1";
    drafts.put(
        taskId,
        new RequirementDraft(
            false,
            "Create a new integration chain in QIP.",
            DraftDecision.NEEDS_INPUT,
            List.of(
                "What HTTP method and path should the chain expose?",
                "What response body should it return?"),
            "brainstorming",
            "integration-platform-skills"));

    List<CreateChainEvent> events =
        facade
            .start(new StartCreateChainCommand(taskId, "Hello! Create a new chain"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    List<CreateChainEvent.Waiting> waits =
        events.stream()
            .filter(CreateChainEvent.Waiting.class::isInstance)
            .map(CreateChainEvent.Waiting.class::cast)
            .toList();
    assertEquals(1, waits.size(), () -> "expected one public wait, got " + events);
    CreateChainPendingAction.Clarify clarify =
        assertInstanceOf(CreateChainPendingAction.Clarify.class, waits.get(0).pendingAction());
    assertTrue(clarify.reason().contains("HTTP method"), clarify.reason());
    assertEquals(2, clarify.missingEvidence().size());
    assertFalse(clarify.reason().equals("Additional input is required."));
  }

  @Test
  void exactApprovalMatrix() {
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-approve-1";
    WaitingApproval waiting = reachRequirementApproval(facade, taskId);

    ApproveCreateChainOutcome stale =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId, waiting.type(), waiting.hash(), waiting.revision() - 1));
    assertInstanceOf(ApproveCreateChainOutcome.StaleRevision.class, stale);

    ApproveCreateChainOutcome wrongHash =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId, waiting.type(), "b".repeat(64), waiting.revision()));
    assertInstanceOf(ApproveCreateChainOutcome.WrongArtifactHash.class, wrongHash);

    ApproveCreateChainOutcome wrongType =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId,
                CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                waiting.hash(),
                waiting.revision()));
    assertInstanceOf(ApproveCreateChainOutcome.WrongArtifactType.class, wrongType);

    ApproveCreateChainOutcome accepted =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId, waiting.type(), waiting.hash(), waiting.revision()));
    assertInstanceOf(ApproveCreateChainOutcome.Accepted.class, accepted);

    // Replaying the prior approval is rejected: either the stage already advanced (wrong type /
    // not waiting / duplicate) or the same evidence is no longer the current candidate.
    ApproveCreateChainOutcome duplicate =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId, waiting.type(), waiting.hash(), waiting.revision()));
    assertFalse(duplicate instanceof ApproveCreateChainOutcome.Accepted, () -> "" + duplicate);
    assertTrue(
        duplicate instanceof ApproveCreateChainOutcome.DuplicateApproval
            || duplicate instanceof ApproveCreateChainOutcome.NotWaitingForApproval
            || duplicate instanceof ApproveCreateChainOutcome.WrongArtifactType
            || duplicate instanceof ApproveCreateChainOutcome.WrongArtifactHash
            || duplicate instanceof ApproveCreateChainOutcome.StaleRevision,
        () -> "unexpected duplicate outcome: " + duplicate);
  }

  @Test
  void approvalWhileNoStageWaiting() {
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-no-wait-1";
    fixture = Fixture.createWithNeedingInputDiscovery();
    facade = fixture.facade();
    facade
        .start(new StartCreateChainCommand(taskId, ""))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(
        CreateChainExecutionStatus.INPUT_REQUIRED, facade.snapshot(taskId).orElseThrow().status());

    ApproveCreateChainOutcome outcome =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId,
                CreateChainPublicArtifactTypes.REQUIREMENT_DRAFT,
                "d".repeat(64),
                1L));
    assertInstanceOf(ApproveCreateChainOutcome.NotWaitingForApproval.class, outcome);
  }

  @Test
  void planApprovalAutoImplementsOnceWithoutInputRequired() {
    Fixture live = Fixture.createWithMaterialization();
    CreateChainApplicationFacade facade = live.facade();
    String taskId = "task-impl-gate-1";

    reachPlanApproval(facade, taskId);
    CreateChainExecutionSnapshot beforePlan = facade.snapshot(taskId).orElseThrow();
    CreateChainPendingAction.Approve pending =
        assertInstanceOf(CreateChainPendingAction.Approve.class, beforePlan.pendingAction());
    assertEquals(CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN, pending.artifactType());

    ApproveCreateChainOutcome outcome =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId, pending.artifactType(), pending.artifactHash(), pending.revision()));

    assertInstanceOf(ApproveCreateChainOutcome.Accepted.class, outcome);
    ApproveCreateChainOutcome.Accepted accepted = (ApproveCreateChainOutcome.Accepted) outcome;
    assertFalse(
        accepted.events().stream().anyMatch(e -> e instanceof CreateChainEvent.Waiting),
        "normal implementation gate must not expose INPUT_REQUIRED wait");
    assertTrue(
        accepted.events().stream().anyMatch(e -> e instanceof CreateChainEvent.Progress),
        "expected WORKING progress through implementation");
    assertEquals(CreateChainExecutionStatus.COMPLETED, accepted.snapshot().status());
    assertEquals(1, facade.lastImplementSubmissions().get());
    assertEquals(
        RunStatus.CHAIN_MATERIALIZED,
        live.runStore().loadByConversation(taskId).orElseThrow().run().status());
  }

  @Test
  void missingApprovedPlanHashReturnsTypedBlockedOrNonRecoverable() {
    Fixture base = Fixture.createWithMaterialization();
    CreateChainTestOrchestrator realRuntime = base.runtime();
    CreateChainOrchestrator mocked = mock(CreateChainOrchestrator.class);
    when(mocked.approvedPlanContentHash(any())).thenReturn(Optional.empty());
    when(mocked.approve(any())).thenAnswer(inv -> realRuntime.approve(inv.getArgument(0)));
    when(mocked.startOrResume(any())).thenAnswer(inv -> realRuntime.startOrResume(inv.getArgument(0)));
    when(mocked.acceptInput(any())).thenAnswer(inv -> realRuntime.acceptInput(inv.getArgument(0)));
    when(mocked.implement(any())).thenAnswer(inv -> realRuntime.implement(inv.getArgument(0)));

    CreateChainApplicationFacade facade =
        new CreateChainApplicationFacade(
            base.selectionService(),
            base.bindingStore(),
            mocked,
            base.runStore(),
            base.catalog());
    String taskId = "task-missing-hash-1";
    reachPlanApproval(facade, taskId);
    CreateChainPendingAction.Approve pending =
        assertInstanceOf(
            CreateChainPendingAction.Approve.class,
            facade.snapshot(taskId).orElseThrow().pendingAction());

    ApproveCreateChainOutcome outcome =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId, pending.artifactType(), pending.artifactHash(), pending.revision()));

    assertTrue(
        outcome instanceof ApproveCreateChainOutcome.ImplementationBlocked
            || outcome instanceof ApproveCreateChainOutcome.NonRecoverableFailure,
        () -> "expected blocked/non-recoverable, got " + outcome);
    if (outcome instanceof ApproveCreateChainOutcome.ImplementationBlocked blocked) {
      assertTrue(
          blocked.recovery() instanceof ImplementationBlockedRecovery.ApprovePlanEvidence
              || blocked.recovery() instanceof ImplementationBlockedRecovery.ClarifyMissingEvidence);
    }
    verify(mocked, times(0)).implement(any());
  }

  @Test
  void missingPlanEvidenceAtImplementGateIsNonRecoverable() {
    Fixture base = Fixture.createWithMaterialization();
    String taskId = "task-nonrecoverable-1";
    long revision = 4L;
    base.selectionService().selectOrCreate(taskId);
    base.runStore()
        .create(
            new org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot(
                base.bindingStore().load(taskId).orElseThrow().productRunId(),
                taskId,
                revision,
                RunStatus.WAITING_FOR_IMPLEMENT,
                "planning",
                List.of(
                    new StageSnapshot(
                        "planning", StageStatus.SUCCEEDED, List.of(), null)),
                null));

    CreateChainOrchestrator mocked = mock(CreateChainOrchestrator.class);
    when(mocked.approvedPlanContentHash(any())).thenReturn(Optional.empty());
    CreateChainApplicationFacade facade =
        new CreateChainApplicationFacade(
            base.selectionService(),
            base.bindingStore(),
            mocked,
            base.runStore(),
            base.catalog());

    ApproveCreateChainOutcome outcome =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId,
                CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                "f".repeat(64),
                revision));
    assertInstanceOf(ApproveCreateChainOutcome.NonRecoverableFailure.class, outcome);
    verify(mocked, times(0)).implement(any());
    verify(mocked, times(0)).acceptInput(any());
  }

  @Test
  void validateApproveOnBlockedImplementDoesNotInvokeRuntime() {
    Fixture base = Fixture.createWithMaterialization();
    String taskId = "task-validate-blocked-1";
    String planHash = "e".repeat(64);
    long revision = 4L;
    base.selectionService().selectOrCreate(taskId);
    Reference planRef = new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", planHash);
    base.runStore()
        .create(
            new org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot(
                base.bindingStore().load(taskId).orElseThrow().productRunId(),
                taskId,
                revision,
                RunStatus.WAITING_FOR_IMPLEMENT,
                "planning",
                List.of(
                    new StageSnapshot(
                        "planning",
                        StageStatus.SUCCEEDED,
                        List.of(planRef),
                        planRef.artifactId(),
                        List.of(planRef),
                        planRef,
                        1)),
                null));

    CreateChainOrchestrator mocked = mock(CreateChainOrchestrator.class);
    when(mocked.approvedPlanContentHash(any())).thenReturn(Optional.empty());
    when(mocked.implement(any()))
        .thenReturn(
            Multi.createFrom().item(new PipelineSignal.Completed(RunStatus.CHAIN_MATERIALIZED)));
    CreateChainApplicationFacade facade =
        new CreateChainApplicationFacade(
            base.selectionService(),
            base.bindingStore(),
            mocked,
            base.runStore(),
            base.catalog());

    Optional<ApproveCreateChainOutcome> validated =
        facade.validateApprove(
            new ApproveCreateChainArtifactCommand(
                taskId,
                CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                planHash,
                revision));
    assertTrue(validated.isEmpty());
    verify(mocked, times(0)).implement(any());
    verify(mocked, times(0)).approve(any());

    CreateChainExecutionSnapshot snap = facade.snapshot(taskId).orElseThrow();
    assertEquals(CreateChainExecutionStatus.INPUT_REQUIRED, snap.status());
    assertInstanceOf(CreateChainPendingAction.Approve.class, snap.pendingAction());

    facade
        .streamApprove(
            new ApproveCreateChainArtifactCommand(
                taskId,
                CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                planHash,
                revision))
        .collect()
        .asList()
        .await()
        .indefinitely();
    verify(mocked, times(1)).implement(any(ImplementCommand.class));
    verify(mocked, times(0)).approve(any());
  }

  @Test
  void blockedRecoveryApproveConstructsImplementWithoutSecondRuntimeApproval() {
    Fixture base = Fixture.createWithMaterialization();
    String taskId = "task-recovery-1";
    String planHash = "e".repeat(64);
    long revision = 4L;

    // Durable binding + WAITING_FOR_IMPLEMENT run with known plan evidence on the stage.
    base.selectionService().selectOrCreate(taskId);
    Reference planRef = new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", planHash);
    base.runStore()
        .create(
            new org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot(
                base.bindingStore().load(taskId).orElseThrow().productRunId(),
                taskId,
                revision,
                RunStatus.WAITING_FOR_IMPLEMENT,
                "planning",
                List.of(
                    new StageSnapshot(
                        "planning",
                        StageStatus.SUCCEEDED,
                        List.of(planRef),
                        planRef.artifactId(),
                        List.of(planRef),
                        planRef,
                        1)),
                null));

    CreateChainOrchestrator mocked = mock(CreateChainOrchestrator.class);
    when(mocked.approvedPlanContentHash(any())).thenReturn(Optional.empty());
    when(mocked.implement(any()))
        .thenReturn(
            Multi.createFrom().item(new PipelineSignal.Completed(RunStatus.CHAIN_MATERIALIZED)));
    CreateChainApplicationFacade facade =
        new CreateChainApplicationFacade(
            base.selectionService(),
            base.bindingStore(),
            mocked,
            base.runStore(),
            base.catalog());

    ApproveCreateChainOutcome recovered =
        facade.approve(
            new ApproveCreateChainArtifactCommand(
                taskId,
                CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
                planHash,
                revision));
    assertInstanceOf(ApproveCreateChainOutcome.Accepted.class, recovered);
    verify(mocked, times(1)).implement(any(ImplementCommand.class));
    verify(mocked, times(0)).approve(any());
  }

  @Test
  void eventProjectionDoesNotLeakInternalNamesOrStorage() {
    CreateChainApplicationFacade facade = fixture.facade();
    String taskId = "task-events-1";
    List<CreateChainEvent> events =
        facade
            .start(new StartCreateChainCommand(taskId, "create greetings API"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    String joined = events.toString();
    assertFalse(joined.contains("WAITING_FOR_APPROVAL"));
    assertFalse(joined.contains("WAITING_FOR_INPUT"));
    assertFalse(joined.contains("CHAIN_MATERIALIZED"));
    assertFalse(joined.contains("product-pipeline-"));
    assertFalse(joined.contains("compiler-artifacts/"));
    assertFalse(joined.contains("s3://"));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof CreateChainEvent.Waiting
                        || e instanceof CreateChainEvent.Progress
                        || e instanceof CreateChainEvent.ArtifactReady));
  }

  private WaitingApproval reachRequirementApproval(
      CreateChainApplicationFacade facade, String taskId) {
    facade
        .start(new StartCreateChainCommand(taskId, "create greetings API"))
        .collect()
        .asList()
        .await()
        .indefinitely();
    CreateChainExecutionSnapshot snapshot = facade.snapshot(taskId).orElseThrow();
    CreateChainPendingAction.Approve pending =
        assertInstanceOf(CreateChainPendingAction.Approve.class, snapshot.pendingAction());
    return new WaitingApproval(pending.artifactType(), pending.artifactHash(), pending.revision());
  }

  private void reachPlanApproval(CreateChainApplicationFacade facade, String taskId) {
    for (int i = 0; i < 8; i++) {
      CreateChainExecutionSnapshot snapshot = facade.snapshot(taskId).orElse(null);
      if (snapshot == null) {
        facade
            .start(new StartCreateChainCommand(taskId, "create greetings API"))
            .collect()
            .asList()
            .await()
            .indefinitely();
        continue;
      }
      if (snapshot.status() == CreateChainExecutionStatus.INPUT_REQUIRED
          && snapshot.pendingAction() instanceof CreateChainPendingAction.Clarify) {
        facade
            .continueWithInput(
                new ContinueCreateChainCommand(taskId, "create greetings API"))
            .collect()
            .asList()
            .await()
            .indefinitely();
        continue;
      }
      if (snapshot.pendingAction() instanceof CreateChainPendingAction.Approve approve
          && CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN.equals(approve.artifactType())) {
        return;
      }
      if (snapshot.pendingAction() instanceof CreateChainPendingAction.Approve approve) {
        ApproveCreateChainOutcome outcome =
            facade.approve(
                new ApproveCreateChainArtifactCommand(
                    taskId,
                    approve.artifactType(),
                    approve.artifactHash(),
                    approve.revision()));
        assertInstanceOf(ApproveCreateChainOutcome.Accepted.class, outcome);
        continue;
      }
      facade
          .continueWithInput(new ContinueCreateChainCommand(taskId, "continue"))
          .collect()
          .asList()
          .await()
          .indefinitely();
    }
    throw new AssertionError(
        "did not reach plan approval: " + facade.snapshot(taskId).orElse(null));
  }

  private record WaitingApproval(String type, String hash, long revision) {}

  private static final class Fixture {
    private final InMemoryArtifactBlobStore blobs;
    private final ObjectMapper mapper;
    private final FakeKnowledgeClient knowledge;
    private final ProductPipelineProfileCatalog catalog;
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    private final boolean needInputFirst;
    private final boolean materialize;
    private final boolean blankNeedInputReason;
    private final int needInputTimes;
    private final boolean retryableFailureAfterInput;
    private final RequirementDraftStore draftStore;
    private final AtomicInteger discoveryAttempts = new AtomicInteger();
    private final AtomicReference<String> lastDiscoveryUserText = new AtomicReference<>();
    private final AtomicReference<String> lastDiscoveryFollowUp = new AtomicReference<>();
    private CreateRunSelectionService selectionService;
    private CreateRunBindingStore bindingStore;
    private ProductPipelineRunStore runStore;
    private CreateChainTestOrchestrator runtime;
    private CreateChainApplicationFacade facade;

    private Fixture(
        InMemoryArtifactBlobStore blobs,
        ObjectMapper mapper,
        FakeKnowledgeClient knowledge,
        ProductPipelineProfileCatalog catalog,
        boolean needInputFirst,
        boolean materialize) {
      this(blobs, mapper, knowledge, catalog, needInputFirst, materialize, false, 1, false, null);
    }

    private Fixture(
        InMemoryArtifactBlobStore blobs,
        ObjectMapper mapper,
        FakeKnowledgeClient knowledge,
        ProductPipelineProfileCatalog catalog,
        boolean needInputFirst,
        boolean materialize,
        boolean blankNeedInputReason,
        int needInputTimes,
        RequirementDraftStore draftStore) {
      this(
          blobs,
          mapper,
          knowledge,
          catalog,
          needInputFirst,
          materialize,
          blankNeedInputReason,
          needInputTimes,
          false,
          draftStore);
    }

    private Fixture(
        InMemoryArtifactBlobStore blobs,
        ObjectMapper mapper,
        FakeKnowledgeClient knowledge,
        ProductPipelineProfileCatalog catalog,
        boolean needInputFirst,
        boolean materialize,
        boolean blankNeedInputReason,
        int needInputTimes,
        boolean retryableFailureAfterInput,
        RequirementDraftStore draftStore) {
      this.blobs = blobs;
      this.mapper = mapper;
      this.knowledge = knowledge;
      this.catalog = catalog;
      this.needInputFirst = needInputFirst;
      this.materialize = materialize;
      this.blankNeedInputReason = blankNeedInputReason;
      this.needInputTimes = Math.max(1, needInputTimes);
      this.retryableFailureAfterInput = retryableFailureAfterInput;
      this.draftStore = draftStore;
    }

    static Fixture create() throws Exception {
      return create(false, false);
    }

    static Fixture createWithNeedingInputDiscovery() {
      try {
        return create(true, false);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    static Fixture createWithRetryableFailureAfterInput() {
      try {
        Fixture base = create(false, false);
        return new Fixture(
            base.blobs,
            base.mapper,
            base.knowledge,
            base.catalog,
            false,
            false,
            false,
            1,
            true,
            null);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    static Fixture createWithBlankNeedInputDiscovery(RequirementDraftStore drafts) {
      return createWithBlankNeedInputTimes(drafts, 1);
    }

    static Fixture createWithBlankNeedInputTimes(RequirementDraftStore drafts, int times) {
      try {
        Fixture base = create(true, false);
        return new Fixture(
            base.blobs,
            base.mapper,
            base.knowledge,
            base.catalog,
            true,
            false,
            true,
            times,
            drafts);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    static Fixture createWithMaterialization() {
      try {
        return create(false, true);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    private static Fixture create(boolean needInputFirst, boolean materialize) throws Exception {
      ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
      ProductPipelineProfile profileV1;
      ProductPipelineProfile profileV2;
      try (InputStream in =
          Fixture.class.getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
        profileV1 = ProductPipelineProfileParser.parse(in);
      }
      try (InputStream in =
          Fixture.class.getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
        profileV2 = ProductPipelineProfileParser.parse(in);
      }
      return new Fixture(
          new InMemoryArtifactBlobStore(),
          mapper,
          FakeKnowledgeClient.defaultFixture(),
          new ProductPipelineProfileCatalog(List.of(profileV1, profileV2)),
          needInputFirst,
          materialize);
    }

    CreateChainApplicationFacade facade() {
      ensureBuilt();
      return facade;
    }

    ProductPipelineRunStore runStore() {
      ensureBuilt();
      return runStore;
    }

    CreateRunBindingStore bindingStore() {
      ensureBuilt();
      return bindingStore;
    }

    CreateRunSelectionService selectionService() {
      ensureBuilt();
      return selectionService;
    }

    ProductPipelineProfileCatalog catalog() {
      return catalog;
    }

    CreateChainTestOrchestrator runtime() {
      ensureBuilt();
      return runtime;
    }

    long bindingCount(String taskId) {
      return bindingStore().load(taskId).isPresent() ? 1 : 0;
    }

    int discoveryAttempts() {
      return discoveryAttempts.get();
    }

    String lastDiscoveryUserText() {
      return lastDiscoveryUserText.get();
    }

    String lastDiscoveryFollowUp() {
      return lastDiscoveryFollowUp.get();
    }

    private void ensureBuilt() {
      if (facade != null) {
        return;
      }
      bindingStore = new CreateRunBindingStore(blobs, mapper);
      selectionService =
          new CreateRunSelectionService(
              "2026.1", knowledge, bindingStore, catalog, stubPinResolver(), clock, "1");
      CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, clock);
      ProductPipelineArtifactStore storeFacade = new ProductPipelineArtifactStore(artifacts);
      runStore = new ProductPipelineRunStore(blobs, mapper, clock);
      StageCapabilityRegistry capabilities =
          new StageCapabilityRegistry(
              List.of(
                  discovery(),
                  importStage(),
                  UploadedSpecImportPassthrough.capability(),
                  analysis(),
                  planning(),
                  materialization()));
      runtime =
          new CreateChainTestOrchestrator(new ProductPipelineRunSupport(
              runStore, storeFacade, capabilities, catalog, stubPinResolver(), clock), runStore);
      facade =
          draftStore == null
              ? new CreateChainApplicationFacade(
                  selectionService, bindingStore, runtime, runStore, catalog)
              : new CreateChainApplicationFacade(
                  selectionService, bindingStore, runtime, runStore, catalog, draftStore);
    }

    private StageCapability discovery() {
      AtomicInteger calls = new AtomicInteger();
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return RequirementDiscoveryCapability.CAPABILITY_ID;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          String needInputMessage = blankNeedInputReason ? "" : "need input";
          if (needInputFirst && calls.incrementAndGet() <= needInputTimes) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, needInputMessage)));
          }
          if (!needInputFirst
              && calls.incrementAndGet() == 1
              && context.attributeAsString("userText") == null) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, needInputMessage)));
          }
          if (retryableFailureAfterInput && context.attributeAsString("userText") != null) {
            discoveryAttempts.incrementAndGet();
            lastDiscoveryUserText.set(context.attributeAsString("userText"));
            lastDiscoveryFollowUp.set(
                context.attributeAsString(ProductPipelineRunSupport.HALT_FOLLOW_UP_TEXT_ATTR));
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(
                            StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                            "catalog discovery transport failed")));
          }
          RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
          return Multi.createFrom()
              .items(
                  SkillActivitySupport.running("brainstorming"),
                  SkillActivitySupport.completed("brainstorming"),
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                          "draft ready",
                          null)));
        }
      };
    }

    private static StageCapability importStage() {
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return SpecificationImportCapability.CAPABILITY_ID;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.SUCCEEDED,
                          List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                          "import skipped",
                          null)));
        }
      };
    }

    private static StageCapability analysis() {
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return RequirementAnalysisCapability.CAPABILITY_ID;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          RequirementBrief brief =
              new RequirementBrief("brief", List.of("fact"), List.of(), List.of(), List.of(), "ok");
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.SUCCEEDED,
                          List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())),
                          "analyzed",
                          null)));
        }
      };
    }

    private StageCapability planning() {
      org.qubership.integration.platform.ai.plan.model.ChainPlanGraph graph =
          new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph(
              "1.0",
              new org.qubership.integration.platform.ai.plan.model.ChainSection("g", "G"),
              List.of(
                  new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
                      "http-trigger", "http-trigger-2", "HTTP", null, null, List.of())),
              List.of());
      String graphDigest =
          new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(mapper)
              .sha256(graph);
      org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult assembly =
          new org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult(
              1, graph, graphDigest, List.of(), List.of(), List.of());
      org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle bundle =
          new org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle(
              1,
              graphDigest,
              List.of(
                  new org.qubership.integration.platform.ai.productpipeline.artifact
                      .CompilerValidationPass(
                      "validator",
                      new org.qubership.integration.platform.ai.qipknowledge.validation
                          .ValidationResult(true, List.of(), "ok"))));
      ImplementationPlan plan =
          ImplementationPlan.schemaVersion2(
              "Plan",
              "planning",
              "1",
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of());
      org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult validation =
          new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult(
              List.of());
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return PlanningCapability.CAPABILITY_ID;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.CANDIDATE,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.IMPLEMENTATION_PLAN, plan, context.inputRefs()),
                              new ArtifactCandidate(
                                  Kind.PLAN_VALIDATION_RESULT, validation, context.inputRefs()),
                              new ArtifactCandidate(
                                  Kind.CHAIN_PLAN_GRAPH, graph, context.inputRefs()),
                              new ArtifactCandidate(
                                  Kind.GRAPH_ASSEMBLY_RESULT, assembly, context.inputRefs()),
                              new ArtifactCandidate(
                                  Kind.COMPILER_VALIDATION_BUNDLE, bundle, context.inputRefs())),
                          "plan ready",
                          null)));
        }
      };
    }

    private StageCapability materialization() {
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return org.qubership.integration.platform.ai.productpipeline.materialization
              .MaterializationCapability.CAPABILITY_ID;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          if (!materialize) {
            return Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(
                            StageOutcomeClass.NEEDS_INPUT, "materialization stub")));
          }
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          StageOutcomeClass.SUCCEEDED,
                          List.of(
                              new ArtifactCandidate(
                                  Kind.MATERIALIZATION_RESULT, java.util.Map.of("ok", true), List.of()),
                              new ArtifactCandidate(
                                  Kind.CATALOG_CHAIN_SNAPSHOT,
                                  new org.qubership.integration.platform.ai.chain.presentation
                                      .ChainCatalogFacts(
                                      "catalog-chain-1",
                                      "DemoChain",
                                      "",
                                      2,
                                      0,
                                      "",
                                      List.of(),
                                      List.of(),
                                      "built_in_catalog"),
                                  List.of()),
                              new ArtifactCandidate(
                                  Kind.RECONCILE_RESULT,
                                  java.util.Map.of("reconcile", "ok"),
                                  List.of())),
                          "materialized",
                          null)));
        }
      };
    }

    private static CompilerRunPinResolver stubPinResolver() {
      CompilerRunPin pin =
          new CompilerRunPin(
              "pkg",
              "1",
              "digest",
              1,
              "idx-1",
              "idx-digest",
              new ResolvedCompilerDag(List.of(), List.of(), "dag"),
              List.of("planning"),
              java.util.Map.of(),
              java.util.Map.of("skill", "a".repeat(64)),
              List.of(),
              null,
              null,
              null,
              null,
              null,
              null);
      CompilerRunPinResolver resolver = mock(CompilerRunPinResolver.class);
      when(resolver.resolve(any(), any())).thenReturn(pin);
      return resolver;
    }
  }
}
