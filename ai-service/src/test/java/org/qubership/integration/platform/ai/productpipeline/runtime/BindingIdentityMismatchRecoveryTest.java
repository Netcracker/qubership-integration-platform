package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.BindingResolutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultExecutorCatalogBindingAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/**
 * Binding versus semantic identity mismatch is repaired at design-input. Fake LLM and catalog;
 * real adapter, routing, and ledger. Derived plan candidates require approval again.
 */
class BindingIdentityMismatchRecoveryTest {

  private static final Instant FIXED = Instant.parse("2026-09-10T12:00:00Z");
  private static final String RUN_ID = "run-identity-mismatch-1";
  private static final String CONV_ID = "conv-identity-mismatch-1";
  private static final String BINDING_INTERACTION_ID = "wfms-create-work-order";
  private static final String SEMANTIC_MISMATCH_ID = "create-work-order";
  private static final String SERVICE_NAME = "WFMS Create Work Order";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private CatalogSystemReadTool catalog;
  private BindingAwareExecution execution;
  private AtomicInteger designInputCalls;
  private AtomicInteger planningCalls;
  private ProductPipelineProfile profile;
  private Clock clock;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    catalog = mock(CatalogSystemReadTool.class);
    stubCatalog();
    execution =
        new BindingAwareExecution(
            artifactStore,
            new DefaultExecutorCatalogBindingAdapter(catalog, mock(OperationSchemaLoader.class)));
    designInputCalls = new AtomicInteger();
    planningCalls = new AtomicInteger();
    profile = fourStageProfile();
    support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(
                        discoveryCapability(),
                        designInputCapability(),
                        planningCapability(),
                        execution)),
                clock)
            .build();
    runtime = new CreateChainTestOrchestrator(support, runStore);
  }

  @Test
  void identityMismatchRepairsDesignInputAndRenewsPlanApproval() {
    waitAtFirstPlanApproval();
    String firstPlanHash = snapshot("planning").approvableReference().contentHash();
    List<CatalogBindingHint> hintsBefore = persistedHints();
    assertEquals(1, hintsBefore.size());
    assertEquals(BINDING_INTERACTION_ID, hintsBefore.getFirst().interactionId());

    approveCurrentPlan();

    assertEquals(RUN_ID, run().run().runId());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals("planning", run().run().currentStageId());
    assertEquals(StageStatus.WAITING_FOR_APPROVAL, snapshot("planning").status());
    assertEquals(StageStatus.PENDING, snapshot("design-execution").status());
    assertEquals(2, designInputCalls.get());
    assertEquals(2, planningCalls.get());
    assertEquals(1, execution.calls.get());
    assertFalse(execution.compilerInvoked.get());
    assertEquals("design-input", support.diagnosedOwnerStageId(RUN_ID).orElse(""));
    String rebuiltPlanHash = snapshot("planning").approvableReference().contentHash();
    assertNotEquals(firstPlanHash, rebuiltPlanHash);
    List<CatalogBindingHint> hintsAfterRepair = persistedHints();
    assertEquals(1, hintsAfterRepair.size());
    assertEquals(BINDING_INTERACTION_ID, hintsAfterRepair.getFirst().interactionId());
    assertTrue(
        hintsAfterRepair.stream()
            .noneMatch(hint -> SEMANTIC_MISMATCH_ID.equals(hint.interactionId())),
        hintsAfterRepair.toString());
    assertEquals(BINDING_INTERACTION_ID, serviceCallId(latestRevision()));

    List<PipelineSignal> afterSecondApproval = approveCurrentPlan();

    assertEquals(RunStatus.PLAN_APPROVED, run().run().status());
    assertTrue(
        afterSecondApproval.stream().anyMatch(PipelineSignal.Completed.class::isInstance),
        afterSecondApproval.toString());
    assertEquals(2, execution.calls.get());
    assertTrue(execution.compilerInvoked.get());
    assertEquals(BINDING_INTERACTION_ID, execution.lastBinding.serviceCallId());
    assertEquals("op-create-wo", execution.lastBinding.operationId());
    assertEquals(1, persistedHints().size());
    assertEquals(BINDING_INTERACTION_ID, persistedHints().getFirst().interactionId());
  }

  @Test
  void catalogWriteStopsIdentityMismatchWithoutUpstreamReplay() {
    waitAtFirstPlanApproval();
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.MATERIALIZATION_RESULT,
            "1",
            "test",
            "1",
            Map.of("ok", true),
            List.of(),
            null,
            provenance()));
    int designCallsBefore = designInputCalls.get();
    int planningBefore = planningCalls.get();

    List<PipelineSignal> signals = approveCurrentPlan();

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    String prompt = latestWaitingPrompt();
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(prompt).orElse(""));
    assertEquals(
        HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.name(),
        PipelineGates.guardOf(prompt).orElse(""));
    assertTrue(
        PipelineGates.strip(prompt).contains(HaltRecoveryGuard.CATALOG_ALREADY_WRITTEN.cardSentence()),
        prompt);
    assertEquals(designCallsBefore, designInputCalls.get());
    assertEquals(planningBefore, planningCalls.get());
    assertEquals(1, execution.calls.get());
    assertFalse(execution.compilerInvoked.get());
    assertTrue(
        signals.stream().noneMatch(PipelineSignal.Completed.class::isInstance),
        signals.toString());
    assertEquals(1, persistedHints().size());
    assertEquals(BINDING_INTERACTION_ID, persistedHints().getFirst().interactionId());
  }

  @Test
  void runCeilingStopsIdentityMismatchWithoutUpstreamReplay() {
    support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(
                        discoveryCapability(),
                        designInputCapability(),
                        planningCapability(),
                        execution)),
                clock)
            .recoveryLedger(new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 2, 0)))
            .build();
    runtime = new CreateChainTestOrchestrator(support, runStore);
    waitAtFirstPlanApproval();
    int designCallsBefore = designInputCalls.get();

    List<PipelineSignal> signals = approveCurrentPlan();

    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-execution", run().run().currentStageId());
    String prompt = latestWaitingPrompt();
    assertEquals(PipelineGates.RECOVERY_REPEATED, PipelineGates.gateOf(prompt).orElse(""));
    assertEquals(
        HaltRecoveryGuard.MAX_CAUSAL_REOPENS.name(), PipelineGates.guardOf(prompt).orElse(""));
    assertTrue(
        PipelineGates.strip(prompt).contains(HaltRecoveryGuard.MAX_CAUSAL_REOPENS.cardSentence()),
        prompt);
    assertEquals(designCallsBefore, designInputCalls.get());
    assertEquals(1, execution.calls.get());
    assertFalse(execution.compilerInvoked.get());
    assertTrue(
        signals.stream().noneMatch(PipelineSignal.Completed.class::isInstance),
        signals.toString());
  }

  private void waitAtFirstPlanApproval() {
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    if (run().run().status() == RunStatus.WAITING_FOR_INPUT) {
      runtime
          .acceptInput(new AcceptInputCommand(RUN_ID, "create a WFMS work order chain"))
          .collect()
          .asList()
          .await()
          .indefinitely();
    }
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    assertEquals("planning", run().run().currentStageId());
    assertEquals(1, designInputCalls.get());
    assertEquals(1, planningCalls.get());
    assertEquals(0, execution.calls.get());
  }

  private List<PipelineSignal> approveCurrentPlan() {
    CompilationArtifacts.Reference candidate = snapshot("planning").approvableReference();
    return runtime
        .approve(new ApproveCommand(RUN_ID, candidate, run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void stubCatalog() {
    CatalogRestClient.SystemDto system =
        new CatalogRestClient.SystemDto("sys-wfms", SERVICE_NAME, "EXTERNAL", "http");
    CatalogRestClient.SpecificationDto spec =
        new CatalogRestClient.SpecificationDto("spec-wfms", "WFMS v1", "sg-wfms", "sys-wfms");
    CatalogRestClient.OperationDto op =
        new CatalogRestClient.OperationDto(
            "op-create-wo", "Create Work Order", "POST", "/work-orders", "spec-wfms");
    when(catalog.searchCatalogSystems("sys-wfms")).thenReturn(List.of(system));
    when(catalog.getApiSpecifications("sys-wfms")).thenReturn(List.of(spec));
    when(catalog.listCatalogOperations(eq(CONV_ID), eq("spec-wfms"), eq("sys-wfms"), isNull()))
        .thenReturn(List.of(op));
    when(catalog.listCatalogOperations("spec-wfms", "sys-wfms", null)).thenReturn(List.of(op));
  }

  private StageCapability discoveryCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "requirement-discovery";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(
                            new ArtifactCandidate(
                                Kind.CATALOG_BINDING_HINT, bindingHint(), List.of())),
                        "hint ready",
                        null)));
      }
    };
  }

  private StageCapability designInputCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "design-input";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        int call = designInputCalls.incrementAndGet();
        String interactionId = call == 1 ? SEMANTIC_MISMATCH_ID : BINDING_INTERACTION_ID;
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(
                            new ArtifactCandidate(
                                Kind.CHAIN_SEMANTIC_REVISION,
                                revisionFor(interactionId),
                                List.of())),
                        "revision ready",
                        null)));
      }
    };
  }

  private StageCapability planningCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "planning";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        int call = planningCalls.incrementAndGet();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.IMPLEMENTATION_PLAN,
                                new ImplementationPlan("plan-" + call),
                                List.of())),
                        "plan ready",
                        null)));
      }
    };
  }

  private List<CatalogBindingHint> persistedHints() {
    return artifactStore.history(RUN_ID, Kind.CATALOG_BINDING_HINT).stream()
        .map(revision -> artifactStore.payload(revision, CatalogBindingHint.class))
        .toList();
  }

  private ChainSemanticRevision latestRevision() {
    return artifactStore
        .latest(RUN_ID, Kind.CHAIN_SEMANTIC_REVISION)
        .map(revision -> artifactStore.payload(revision, ChainSemanticRevision.class))
        .orElseThrow();
  }

  private static String serviceCallId(ChainSemanticRevision revision) {
    return revision.nodes().stream()
        .filter(SemanticNode.ServiceCall.class::isInstance)
        .map(SemanticNode.ServiceCall.class::cast)
        .map(SemanticNode.ServiceCall::serviceCallId)
        .findFirst()
        .orElseThrow();
  }

  private static CatalogBindingHint bindingHint() {
    return new CatalogBindingHint(
        "3",
        BINDING_INTERACTION_ID,
        BINDING_INTERACTION_ID,
        "Create Work Order",
        "sys-wfms",
        "sg-wfms",
        "spec-wfms",
        "op-create-wo",
        "http",
        "POST",
        "/work-orders",
        "catalog",
        FIXED,
        "evidence-wfms");
  }

  private static ChainSemanticRevision revisionFor(String interactionId) {
    return SemanticFixtures.linear(
        "WFMS",
        "revision-" + interactionId,
        "trigger-http",
        "node-call",
        interactionId,
        "Create Work Order",
        SERVICE_NAME,
        List.of(),
        List.of());
  }

  private String latestWaitingPrompt() {
    return run().transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElseThrow();
  }

  private StageSnapshot snapshot(String stageId) {
    return run().run().stages().stream()
        .filter(stage -> stageId.equals(stage.stageId()))
        .findFirst()
        .orElseThrow();
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private ArtifactProvenance provenance() {
    return new ArtifactProvenance(
        RUN_ID,
        "design-execution",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "design-execution",
        "1",
        "closure-sha");
  }

  private static ProductPipelineProfile fourStageProfile() {
    ArtifactTypeRef userInput = new ArtifactTypeRef("user-input", 1);
    ArtifactTypeRef hint = new ArtifactTypeRef("catalog-binding-hint", 1);
    ArtifactTypeRef semantic = new ArtifactTypeRef("chain-semantic-revision", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("implementation-plan", 1);
    return new ProductPipelineProfile(
        1,
        "test-identity-mismatch",
        "1",
        List.of(userInput),
        List.of(
            new ProfileStage(
                "requirement-discovery",
                "requirement-discovery",
                List.of(userInput),
                List.of(),
                List.of(hint),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L),
                null),
            new ProfileStage(
                "design-input",
                "design-input",
                List.of(),
                List.of(),
                List.of(semantic),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L),
                null),
            new ProfileStage(
                "planning",
                "planning",
                List.of(semantic),
                List.of(),
                List.of(plan),
                List.of(),
                new ApprovalPolicy(plan),
                null,
                new RetryPolicy(0, 1L),
                null),
            new ProfileStage(
                "design-execution",
                "design-execution",
                List.of(semantic, plan),
                List.of(hint),
                List.of(),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L),
                null)),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("requirement-discovery", "design-input", "planning", "design-execution"));
  }

  private RunManifest manifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(
            new DependencyClosureEntry("requirement-discovery", "1", "c1"),
            new DependencyClosureEntry("design-input", "1", "c2"),
            new DependencyClosureEntry("planning", "1", "c3"),
            new DependencyClosureEntry("design-execution", "1", "c4")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private static ApprovalRecordV2 catalogFirstApproval() {
    return new ApprovalRecordV2(
        new CompilationArtifacts.Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan"),
        "hash-plan",
        List.of(),
        "tester",
        "approved",
        FIXED,
        ApprovalPolicy.CATALOG_FIRST_V1,
        ApprovalPolicy.CATALOG_FIRST_V1_HASH,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static final class BindingAwareExecution implements StageCapability {

    private final ProductPipelineArtifactStore artifactStore;
    private final DefaultExecutorCatalogBindingAdapter adapter;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean compilerInvoked = new AtomicBoolean();
    private ResolvedServiceCallBinding lastBinding;

    private BindingAwareExecution(
        ProductPipelineArtifactStore artifactStore,
        DefaultExecutorCatalogBindingAdapter adapter) {
      this.artifactStore = artifactStore;
      this.adapter = adapter;
    }

    @Override
    public String capabilityId() {
      return "design-execution";
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      calls.incrementAndGet();
      List<CatalogBindingHint> hints = new ArrayList<>();
      for (CompilationArtifacts.Revision revision :
          artifactStore.history(context.runId(), Kind.CATALOG_BINDING_HINT)) {
        hints.add(artifactStore.payload(revision, CatalogBindingHint.class));
      }
      ChainSemanticRevision revision =
          artifactStore
              .latest(context.runId(), Kind.CHAIN_SEMANTIC_REVISION)
              .map(stored -> artifactStore.payload(stored, ChainSemanticRevision.class))
              .orElseThrow();
      List<BindingResolutionResult> results =
          adapter.resolve(context.conversationId(), revision, hints, catalogFirstApproval());
      for (BindingResolutionResult result : results) {
        if (result instanceof BindingResolutionResult.Failed failed && failed.isMissingHint()) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.DOMAIN_FAILURE,
                          failed.reason(),
                          RecoveryCause.missingCatalogBinding(failed.serviceCallId()))));
        }
        if (result instanceof BindingResolutionResult.Failed failed
            && failed.isIdentityMismatch()) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.DOMAIN_FAILURE,
                          failed.reason(),
                          RecoveryCause.bindingIdentityMismatch(failed.serviceCallId()))));
        }
        if (result instanceof BindingResolutionResult.Failed failed) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          failed.outcomeClass(),
                          failed.reason(),
                          RecoveryCause.catalogResolution(failed.requestedFact()))));
        }
        if (result instanceof BindingResolutionResult.Resolved resolved) {
          lastBinding = resolved.binding();
          compilerInvoked.set(true);
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.SUCCEEDED, "compiled with resolved binding")));
        }
      }
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(StageOutcomeClass.DOMAIN_FAILURE, "no binding result")));
    }
  }
}
