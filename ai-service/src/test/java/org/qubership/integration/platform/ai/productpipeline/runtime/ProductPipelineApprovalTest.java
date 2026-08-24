package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecord;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.storage.S3Service;

class ProductPipelineApprovalTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T12:00:00Z");
  private static final String RUN_ID = "run-approval-1";
  private static final String CAPABILITY_ID = "scripted-candidate-capability";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
  }

  @Test
  void selectsApprovalTargetByDeclaredArtifactType() {
    StageOutcome candidate =
        candidateOutcome(
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "v1")),
            artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "ok")),
            artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "v1")));

    WaitingForApprovalResult waiting = runToCandidate(candidate, multiItemPolicy(), multiItemProduces());

    assertEquals(Kind.IMPLEMENTATION_PLAN, waiting.waiting().candidate().kind());
  }

  @Test
  void surfacesIdsDocumentMarkdownBeforeApprovalWait() {
    String idsMarkdown =
        """
        # Integration Design Specification (IDS)
        ## Integration flow for CIP Chain - Sample
        ```mermaid
        sequenceDiagram
          autonumber
          participant Client
          participant CIP
          Client->>CIP: ping
        ```
        """;
    IdsDocument ids =
        new IdsDocument(
            "1",
            IdsDocument.Mode.GENERATED,
            "brief-1",
            "source-hash",
            "flow-hash",
            "design-generator@1",
            idsMarkdown);
    WaitingForApprovalResult waiting =
        runToCandidate(
            candidateOutcome(artifact(Kind.IDS_DOCUMENT, ids)),
            new ApprovalPolicy(new ArtifactTypeRef("ids-document", 1), List.of()),
            List.of(new ArtifactTypeRef("ids-document", 1)));

    assertEquals(Kind.IDS_DOCUMENT, waiting.waiting().candidate().kind());
    String reviewText =
        waiting.signals().stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .map(PipelineSignal.Message::text)
            .filter(text -> text.contains("Integration Design Specification"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected IDS markdown Message before approval"));
    assertTrue(reviewText.contains("sequenceDiagram"));
    assertTrue(
        reviewText.endsWith("\n\n"),
        "expected trailing blank lines so Agree CTA token does not glue to IDS body");
  }

  @Test
  void omitsHashMetadataFromImplementationPlanChatReview() {
    String planText =
        """
        # Implementation plan: HealthProxy

        Schema version: 2
        Design input hash: e7108e1c6fdda11b7d2af9181ebecdae006f1ff4d83fce8245097b3c0af1b597
        Source report hash: 2339890f5e7b5b39c31e276cb1827f0e865885dfc2992bbe91e78ebc9c63ef0a
        Compiler catalog hash: 98ce0714699603131bd3fd256ea089e67013c37961461431ba7c082abc13e90a

        ## Planner steps
        1. Call health
        """;
    ImplementationPlan plan = new ImplementationPlan(planText);
    WaitingForApprovalResult waiting =
        runToCandidate(
            candidateOutcome(artifact(Kind.IMPLEMENTATION_PLAN, plan)),
            new ApprovalPolicy(new ArtifactTypeRef("implementation-plan", 1), List.of()),
            List.of(new ArtifactTypeRef("implementation-plan", 1)));

    String reviewText =
        waiting.signals().stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .map(PipelineSignal.Message::text)
            .filter(text -> text.contains("Implementation plan: HealthProxy"))
            .findFirst()
            .orElseThrow();
    assertTrue(reviewText.contains("## Planner steps"));
    assertFalse(reviewText.contains("Design input hash:"));
    assertFalse(reviewText.contains("Source report hash:"));
    assertFalse(reviewText.contains("Compiler catalog hash:"));
    assertFalse(reviewText.contains("e7108e1c6fdda11b"));
  }

  @Test
  void surfacesIdsDownloadLinkWhenStorageAvailable() {
    String idsMarkdown = "# IDS\n\nBody for download.\n";
    IdsDocument ids =
        new IdsDocument(
            "1",
            IdsDocument.Mode.GENERATED,
            "brief-1",
            "source-hash",
            "flow-hash",
            "design-generator@1",
            idsMarkdown);
    S3Service s3 = mock(S3Service.class);
    when(s3.putDesignIdsMarkdown(anyString())).thenReturn("ids-designs/abc/ids.md");
    configureRuntime(
        profile(
            new ApprovalPolicy(new ArtifactTypeRef("ids-document", 1), List.of()),
            List.of(new ArtifactTypeRef("ids-document", 1))),
        new ScriptedCapability(candidateOutcome(artifact(Kind.IDS_DOCUMENT, ids))),
        s3);
    startRun();
    WaitingForApprovalResult waiting = acceptInput("candidate");

    String reviewText =
        waiting.signals().stream()
            .filter(PipelineSignal.Message.class::isInstance)
            .map(PipelineSignal.Message.class::cast)
            .map(PipelineSignal.Message::text)
            .filter(text -> text.contains("# IDS"))
            .findFirst()
            .orElseThrow();
    assertTrue(reviewText.contains("[ids.md](/api/v1/storage/objects?key="));
    assertTrue(reviewText.contains("ids-designs%2Fabc%2Fids.md"));
    // Body, download link, and trailing blank lines (Agree CTA is a separate streamed token).
    assertTrue(
        reviewText.contains("# IDS\n\nBody for download.\n\n[ids.md](/api/v1/storage/objects?key="),
        "expected blank line between IDS body and download link");
    assertTrue(
        reviewText.endsWith(")\n\n"),
        "expected trailing blank lines after download link so CTA does not glue to ids.md");
    String concatenated = reviewText + waiting.waiting().prompt();
    assertFalse(concatenated.contains("ids.mdDo"), "Message + WaitingForApproval must not glue");
    assertTrue(
        concatenated.matches("(?s).*\\[ids\\.md\\]\\([^)]*\\)\\n\\n\\S.*"),
        "download link and Agree CTA must be separated by blank lines when tokens concatenate");
    verify(s3).putDesignIdsMarkdown(idsMarkdown.trim());
  }

  @Test
  void approvedStageCommitsWholeCurrentCandidateSet() {
    WaitingForApprovalResult waiting =
        runToCandidate(validCreateChainCandidate(), multiItemPolicy(), multiItemProduces());
    runtime.approve(new ApproveCommand(RUN_ID, waiting.waiting().candidate(), runStore.load(RUN_ID).orElseThrow().run().runRevision())).collect().asList().await().indefinitely();

    StageSnapshot stage = currentStage();
    assertEquals(
        Set.of(Kind.IMPLEMENTATION_PLAN, Kind.PLAN_VALIDATION_RESULT, Kind.CHAIN_PLAN_GRAPH),
        stage.outputRefs().stream()
            .filter(ref -> ref.kind() != Kind.APPROVAL_RECORD)
            .map(Reference::kind)
            .collect(Collectors.toSet()));
    ApprovalRecordV2 approvalRecord = latestApprovalV2();
    assertEquals(
        stage.outputRefs().stream()
            .filter(ref -> ref.kind() != Kind.APPROVAL_RECORD)
            .toList(),
        approvalRecord.approvedCandidates());
    assertNull(approvalRecord.bindingResolutionPolicy());
    assertNull(approvalRecord.bindingResolutionPolicyHash());
  }

  @Test
  void copiesBindingResolutionPolicyFromApprovalPolicyOnly() {
    WaitingForApprovalResult waiting =
        runToCandidate(
            validCreateChainCandidate(),
            new ApprovalPolicy(
                new ArtifactTypeRef("implementation-plan", 2),
                multiItemCandidateSet(),
                ApprovalPolicy.CATALOG_FIRST_V1,
                ApprovalPolicy.CATALOG_FIRST_V1_HASH),
            multiItemProduces());
    runtime
        .approve(
            new ApproveCommand(
                RUN_ID,
                waiting.waiting().candidate(),
                runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    ApprovalRecordV2 approvalRecord = latestApprovalV2();
    assertEquals(ApprovalPolicy.CATALOG_FIRST_V1, approvalRecord.bindingResolutionPolicy());
    assertEquals(ApprovalPolicy.CATALOG_FIRST_V1_HASH, approvalRecord.bindingResolutionPolicyHash());
    assertEquals(Kind.IMPLEMENTATION_PLAN, approvalRecord.target().kind());
  }

  @Test
  void legacySingleItemPolicyKeepsDeclaredValidationEvidenceOutsideApproval() {
    WaitingForApprovalResult waiting =
        runToCandidate(
            candidateOutcome(
                artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "legacy")),
                artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "legacy"))),
            new ApprovalPolicy(new ArtifactTypeRef("implementation-plan", 2)),
            List.of(
                new ArtifactTypeRef("implementation-plan", 2),
                new ArtifactTypeRef("plan-validation-result", 1)));

    runtime.approve(new ApproveCommand(RUN_ID, waiting.waiting().candidate(), runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(Kind.IMPLEMENTATION_PLAN, latestApprovalV1().target().kind());
    assertEquals(
        Set.of(Kind.IMPLEMENTATION_PLAN, Kind.PLAN_VALIDATION_RESULT),
        currentStage().outputRefs().stream()
            .filter(ref -> ref.kind() != Kind.APPROVAL_RECORD)
            .map(Reference::kind)
            .collect(Collectors.toSet()));
    assertNotEquals("2", latestApprovalRevision().schemaVersion());
  }

  @Test
  void multiItemPolicyApprovesRequiredSubsetAndRetainsDeclaredExtraEvidence() {
    WaitingForApprovalResult waiting =
        runToCandidate(
            candidateOutcome(
                artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "v2")),
                artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "v2")),
                artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "evidence"))),
            new ApprovalPolicy(
                new ArtifactTypeRef("implementation-plan", 2),
                List.of(
                    new ArtifactTypeRef("implementation-plan", 2),
                    new ArtifactTypeRef("chain-plan-graph", 1))),
            multiItemProduces());

    runtime.approve(new ApproveCommand(RUN_ID, waiting.waiting().candidate(), runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    ApprovalRecordV2 approvalRecord = latestApprovalV2();
    assertEquals(
        Set.of(Kind.IMPLEMENTATION_PLAN, Kind.CHAIN_PLAN_GRAPH),
        approvalRecord.approvedCandidates().stream().map(Reference::kind).collect(Collectors.toSet()));
    assertTrue(
        currentStage().outputRefs().stream()
            .anyMatch(ref -> ref.kind() == Kind.PLAN_VALIDATION_RESULT));
  }

  @Test
  void secondCandidateReplacesOutputRefsAndPreservesCandidateHistory() {
    StageOutcome first =
        candidateOutcome(
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "first")),
            artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "first")),
            artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "first")));
    StageOutcome second =
        candidateOutcome(
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "second")),
            artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "second")),
            artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "second")));
    configureRuntime(profile(multiItemPolicy(), multiItemProduces()), new ScriptedCapability(first, second));

    startRun();
    WaitingForApprovalResult firstWait = acceptInput("first");
    List<Reference> firstOutputRefs = currentStage().outputRefs();

    WaitingForApprovalResult secondWait = acceptInput("second");
    StageSnapshot stage = currentStage();

    assertNotEquals(firstWait.waiting().candidate(), secondWait.waiting().candidate());
    assertNotEquals(firstOutputRefs, stage.outputRefs());
    assertEquals(3, stage.outputRefs().size());
    assertEquals(6, stage.candidateReferences().size());
  }

  @Test
  void unknownOutputKindFailsClosedWithContractFailure() {
    ProductPipelineRunDocument doc =
        runAndExpectFailure(
            candidateOutcome(artifact(Kind.REQUIREMENT_BRIEF, Map.of("unexpected", true))),
            multiItemPolicy(),
            multiItemProduces());
    assertEquals(StageStatus.WAITING_FOR_INPUT, currentStage(doc).status());
  }

  @Test
  void undeclaredSchemaVersionFailsClosedWithContractFailure() {
    List<ArtifactTypeRef> producesWithUndeclaredSchema =
        List.of(
            new ArtifactTypeRef("implementation-plan", 0),
            new ArtifactTypeRef("plan-validation-result", 1),
            new ArtifactTypeRef("chain-plan-graph", 1));
    ProductPipelineRunDocument doc =
        runAndExpectFailure(validCreateChainCandidate(), multiItemPolicy(), producesWithUndeclaredSchema);
    assertEquals(StageStatus.WAITING_FOR_INPUT, currentStage(doc).status());
  }

  @Test
  void missingRequiredKindFailsClosedWithContractFailure() {
    StageOutcome missingGraph =
        candidateOutcome(
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "v1")),
            artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "ok")));
    ProductPipelineRunDocument doc =
        runAndExpectFailure(missingGraph, multiItemPolicy(), multiItemProduces());
    assertEquals(StageStatus.WAITING_FOR_INPUT, currentStage(doc).status());
  }

  @Test
  void duplicateRequiredKindFailsClosedWithContractFailure() {
    StageOutcome duplicateImplementationPlans =
        candidateOutcome(
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "a")),
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "b")),
            artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "ok")),
            artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "ok")));
    ProductPipelineRunDocument doc =
        runAndExpectFailure(duplicateImplementationPlans, multiItemPolicy(), multiItemProduces());
    assertEquals(StageStatus.WAITING_FOR_INPUT, currentStage(doc).status());
  }

  @Test
  void persistsDeclaredSchemaVersionsForCandidateAndApprovalV2() {
    WaitingForApprovalResult waiting =
        runToCandidate(validCreateChainCandidate(), multiItemPolicy(), multiItemProduces());
    runtime.approve(new ApproveCommand(RUN_ID, waiting.waiting().candidate(), runStore.load(RUN_ID).orElseThrow().run().runRevision())).collect().asList().await().indefinitely();

    Revision implementationPlanRevision =
        artifactStore.history(RUN_ID, Kind.IMPLEMENTATION_PLAN).stream().findFirst().orElseThrow();
    Revision approvalRevision = latestApprovalRevision();

    assertEquals("2", implementationPlanRevision.schemaVersion());
    assertEquals("2", approvalRevision.schemaVersion());
  }

  @Test
  void rejectsStaleCandidateAfterRefinement() {
    StageOutcome first =
        candidateOutcome(
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "first")),
            artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "first")),
            artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "first")));
    StageOutcome second =
        candidateOutcome(
            artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "second")),
            artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "second")),
            artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "second")));
    configureRuntime(profile(multiItemPolicy(), multiItemProduces()), new ScriptedCapability(first, second));
    startRun();

    Reference candidateA = acceptInput("draft-a").waiting().candidate();
    Reference candidateB = acceptInput("draft-b").waiting().candidate();
    assertNotEquals(candidateA, candidateB);

    Throwable stale =
        assertThrows(
            Exception.class,
            () ->
                runtime
                    .approve(new ApproveCommand(RUN_ID, candidateA, runStore.load(RUN_ID).orElseThrow().run().runRevision()))
                    .collect()
                    .asList()
                    .await()
                    .indefinitely());
    assertTrue(rootCause(stale) instanceof StaleApprovalException);

    runtime
        .approve(new ApproveCommand(RUN_ID, candidateB, runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    ProductPipelineRunDocument doc = runStore.load(RUN_ID).orElseThrow();
    assertEquals(StageStatus.SUCCEEDED, currentStage(doc).status());
    assertEquals(candidateB.artifactId(), currentStage(doc).approvedArtifactId());
  }

  @Test
  void negativeExpectedRevisionIsRejectedAsStale() {
    WaitingForApprovalResult waiting =
        runToCandidate(validCreateChainCandidate(), multiItemPolicy(), multiItemProduces());

    assertThrows(
        StaleApprovalException.class,
        () ->
            runtime
                .approve(new ApproveCommand(RUN_ID, waiting.waiting().candidate(), -1L))
                .collect()
                .asList()
                .await()
                .indefinitely());

    assertEquals(RunStatus.WAITING_FOR_APPROVAL, runStore.load(RUN_ID).orElseThrow().run().status());
  }

  @Test
  void approveAfterProcessRestartReloadsProfilePinsFromDurableManifest() {
    WaitingForApprovalResult waiting =
        runToCandidate(validCreateChainCandidate(), multiItemPolicy(), multiItemProduces());
    ProductPipelineProfileCatalog catalog = mock(ProductPipelineProfileCatalog.class);
    when(catalog.require(profile.profileId(), profile.profileVersion())).thenReturn(profile);
    CreateChainTestOrchestrator restarted =
        new CreateChainTestOrchestrator(
            new ProductPipelineRunSupport(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(List.of(new ScriptedCapability(validCreateChainCandidate()))),
                catalog,
                null,
                Clock.fixed(FIXED, ZoneOffset.UTC),
                null,
                null,
                null),
            runStore);

    restarted
        .approve(
            new ApproveCommand(
                RUN_ID,
                waiting.waiting().candidate(),
                runStore.load(RUN_ID).orElseThrow().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(StageStatus.SUCCEEDED, currentStage().status());
  }

  private WaitingForApprovalResult runToCandidate(
      StageOutcome outcome, ApprovalPolicy approvalPolicy, List<ArtifactTypeRef> produces) {
    configureRuntime(profile(approvalPolicy, produces), new ScriptedCapability(outcome));
    startRun();
    return acceptInput("candidate");
  }

  private ProductPipelineRunDocument runAndExpectFailure(
      StageOutcome outcome, ApprovalPolicy approvalPolicy, List<ArtifactTypeRef> produces) {
    configureRuntime(profile(approvalPolicy, produces), new ScriptedCapability(outcome));
    startRun();
    List<PipelineSignal> signals =
        runtime.acceptInput(new AcceptInputCommand(RUN_ID, "candidate")).collect().asList().await().indefinitely();
    PipelineSignal.WaitingForInput waiting =
        signals.stream()
            .filter(PipelineSignal.WaitingForInput.class::isInstance)
            .map(PipelineSignal.WaitingForInput.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(
        PipelineGates.STAGE_RETRY, PipelineGates.gateOf(waiting.prompt()).orElseThrow());
    return runStore.load(RUN_ID).orElseThrow();
  }

  private void configureRuntime(ProductPipelineProfile profile, StageCapability capability) {
    configureRuntime(profile, capability, null);
  }

  private void configureRuntime(
      ProductPipelineProfile profile, StageCapability capability, S3Service s3Service) {
    runtime =
        new CreateChainTestOrchestrator(new ProductPipelineRunSupport(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(List.of(capability)),
            null,
            null,
            Clock.fixed(FIXED, ZoneOffset.UTC),
            null,
            null,
            s3Service), runStore);
    this.profile = profile;
  }

  private ProductPipelineProfile profile(ApprovalPolicy approvalPolicy, List<ArtifactTypeRef> produces) {
    return new ProductPipelineProfile(
        1,
        "test-approval",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "collect",
                CAPABILITY_ID,
                List.of(new ArtifactTypeRef("user-input", 1)),
                produces,
                approvalPolicy,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("collect", "PLAN_APPROVED"),
        List.of(CAPABILITY_ID));
  }

  private void startRun() {
    runtime
        .startOrResume(new StartOrResumeCommand("conv-approval", RUN_ID, profile, sampleManifest(RUN_ID)))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private WaitingForApprovalResult acceptInput(String text) {
    List<PipelineSignal> signals =
        runtime.acceptInput(new AcceptInputCommand(RUN_ID, text)).collect().asList().await().indefinitely();
    PipelineSignal.WaitingForApproval waiting =
        signals.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    return new WaitingForApprovalResult(waiting, signals);
  }

  private StageSnapshot currentStage() {
    return currentStage(runStore.load(RUN_ID).orElseThrow());
  }

  private StageSnapshot currentStage(ProductPipelineRunDocument doc) {
    return doc.run().stages().stream().filter(s -> s.stageId().equals("collect")).findFirst().orElseThrow();
  }

  private ApprovalRecord latestApprovalV1() {
    Revision revision =
        artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
            .filter(item -> item.schemaVersion().equals("1"))
            .reduce((first, second) -> second)
            .orElseThrow();
    return artifactStore.payload(revision, ApprovalRecord.class);
  }

  private ApprovalRecordV2 latestApprovalV2() {
    Revision revision =
        artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
            .filter(item -> item.schemaVersion().equals("2"))
            .reduce((first, second) -> second)
            .orElseThrow();
    return artifactStore.payload(revision, ApprovalRecordV2.class);
  }

  private Revision latestApprovalRevision() {
    return artifactStore.history(RUN_ID, Kind.APPROVAL_RECORD).stream()
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private static ArtifactCandidate artifact(Kind kind, Object payload) {
    return new ArtifactCandidate(kind, payload, List.of());
  }

  private static StageOutcome candidateOutcome(ArtifactCandidate... candidates) {
    return new StageOutcome(StageOutcomeClass.CANDIDATE, List.of(candidates), "candidate", null);
  }

  private static StageOutcome validCreateChainCandidate() {
    return candidateOutcome(
        artifact(Kind.IMPLEMENTATION_PLAN, Map.of("plan", "ok")),
        artifact(Kind.PLAN_VALIDATION_RESULT, Map.of("validation", "ok")),
        artifact(Kind.CHAIN_PLAN_GRAPH, Map.of("graph", "ok")));
  }

  private static ApprovalPolicy multiItemPolicy() {
    return new ApprovalPolicy(new ArtifactTypeRef("implementation-plan", 2), multiItemCandidateSet());
  }

  private static List<ArtifactTypeRef> multiItemCandidateSet() {
    return List.of(
        new ArtifactTypeRef("implementation-plan", 2),
        new ArtifactTypeRef("plan-validation-result", 1),
        new ArtifactTypeRef("chain-plan-graph", 1));
  }

  private static List<ArtifactTypeRef> multiItemProduces() {
    return List.of(
        new ArtifactTypeRef("implementation-plan", 2),
        new ArtifactTypeRef("plan-validation-result", 1),
        new ArtifactTypeRef("chain-plan-graph", 1));
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private RunManifest sampleManifest(String runId) {
    return new RunManifest(
        runId,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry(CAPABILITY_ID, "1", "c1")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private static final class ScriptedCapability implements StageCapability {
    private final Queue<StageOutcome> outcomes;

    private ScriptedCapability(StageOutcome... outcomes) {
      this.outcomes = new ArrayDeque<>(List.of(outcomes));
    }

    @Override
    public String capabilityId() {
      return CAPABILITY_ID;
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      if (!context.attributes().containsKey("userText")) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need user input")));
      }
      StageOutcome outcome =
          outcomes.isEmpty()
              ? StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "no scripted outcome configured")
              : outcomes.remove();
      return Multi.createFrom().item(new CapabilitySignal.Completed(outcome));
    }
  }

  private record WaitingForApprovalResult(
      PipelineSignal.WaitingForApproval waiting, List<PipelineSignal> signals) {

    private WaitingForApprovalResult {
      signals = signals == null ? List.of() : List.copyOf(signals);
    }
  }
}
