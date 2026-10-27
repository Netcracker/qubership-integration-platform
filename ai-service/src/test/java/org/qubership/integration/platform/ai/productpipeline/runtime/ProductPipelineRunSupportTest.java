package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureProjection;
import org.qubership.integration.platform.ai.plan.RequirementClarificationCoordinator;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ProductPipelineRunSupportTest {

  @Test
  void completeRecoveryCauseSurvivesTheStrictPersistedReaderWithOrWithoutCaptureEvidence() throws Exception {
    var json = new ObjectMapper();
    var feedback = new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, "Repeated invalid field",
        CaptureFailureClass.IDENTICAL_SPAM, false, List.of(), "", true);
    var capture = new CaptureFailureEvidence(feedback, null,
        new CompilationArtifacts.Reference(CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT,
            "native-attempt", "native-hash"), List.of());
    for (boolean captured : List.of(false, true)) {
      var cause = new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, List.of(), "", true, captured ? capture : null);
      String saved = ProductPipelineRunSupport.recoveryCauseJson(cause);
      assertEquals(cause, json.readValue(saved, RecoveryCause.class));
      assertTrue(json.readTree(saved).path("noProgress").asBoolean());
    }
  }

  @Test
  void upstreamSuccessKeepsTheObserverErrorUntilTheObserverSucceeds() {
    var attrs = new HashMap<String, Object>();
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "Rejected downstream plan");
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "design-planning");
    ProductPipelineRunSupport.settleActiveRepair("requirement-analysis", attrs);
    assertTrue(org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence.haltRecorded(attrs));
    ProductPipelineRunSupport.settleActiveRepair("design-planning", attrs);
    assertFalse(org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence.haltRecorded(attrs));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"question", "allowance", "failure", "other-observer"})
  void onlyTheValidatedReadyGateCanSettleItsOwnDiscoveryDefect(String scenario) {
    var attrs = new HashMap<String, Object>();
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "Retain the unresolved native defect");
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR,
        "other-observer".equals(scenario) ? "design-execution" : "requirement-discovery");
    attrs.put(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR, "requirement-discovery");
    var before = Map.copyOf(attrs);
    String gate = "question".equals(scenario)
        ? org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.STAGE_CLARIFICATION
        : "allowance".equals(scenario)
            ? org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.RECOVERY_REPAIR_ALLOWANCE
            : org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.REQUIREMENT_DRAFT_READY;
    var outcome = org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome.of(
        "failure".equals(scenario)
            ? org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass.CONTRACT_FAILURE
            : org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass.NEEDS_INPUT,
        org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.tag(gate, "Native checkpoint"));

    assertFalse(ProductPipelineRunSupport.settleReadyDiscoveryRepair("requirement-discovery", outcome, attrs));
    assertEquals(before, attrs);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"requirement-discovery", "another-owner"})
  void readyDiscoverySettlesOnlyItsOwnPreparation(String owner) {
    var attrs = new HashMap<String, Object>();
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "Resolved native defect");
    attrs.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "requirement-discovery");
    attrs.put(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR, owner);
    attrs.put(ProductPipelineRunSupport.ACCEPTED_SOURCE_BRIEF_REF_ATTR, "retained-source-reference");
    attrs.put("mappingRepairAutomatic", "true");
    var outcome = org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome.of(
        org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass.NEEDS_INPUT,
        org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.tag(
            org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.REQUIREMENT_DRAFT_READY,
            "Requirements draft saved."));

    assertTrue(ProductPipelineRunSupport.settleReadyDiscoveryRepair("requirement-discovery", outcome, attrs));
    assertFalse(attrs.containsKey(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
    if ("another-owner".equals(owner)) {
      assertEquals(owner, attrs.get(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR));
      assertEquals("retained-source-reference", attrs.get(ProductPipelineRunSupport.ACCEPTED_SOURCE_BRIEF_REF_ATTR));
      assertEquals("true", attrs.get("mappingRepairAutomatic"));
    } else {
      assertFalse(attrs.containsKey(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR));
      assertFalse(attrs.containsKey(ProductPipelineRunSupport.ACCEPTED_SOURCE_BRIEF_REF_ATTR));
      assertFalse(attrs.containsKey("mappingRepairAutomatic"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = StageStatus.class, names = {"SUCCEEDED"})
  void settledCaptureCannotRestoreAnEarlierActiveError(StageStatus settled) {
    String json = ProductPipelineRunSupport.haltEvidence(Map.of(
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "Rejected draft",
        ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "requirement-discovery"), null);
    var failed = documentWithHalt("requirement-discovery", json);
    var attempts = new java.util.ArrayList<>(failed.attempts());
    attempts.add(attempt("requirement-discovery", 3, settled));
    var doc = new ProductPipelineRunDocument(failed.run(), attempts, failed.transitions(), failed.blobVersion());
    var attrs = new HashMap<String, Object>();
    ProductPipelineRunSupport.overlayHaltEvidenceForStage(doc, "requirement-discovery", attrs);
    assertFalse(org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence.haltRecorded(attrs));
    assertEquals(2, doc.attempts().size());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"", "requirement-discovery"})
  void aStageLocalHaltCannotResurrectOrReplaceTheGlobalPreparationOwner(String effectiveOwner) {
    var old = ProductPipelineRunSupport.haltEvidence(Map.of(
        ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR, "design-input",
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "Old rejected semantic capture"), null);
    var doc = new ProductPipelineRunDocument(new RunSnapshot("run-1", "conv-1", 3L, RunStatus.RUNNING,
        "design-input", List.of(), null), List.of(new StageAttempt("old", "design-input", 1L,
            StageStatus.FAILED, Instant.EPOCH, Instant.EPOCH, List.of(), old)), List.of(), "v3");
    var attributes = new HashMap<String, Object>();
    attributes.put(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR, effectiveOwner);
    ProductPipelineRunSupport.overlayHaltEvidenceForStage(doc, "design-input", attributes);
    assertEquals(effectiveOwner, attributes.get(ProductPipelineRunSupport.RECOVERY_PREPARING_OWNER_ATTR));
    assertEquals("Old rejected semantic capture", attributes.get(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"same-defect", "other-stage", "consumed-offer"})
  void aResourceAllowanceCannotRenewAConsumedOfferOrAnEarlierStagesOffer(String scenario) {
    String stage = "design-planning";
    var transitions = new java.util.ArrayList<RunTransition>();
    transitions.add(new RunTransition(1L, 2L, RunStatus.RUNNING, RunStatus.WAITING_FOR_INPUT,
        "other-stage".equals(scenario) ? "design-input" : stage, java.time.Instant.EPOCH,
        org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.tag(
            org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.RECOVERY_REPAIR_ALLOWANCE, "Grant repair")));
    if ("consumed-offer".equals(scenario)) {
      transitions.add(new RunTransition(2L, 3L, RunStatus.WAITING_FOR_INPUT, RunStatus.RUNNING,
          stage, java.time.Instant.EPOCH, "repair-grant:targeted:fixture"));
    }
    transitions.add(new RunTransition(3L, 4L, RunStatus.RUNNING, RunStatus.WAITING_FOR_INPUT,
        stage, java.time.Instant.EPOCH,
        org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.tag(
            org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates.RECOVERY_RETRY_TECHNICAL, "Model resources spent")));
    var doc = new ProductPipelineRunDocument(new RunSnapshot("run-1", "conv-1", 4L,
        RunStatus.WAITING_FOR_INPUT, stage, List.of(), null), List.of(), transitions, "v4");

    assertEquals("same-defect".equals(scenario), ProductPipelineRunSupport.resourcePauseFollowedRepairOffer(doc));
  }

  private static final Instant FIXED = Instant.parse("2026-08-25T00:00:00Z");

  @Test
  void haltEvidenceRoundTripsRecoveryEvidenceRefForTheReopenedProducer() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "mapping contract rejected");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, "VALIDATION_FAILURE");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "design-execution");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_SKILL_ATTR, "cip-script-generator");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, "MAPPING_UNKNOWN_TARGET");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, "MAPPING_CONTRACT");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_REQUESTED_FACT_ATTR, "");
    attributes.put(ProductPipelineRunSupport.DIAGNOSED_OWNER_STAGE_ATTR, "requirement-analysis");
    attributes.put(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR, "hash-active-evidence");
    String json = ProductPipelineRunSupport.haltEvidence(attributes, "brief-hash");
    Map<String, Object> restored = new HashMap<>();
    ProductPipelineRunSupport.overlayHaltEvidenceForStage(
        documentWithHalt("requirement-analysis", json), "requirement-analysis", restored);

    assertEquals("hash-active-evidence", restored.get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR));
    assertEquals("MAPPING_CONTRACT", restored.get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR));
    assertEquals("design-execution", restored.get(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR));
    assertEquals("cip-script-generator", restored.get(ProductPipelineRunSupport.STAGE_ERROR_FAILED_SKILL_ATTR));
  }

  @Test
  void overlayHaltEvidenceKeepsTheObservingStageRefOnTheProducer() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR, "mapping contract rejected");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_OUTCOME_ATTR, "VALIDATION_FAILURE");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FAILED_STAGE_ATTR, "design-execution");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR, "MAPPING_UNKNOWN_TARGET");
    attributes.put(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR, "MAPPING_CONTRACT");
    attributes.put(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR, "hash-observing");
    String observingJson = ProductPipelineRunSupport.haltEvidence(attributes, null);
    String producerJson = ProductPipelineRunSupport.haltEvidence(attributes, "brief-hash");
    ProductPipelineRunDocument doc =
        new ProductPipelineRunDocument(
            new RunSnapshot(
                "run-1",
                "conv-1",
                2L,
                RunStatus.RUNNING,
                "requirement-analysis",
                List.of(),
                null),
            List.of(
                haltAttempt("design-execution", 1L, observingJson),
                haltAttempt("requirement-analysis", 2L, producerJson)),
            List.of(),
            null);
    Map<String, Object> producerAttrs = new HashMap<>();
    ProductPipelineRunSupport.overlayHaltEvidenceForStage(doc, "requirement-analysis", producerAttrs);
    Map<String, Object> observingAttrs = new HashMap<>();
    ProductPipelineRunSupport.overlayHaltEvidenceForStage(doc, "design-execution", observingAttrs);

    assertEquals("hash-observing", producerAttrs.get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR));
    assertEquals("hash-observing", observingAttrs.get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR));
  }

  @Test
  void legacyHaltEvidenceWithoutRecoveryEvidenceRefDoesNotInventAHash() {
    String legacyJson =
        """
        {"stageErrorContext":"mapping contract rejected","stageErrorOutcomeClass":"VALIDATION_FAILURE","stageErrorFailedStageId":"design-execution","stageErrorFindings":"MAPPING_UNKNOWN_TARGET","stageErrorCauseCode":"MAPPING_CONTRACT","stageErrorRequestedFact":"","diagnosedOwnerStageId":"requirement-analysis"}
        """;
    Map<String, Object> restored = new HashMap<>();
    ProductPipelineRunSupport.overlayHaltEvidenceForStage(
        documentWithHalt("requirement-analysis", legacyJson), "requirement-analysis", restored);

    assertEquals("MAPPING_CONTRACT", restored.get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR));
    Object ref = restored.get(ProductPipelineRunSupport.RECOVERY_EVIDENCE_REF_ATTR);
    assertTrue(ref == null || String.valueOf(ref).isBlank());
  }

  @Test
  void rebuildsOnlyConsecutiveFailedAttemptsForEachStage() {
    List<StageAttempt> attempts =
        List.of(
            attempt("analysis", 1, StageStatus.FAILED),
            attempt("planning", 2, StageStatus.FAILED),
            attempt("analysis", 3, StageStatus.SUCCEEDED),
            attempt("planning", 4, StageStatus.FAILED),
            attempt("analysis", 5, StageStatus.FAILED),
            nonTechnicalAttempt("analysis", 6));

    assertEquals(
        Map.of("run-1:planning", 2, "run-1:analysis", 1),
        ProductPipelineRunSupport.consecutiveTechnicalRetries("run-1", attempts, List.of()));
  }

  @Test
  void restoreForExternalWorkflowVerifiesAvailablePinWithoutLoadingANewerContract()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    ProductPipelineRunStore runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    ProductPipelineArtifactStore artifactStore = new ProductPipelineArtifactStore(artifacts);
    ProductPipelineProfile profile;
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v2.yaml")) {
      profile = ProductPipelineProfileParser.parse(in);
    }

    CompilerRunPinResolver pinResolver = mock(CompilerRunPinResolver.class);
    doNothing().when(pinResolver).verifyAvailable(any());
    when(pinResolver.resolve(any(ProductPipelineProfile.class), any(KnowledgeQueryContext.class)))
        .thenThrow(new IllegalStateException("must not resolve a new pin on restore"));
    when(pinResolver.resolve(any(), any(ChainSemanticRevision.class), any(CompilerContract.class)))
        .thenThrow(new IllegalStateException("must not resolve a new pin on restore"));
    doThrow(new IllegalStateException("must not load a newer compiler contract on restore"))
        .when(pinResolver)
        .verifyPersistedPin(any(), any(), any(CompilerContract.class));

    ProductPipelineRunSupport support =
        CreateChainTestOrchestrator.supportBuilder(
                runStore, artifactStore, new StageCapabilityRegistry(List.of()), clock)
            .profileCatalog(new ProductPipelineProfileCatalog(List.of(profile)))
            .compilerRunPinResolver(pinResolver)
            .build();

    RunManifest manifest = restoreManifest(profile);
    StartOrResumeCommand command =
        new StartOrResumeCommand("conv-restore-pin", "run-restore-pin", profile, manifest);
    support.bootstrap(command, "flow-restore-pin");
    support
        .restoreForExternalWorkflow(command)
        .collect()
        .asList()
        .await()
        .indefinitely();

    verify(pinResolver, org.mockito.Mockito.atLeastOnce()).verifyAvailable(any());
    verify(pinResolver, never())
        .resolve(any(ProductPipelineProfile.class), any(KnowledgeQueryContext.class));
    verify(pinResolver, never())
        .resolve(any(), any(ChainSemanticRevision.class), any(CompilerContract.class));
    verify(pinResolver, never()).verifyPersistedPin(any(), any(), any(CompilerContract.class));
  }

  private static RunManifest restoreManifest(ProductPipelineProfile profile) {
    return new RunManifest(
        "run-restore-pin",
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        new CompilerRunPin(
            "compiler",
            "1",
            "digest",
            2,
            "1",
            "catalog-hash",
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null));
  }

  private static ProductPipelineRunDocument documentWithHalt(String stageId, String haltJson) {
    return new ProductPipelineRunDocument(
        new RunSnapshot("run-1", "conv-1", 1L, RunStatus.RUNNING, stageId, List.of(), null),
        List.of(haltAttempt(stageId, 1L, haltJson)),
        List.of(),
        null);
  }

  private static StageAttempt haltAttempt(String stageId, long revision, String haltJson) {
    return new StageAttempt(
        "attempt-" + revision,
        stageId,
        revision,
        StageStatus.RUNNING,
        FIXED,
        FIXED,
        List.of(),
        haltJson);
  }

  @Test
  void approvalPromptCarriesTheReasonTheRunCameBack() {
    Map<String, Object> attributes =
        new HashMap<>(
            Map.of(
                ProductPipelineRunSupport.RECOVERY_AUTHOR_NOTE_ATTR,
                "The entry point was captured without its trigger."));

    String prompt =
        ProductPipelineRunSupport.withAuthorNote(
            attributes, ProductPipelineRunSupport.BRIEF_REPAIR_APPROVAL_PROMPT);

    assertTrue(prompt.startsWith("The entry point was captured without its trigger."), prompt);
    assertTrue(prompt.endsWith(ProductPipelineRunSupport.BRIEF_REPAIR_APPROVAL_PROMPT), prompt);
  }

  @Test
  void approvalPromptStaysBareWithoutARecordedReason() {
    assertEquals(
        ProductPipelineRunSupport.BRIEF_REPAIR_APPROVAL_PROMPT,
        ProductPipelineRunSupport.withAuthorNote(
            new HashMap<>(), ProductPipelineRunSupport.BRIEF_REPAIR_APPROVAL_PROMPT));
  }

  @Test
  void waitingForAnAuthoredQuestionPersistsAndRestoresItsOffer() {
    var harness = new OfferHarness();
    String conversationId = "conv-offer";
    String runId = "run-offer";
    var accepted = harness.acceptChainCallQuestion(conversationId);
    String prompt = PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, "Name the operation for this call.");
    var waiting = harness.wait(runId, conversationId, "requirement-discovery", "requirement-discovery", prompt);
    var transition = waiting.transitions().stream()
        .filter(item -> item.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((earlier, later) -> later)
        .orElseThrow();
    var clarification = transition.clarification();
    assertNotNull(clarification, "waiting transitions do not carry an offer");
    assertNull(clarification.answer());
    var offer = clarification.offer();
    assertNotNull(offer, "waiting transitions do not carry an offer");
    assertEquals(1, waiting.transitions().stream().filter(item -> item.clarification() != null
        && item.clarification().offer() != null).count());
    assertEquals(accepted.reference(), offer.draftReference());
    assertEquals("chain-target", offer.questionId());
    assertEquals(transition.toRevision(), offer.runRevision());
    assertEquals(harness.generation(conversationId), offer.sourceGeneration());
    assertEquals(new RequirementClarificationCoordinator().createOffer(
        new RequirementClarificationCoordinator().activeQuestion(
            accepted, harness.drafts.get(conversationId).orElseThrow(), offer.sourceGeneration()).orElseThrow(),
        transition.toRevision()), offer);

    var restored = harness.freshSupport();
    restored.restoreForExternalWorkflow(harness.command(runId, conversationId, waiting))
        .collect().asList().await().indefinitely();
    var clarify = assertInstanceOf(CreateChainPendingAction.Clarify.class,
        harness.facade().snapshot(conversationId).orElseThrow().pendingAction());
    assertEquals(offer.offerId(), clarify.clarificationOfferId());
    var decision = assertInstanceOf(ChatEvent.Decision.class,
        ChatEvent.decision(clarify, waiting.run().runRevision(), "Name the operation for this call."));
    assertEquals(offer.offerId(), decision.id());
  }

  @Test
  void idsCatalogBindingAndOtherGatesDoNotAcquireARequirementOffer() {
    var harness = new OfferHarness();
    harness.acceptChainCallQuestion("conv-ids");
    harness.acceptChainCallQuestion("conv-binding");
    harness.acceptChainCallQuestion("conv-mapping");
    var ids = harness.wait("run-ids", "conv-ids", "requirement-discovery", "requirement-discovery",
        PipelineGates.tag(PipelineGates.IDS_PATH_CHOICE, "Show the Integration Design Specification (IDS) for review?"));
    var binding = harness.wait("run-binding", "conv-binding", "design-execution", "design-execution",
        PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, "Name the catalog service this interaction should use."));
    var mapping = harness.wait("run-mapping", "conv-mapping", "requirement-discovery", "requirement-discovery",
        PipelineGates.tag(PipelineGates.MAPPING_GAP, "Describe the field mappings."));
    for (var doc : List.of(ids, binding, mapping)) {
      var transition = doc.transitions().stream()
          .filter(item -> item.toStatus() == RunStatus.WAITING_FOR_INPUT)
          .reduce((earlier, later) -> later)
          .orElseThrow();
      assertNull(transition.clarification(), doc.run().runId());
      var clarify = assertInstanceOf(CreateChainPendingAction.Clarify.class,
          harness.facade().snapshot(doc.run().conversationId()).orElseThrow().pendingAction());
      assertEquals("", clarify.clarificationOfferId(), doc.run().runId());
      var decision = assertInstanceOf(ChatEvent.Decision.class,
          ChatEvent.decision(clarify, doc.run().runRevision(), clarify.reason()));
      assertEquals("clarify:" + doc.run().runRevision(), decision.id(), doc.run().runId());
    }
  }

  @Test
  void specializedClarificationAnswersAdmitTheirGate() {
    for (String gate : List.of(
        PipelineGates.TARGET_FOLDER_CHOICE, PipelineGates.REQUIREMENT_RECOVERY_CLARIFICATION)) {
      var harness = new OfferHarness();
      String runId = "run-" + gate;
      harness.wait(runId, "conv-" + gate, "requirement-discovery", "requirement-discovery",
          PipelineGates.tag(gate, "Choose one."));
      var signals = harness.support().recordInput(
          new AcceptInputCommand(runId, "A/Salesforce", "cmd-" + gate, "hash-" + gate))
          .collect().asList().await().indefinitely();
      assertTrue(signals.isEmpty(), gate);
      assertEquals(gate, harness.support().runAttributes(runId)
          .get(ProductPipelineRunSupport.ADMITTED_INPUT_GATE_ATTR), gate);
    }
  }

  @Test
  void stageClarificationWithoutAnAuthoredQuestionStoresNoOffer() {
    var harness = new OfferHarness();
    String conversationId = "conv-no-question";
    String runId = "run-no-question";
    var accepted = harness.acceptAuthoredDraft(conversationId, List.of());
    assertTrue(new RequirementClarificationCoordinator().activeQuestion(
        accepted, harness.drafts.get(conversationId).orElseThrow(), harness.generation(conversationId)).isEmpty());
    String prompt = PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, "Name the operation for this call.");
    var waiting = harness.wait(runId, conversationId, "requirement-discovery", "requirement-discovery", prompt);
    var transition = waiting.transitions().stream()
        .filter(item -> item.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((earlier, later) -> later)
        .orElseThrow();
    assertNull(transition.clarification(), runId);
    var clarify = assertInstanceOf(CreateChainPendingAction.Clarify.class,
        harness.facade().snapshot(conversationId).orElseThrow().pendingAction());
    assertEquals("", clarify.clarificationOfferId(), runId);
    var decision = assertInstanceOf(ChatEvent.Decision.class,
        ChatEvent.decision(clarify, waiting.run().runRevision(), clarify.reason()));
    assertEquals("clarify:" + waiting.run().runRevision(), decision.id(), runId);
  }

  @Test
  void requirementClarificationRebuildsTheActiveQuestionOffer() {
    var harness = new OfferHarness();
    String conversationId = "conv-offer-builder";
    String runId = "run-offer-builder";
    harness.acceptChainCallQuestion(conversationId);
    String prompt = PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, "Name the operation for this call.");
    var waiting = harness.wait(runId, conversationId, "requirement-discovery", "requirement-discovery", prompt);
    long revision = waiting.run().runRevision();
    var coordinator = new RequirementClarificationCoordinator();
    var active = coordinator.activeQuestion(
        harness.drafts.latestRevision(conversationId).orElseThrow(),
        harness.drafts.get(conversationId).orElseThrow(),
        harness.generation(conversationId)).orElseThrow();
    var built = harness.support().requirementClarification(waiting, revision, prompt);
    assertNotNull(built, runId);
    assertEquals(coordinator.createOffer(active, revision), built.offer());
    assertNull(built.answer());
    assertNull(harness.support().requirementClarification(
        waiting, revision, PipelineGates.retag(PipelineGates.MAPPING_GAP, "Describe the field mappings.")));

    String otherConversation = "conv-other-capability";
    harness.acceptChainCallQuestion(otherConversation);
    String otherPrompt = PipelineGates.retag(
        PipelineGates.STAGE_CLARIFICATION, "Name the catalog service this interaction should use.");
    var other = harness.wait(
        "run-other-capability", otherConversation, "design-execution", "design-execution", otherPrompt);
    assertNull(harness.support().requirementClarification(other, other.run().runRevision(), otherPrompt));
  }

  @Test
  void theTransitionThatConsumesAnAnswerPersistsItBeforeTheNextOffer() throws Exception {
    var harness = new OfferHarness();
    String conversationId = "conv-answer-envelope";
    String runId = "run-answer-envelope";
    harness.acceptChainCallQuestion(conversationId, false);
    String prompt = PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, "Name the operation for this call.");
    var waiting = harness.wait(runId, conversationId, "requirement-discovery", "requirement-discovery", prompt);
    var first = waiting.transitions().stream()
        .filter(item -> item.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((earlier, later) -> later).orElseThrow();
    assertNull(first.clarification().answer());
    var consumed = harness.consumePinnedAnswer(runId, conversationId);
    assertNull(first.clarification().answer());
    var latest = consumed.transitions().stream()
        .filter(item -> item.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((earlier, later) -> later).orElseThrow();
    assertNotNull(latest.clarification());
    assertNotNull(latest.clarification().answer());
    assertEquals(first.clarification().offer().offerId(), latest.clarification().answer().offerId());
    assertEquals(org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Disposition.NON_ANSWER,
        latest.clarification().answer().disposition());
    assertNotNull(latest.clarification().offer());
    assertNotEquals(first.clarification().offer().offerId(), latest.clarification().offer().offerId());
  }

  private static StageAttempt attempt(String stageId, long revision, StageStatus outcome) {
    return new StageAttempt(
        "attempt-" + revision, stageId, revision, outcome, FIXED, FIXED, List.of(), null);
  }

  private static StageAttempt nonTechnicalAttempt(String stageId, long revision) {
    return new StageAttempt(
        "attempt-" + revision,
        stageId,
        revision,
        StageStatus.FAILED,
        FIXED,
        FIXED,
        List.of(),
        ProductPipelineRunSupport.nonTechnicalFailureEvidence("invalid contract"));
  }

  private static final class OfferHarness {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    private final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, FIXED_CLOCK);
    private final CompilationSessions sessions = new CompilationSessions(blobs, mapper, FIXED_CLOCK);
    private final ConversationService conversations = new ConversationService();
    private final ProductPipelineRunStore runStore = new ProductPipelineRunStore(blobs, mapper, FIXED_CLOCK);
    private final ProductPipelineArtifactStore artifactStore = new ProductPipelineArtifactStore(artifacts);
    final RequirementDraftStore drafts = new RequirementDraftStore(artifacts, sessions, conversations);
    private final Map<String, String> prompts = new ConcurrentHashMap<>();
    private final Map<String, StartOrResumeCommand> commands = new HashMap<>();
    private final List<ProductPipelineProfile> profiles = new ArrayList<>();
    private final ProductPipelineRunSupport support = newSupport();

    CompilationArtifacts.Revision acceptChainCallQuestion(String conversationId) {
      return acceptChainCallQuestion(conversationId, true);
    }

    CompilationArtifacts.Revision acceptChainCallQuestion(String conversationId, boolean resolved) {
      return acceptAuthoredDraft(conversationId, resolved, List.of(new QuestionInput(
          "chain-target",
          List.of("child"),
          "Which chain-trigger element id should this chain call use?",
          new AuthorQuestionEvidence(
              "child", "targetReference", AuthorQuestionEvidence.Kind.CATALOG_CHOICE, List.of("turn-1")))));
    }

    CompilationArtifacts.Revision acceptAuthoredDraft(String conversationId, List<QuestionInput> openQuestions) {
      return acceptAuthoredDraft(conversationId, true, openQuestions);
    }

    CompilationArtifacts.Revision acceptAuthoredDraft(String conversationId, boolean resolved,
        List<QuestionInput> openQuestions) {
      String text = "Call the existing chain.";
      var input = conversations.archive().beginInput(conversationId, text, null);
      conversations.archive().resolveInput(conversationId, input, text);
      drafts.beginTurn(conversationId);
      var authored = new DraftInput(
          new FlowInput(
              List.of(
                  new InteractionInput("entry", Direction.INBOUND, "Caller", "GET /context", null, null, null),
                  new InteractionInput("child", Direction.OUTBOUND, "Existing chain", "Run", null, null, null)),
              List.of(new TransitionInput("entry", "child"))),
          List.of(new FactInput("goal", List.of("child"), FactKind.GOAL, Polarity.POSITIVE, "Call the existing chain.")),
          List.of(
              new CapabilityInput("entry", "http-trigger", null, HttpMethod.GET, "/context", null, null, null),
              new CapabilityInput("child", "chain-call-2", null, null, null, null, null,
                  resolved ? "trigger-element" : null)),
          openQuestions,
          new DraftSettings(false, null));
      drafts.put(conversationId, RequirementCaptureProjection.toDraft(
          authored, null, List.of(), "1", "hash",
          resolved ? Map.of("child", "resolved-trigger") : Map.of()));
      return drafts.latestRevision(conversationId).orElseThrow();
    }

    String generation(String conversationId) {
      return conversations.archive().read(conversationId).generation();
    }

    ProductPipelineRunDocument wait(
        String runId, String conversationId, String stageId, String capabilityId, String prompt) {
      prompts.put(runId, prompt);
      var profile = new ProductPipelineProfile(
          1,
          "offer-" + runId,
          "1",
          List.of(),
          List.of(new ProfileStage(
              stageId, capabilityId, List.of(), List.of(), null, null, new RetryPolicy(0, 1L))),
          new TerminalPolicy(stageId, "PLAN_APPROVED"),
          List.of(stageId));
      profiles.add(profile);
      var manifest = new RunManifest(
          runId,
          null,
          List.of(),
          "product",
          profile.profileId(),
          profile.profileVersion(),
          "profile-sha",
          "baseline",
          "baseline-sha",
          List.of(new DependencyClosureEntry(stageId, "1", "stage-sha")),
          "closure-sha",
          new KnowledgePackageRef("knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
          "24.4",
          List.of(),
          null);
      var command = new StartOrResumeCommand(conversationId, runId, profile, manifest);
      commands.put(runId, command);
      support.bootstrap(command, "flow-" + runId);
      support.stageExecutor().execute(runId, stageId).await().indefinitely();
      return runStore.load(runId).orElseThrow();
    }

    StartOrResumeCommand command(String runId, String conversationId, ProductPipelineRunDocument ignored) {
      return commands.get(runId);
    }

    ProductPipelineRunSupport support() {
      return support;
    }

    ProductPipelineRunSupport freshSupport() {
      return newSupport();
    }

    CreateChainApplicationFacade facade() {
      return new CreateChainApplicationFacade(
          mock(CreateRunSelectionService.class),
          mock(CreateRunBindingStore.class),
          mock(CreateChainOrchestrator.class),
          runStore,
          new ProductPipelineProfileCatalog(profiles),
          drafts);
    }

    private ProductPipelineRunSupport newSupport() {
      return CreateChainTestOrchestrator.supportBuilder(
              runStore,
              artifactStore,
              new StageCapabilityRegistry(List.of(
                  promptCapability("requirement-discovery"),
                  promptCapability("design-execution"))),
              FIXED_CLOCK)
          .build()
          .withConversations(conversations)
          .withNativeRequirementCapture(drafts, null);
    }

    ProductPipelineRunDocument consumePinnedAnswer(String runId, String conversationId) throws Exception {
      var waiting = runStore.load(runId).orElseThrow();
      var offer = waiting.transitions().stream().map(RunTransition::clarification)
          .filter(java.util.Objects::nonNull).map(item -> item.offer()).filter(java.util.Objects::nonNull)
          .reduce((earlier, later) -> later).orElseThrow();
      String reply = "I do not know the element id yet.";
      String turnId = org.qubership.integration.platform.ai.chat.conversation.ConversationArchive
          .deterministicAnswerTurnId(offer.offerId(), reply);
      var metadata = mapper.createObjectNode().put("clarificationOfferId", offer.offerId());
      var input = conversations.archive().beginInput(conversationId, turnId, reply, metadata);
      conversations.archive().resolveInput(conversationId, input, reply);
      drafts.beginTurn(conversationId, reply);
      var readyIds = conversations.archive().read(conversationId).activeTurns().stream()
          .filter(turn -> "READY".equals(turn.status())).map(turn -> turn.id()).toList();
      var question = drafts.get(conversationId).orElseThrow().authoredDraft().openQuestions().getFirst();
      var reviewed = new QuestionInput(question.questionId(), question.interactionIds(), question.text(),
          new AuthorQuestionEvidence(question.evidence().interactionId(), question.evidence().propertyKey(),
              question.evidence().kind(), readyIds));
      var update = new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate(
          List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of(reviewed), List.of(), null, List.of(), null,
          new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClarificationDispositionInput(
              question.questionId(),
              org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Disposition.NON_ANSWER,
              turnId, reply, List.of()));
      var tools = new org.qubership.integration.platform.ai.plan.RequirementCaptureTools(
          drafts, new org.qubership.integration.platform.ai.plan.RequirementDraftTool(drafts), mapper);
      try (var ignored = org.qubership.integration.platform.ai.chat.ToolSession.open(conversationId)) {
        var opened = mapper.readValue(tools.updateRequirementDraft(
            new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of(), null)),
            org.qubership.integration.platform.ai.plan.RequirementCaptureResult.class);
        var confirmed = mapper.readValue(tools.updateRequirementDraft(update),
            org.qubership.integration.platform.ai.plan.RequirementCaptureResult.class);
        if (opened.accepted() || !confirmed.accepted()) {
          throw new AssertionError(opened + " then " + confirmed);
        }
      }
      var current = runStore.load(runId).orElseThrow();
      long expected = current.run().runRevision();
      var transition = new RunTransition(expected, expected + 1L, current.run().status(), RunStatus.RUNNING,
          current.run().currentStageId(), FIXED, "accepted clarification", null, null);
      var attempt = new StageAttempt("resume-" + expected, current.run().currentStageId(), expected + 1L,
          StageStatus.RUNNING, FIXED, FIXED, List.of(), null);
      runStore.commit(expected, org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit.statusChange(
          current.run(), StageStatus.RUNNING, current.run().stages(), attempt, transition));
      support.stageExecutor().execute(runId, current.run().currentStageId()).await().indefinitely();
      return runStore.load(runId).orElseThrow();
    }

    private StageCapability promptCapability(String capabilityId) {
      return new StageCapability() {
        @Override
        public String capabilityId() {
          return capabilityId;
        }

        @Override
        public Multi<CapabilitySignal> execute(StageExecutionContext context) {
          return Multi.createFrom().item(new CapabilitySignal.Completed(
              StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, prompts.get(context.runId()))));
        }
      };
    }
  }

  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
}
