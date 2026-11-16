package org.qubership.integration.platform.ai.productpipeline.materialization;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainMaterializedSummary;
import org.qubership.integration.platform.ai.chain.reconcile.ChainReconcileService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ExecutorValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.MaterializationRequest;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.OrderedGraphPatches;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ValidatedExecutionBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;
import org.qubership.integration.platform.ai.productpipeline.recovery.SupersededBriefLineageGuard;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.validation.ApprovedHttpEndpointValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult;

/**
 * Materializes an approved CREATE graph, reads catalog state back, and reconciles before terminal
 * success. For create-chain@2 this is executor Phase 6.
 */
@ApplicationScoped
public class MaterializationCapability implements StageCapability {

  public static final String CAPABILITY_ID = "materialization";

  private final ProductPipelineArtifactStore artifactStore;
  private final ProductChainMaterializer materializer;
  private final ChainCatalogFactsService factsService;
  private final ChainReconcileService reconcileService;
  private final CanonicalGraphDigest canonicalGraphDigest;
  private final CipDesignExecutorJavaAdapter designExecutor;
  private final E2eRecoveryFaultInjector recoveryFaultInjector;

  @Inject org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl recoveryControl;
  @Inject org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator copyMappings;
  @Inject org.qubership.integration.platform.ai.plan.BriefCopyMappingValidator copySchemas;

  @Inject
  public MaterializationCapability(
      ProductPipelineArtifactStore artifactStore,
      ProductChainMaterializer materializer,
      ChainCatalogFactsService factsService,
      ChainReconcileService reconcileService,
      CanonicalGraphDigest canonicalGraphDigest,
      CipDesignExecutorJavaAdapter designExecutor,
      E2eRecoveryFaultInjector recoveryFaultInjector) {
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.materializer = Objects.requireNonNull(materializer, "materializer");
    this.factsService = Objects.requireNonNull(factsService, "factsService");
    this.reconcileService = Objects.requireNonNull(reconcileService, "reconcileService");
    this.canonicalGraphDigest =
        Objects.requireNonNull(canonicalGraphDigest, "canonicalGraphDigest");
    this.designExecutor = Objects.requireNonNull(designExecutor, "designExecutor");
    this.recoveryFaultInjector =
        Objects.requireNonNull(recoveryFaultInjector, "recoveryFaultInjector");
  }

  /** Test helper for input validation without a Phase 6 adapter. */
  public MaterializationCapability(
      ProductPipelineArtifactStore artifactStore,
      ProductChainMaterializer materializer,
      ChainCatalogFactsService factsService,
      ChainReconcileService reconcileService) {
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.materializer = Objects.requireNonNull(materializer, "materializer");
    this.factsService = Objects.requireNonNull(factsService, "factsService");
    this.reconcileService = Objects.requireNonNull(reconcileService, "reconcileService");
    this.canonicalGraphDigest =
        new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper());
    this.designExecutor = null;
    this.recoveryFaultInjector = new E2eRecoveryFaultInjector("", "");
  }

  /** Test helper that supplies a Phase 6 executor adapter. */
  public MaterializationCapability(
      ProductPipelineArtifactStore artifactStore,
      ProductChainMaterializer materializer,
      ChainCatalogFactsService factsService,
      ChainReconcileService reconcileService,
      CanonicalGraphDigest canonicalGraphDigest,
      CipDesignExecutorJavaAdapter designExecutor) {
    this(
        artifactStore,
        materializer,
        factsService,
        reconcileService,
        canonicalGraphDigest,
        designExecutor,
        new E2eRecoveryFaultInjector("", ""));
  }

  /** Test helper that supplies a Phase 6 executor adapter. */
  public MaterializationCapability(
      ProductPipelineArtifactStore artifactStore,
      ProductChainMaterializer materializer,
      ChainCatalogFactsService factsService,
      ChainReconcileService reconcileService,
      CipDesignExecutorJavaAdapter designExecutor) {
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.materializer = Objects.requireNonNull(materializer, "materializer");
    this.factsService = Objects.requireNonNull(factsService, "factsService");
    this.reconcileService = Objects.requireNonNull(reconcileService, "reconcileService");
    this.canonicalGraphDigest =
        new CanonicalGraphDigest(new com.fasterxml.jackson.databind.ObjectMapper());
    this.designExecutor = Objects.requireNonNull(designExecutor, "designExecutor");
    this.recoveryFaultInjector = new E2eRecoveryFaultInjector("", "");
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    ResolvedInputs resolved = resolveInputs(context);
    if (resolved.error() != null) {
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, resolved.error())));
    }
    MaterializationCheckpoint checkpoint = artifactStore.materializationCheckpoint(context.runId()).orElse(null);
    boolean readBack = checkpoint != null && checkpoint.canReadBack()
        && resolved.graph().nodes().stream().allMatch(node ->
            checkpoint.materializationMap().nodeIdToElementId().containsKey(node.nodeId()));
    if (!readBack && Boolean.TRUE.equals(context.attributes().get(ProductPipelineRunSupport.CATALOG_RECHECK_ATTR))) {
      return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "The Catalog checkpoint does not identify every element in the approved graph.",
          RecoveryCause.of(RecoveryCauseCode.CONTRACT_SHAPE))));
    }
    if (!readBack) {
      try {
        var approvedPlan = artifactStore.get(context.runId(), resolved.implementationPlanRef()).orElseThrow();
        var briefRef = SupersededBriefLineageGuard
            .originatingBriefRevision(artifactStore, context.runId(), approvedPlan)
            .orElseThrow(() -> new IllegalStateException("Approved plan has no requirement brief lineage."));
        var semanticRef = SupersededBriefLineageGuard
            .originatingRevision(artifactStore, context.runId(), approvedPlan, Kind.CHAIN_SEMANTIC_REVISION)
            .orElseThrow(() -> new IllegalStateException("Approved plan has no semantic revision lineage."));
        var brief = artifactStore.payload(briefRef, RequirementBrief.class);
        var semantic = artifactStore.payload(semanticRef, ChainSemanticRevision.class);
        if (copyMappings == null) {
          throw new IllegalStateException("Script execution validation is required before materialization.");
        }
        copyMappings.validate(context.conversationId(), brief, semantic, resolved.graph());
        var ordinary = org.qubership.integration.platform.ai.plan.mapping.CopyMappingMaterializationValidator
            .ordinaryMappings(brief, resolved.graph());
        if (!ordinary.isEmpty()) {
          if (copySchemas == null) {
            throw new IllegalStateException("Approved mapping schemas must be revalidated before materialization.");
          }
          var schemaIssues = copySchemas.validateApproved(brief.withMappingIntents(ordinary));
          if (!schemaIssues.isEmpty()) {
            throw new IllegalArgumentException(schemaIssues.getFirst().message());
          }
        }
        var issues = ApprovedHttpEndpointValidator.validate(resolved.graph(), brief, semantic);
        if (!issues.isEmpty()) {
          var validation = PlanValidationResult
              .fromCompiler(new ValidationResult(
                  false, issues, "Generated HTTP endpoint differs from approved requirements."), List.of());
          return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
              StageOutcomeClass.CONTRACT_FAILURE, validation.findings().stream()
                  .map(PlanValidationFinding::message)
                  .collect(Collectors.joining("\n")),
              new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, validation.findings(), ""))));
        }
      } catch (IllegalArgumentException | IllegalStateException missingSource) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(
            StageOutcomeClass.CONTRACT_FAILURE, missingSource.getMessage(), RecoveryCause.of(RecoveryCauseCode.CONTRACT_SHAPE))));
      }
    }
    String chainName =
        resolved.graph().chain() == null ? null : resolved.graph().chain().name();
    Optional<RecoveryCauseCode> injected =
        recoveryFaultInjector.next(context.runId(), chainName, CAPABILITY_ID);
    if (injected.isPresent()) {
      RecoveryCauseCode causeCode = injected.orElseThrow();
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(
                      StageOutcomeClass.CONTRACT_FAILURE,
                      "E2E recovery fault: injected " + causeCode + " at materialization.",
                      RecoveryCause.of(causeCode))));
    }

    if (recoveryControl != null
        && recoveryControl.materializationInputsAccepted(context, resolved.graphRef(), materializationInputs(resolved))) {
      return Multi.createFrom().emitter(ignored -> {});
    }

    // Capture the SSE emit consumer on this thread (ToolInvocationSink bound by chat turn), then
    // re-bind on the worker so CatalogOutboundLoggingFilter can emit kind=tool steps.
    var turnEmit = SkillActivitySupport.captureTurnEmit(context.conversationId());
    // Catalog RestClient + Uni.await inside ProductChainMaterializer must not run on Vert.x event loop.
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(CAPABILITY_ID)),
            Uni.createFrom()
                .item(
                    () -> {
                      try (var session = org.qubership.integration.platform.ai.chat.ToolSession.open(context.conversationId())) {
                        SkillActivitySupport.bindWorker(CAPABILITY_ID, turnEmit, context.conversationId());
                        try {
                          return SkillActivitySupport.wrapTerminal(
                              CAPABILITY_ID, completeMaterialization(context, resolved, checkpoint));
                        } finally {
                          org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink.flushCatalogChanges();
                          SkillActivitySupport.unbindWorker(turnEmit);
                        }
                      }
                    })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem()
                .transformToMulti(signals -> Multi.createFrom().iterable(signals)));
  }

  private List<CapabilitySignal> completeMaterialization(
      StageExecutionContext context, ResolvedInputs resolved, MaterializationCheckpoint checkpoint) {
    if (resolved.error() != null) {
      return List.of(
          new CapabilitySignal.Completed(
              StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, resolved.error())));
    }
    ProductChainMaterializer.Inputs inputs =
        new ProductChainMaterializer.Inputs(
            context.runId(),
            resolved.graph(),
            resolved.runManifest(),
            resolved.graphAssemblyResult().graphDigest());
    MaterializationResult core = materializer.resume(inputs, checkpoint);
    ChainCatalogFacts facts = factsService.load(core.chainId());
    MaterializationResult reconciledCore = materializer.markReconciled(inputs, core);
    ReconcileResult reconcile =
        reconcileService.compare(
            resolved.graph(), reconciledCore.materializationMap(), facts);

    List<Reference> evidenceInputs = materializationInputs(resolved);
    return completePhase6Materialization(
        context, resolved, inputs, reconciledCore, facts, reconcile, evidenceInputs);
  }

  private List<CapabilitySignal> completePhase6Materialization(
      StageExecutionContext context,
      ResolvedInputs resolved,
      ProductChainMaterializer.Inputs inputs,
      MaterializationResult reconciledCore,
      ChainCatalogFacts facts,
      ReconcileResult reconcile,
      List<Reference> evidenceInputs) {
    Reference snapshotRef =
        appendArtifact(context, Kind.CATALOG_CHAIN_SNAPSHOT, "1", facts, evidenceInputs);
    Reference reconcileRef =
        appendArtifact(context, Kind.RECONCILE_RESULT, "1", reconcile, evidenceInputs);
    ArtifactCandidate snapshotCandidate =
        new ArtifactCandidate(Kind.CATALOG_CHAIN_SNAPSHOT, facts, evidenceInputs);
    ArtifactCandidate reconcileCandidate =
        new ArtifactCandidate(Kind.RECONCILE_RESULT, reconcile, evidenceInputs);

    if (!reconcile.matches()) {
      return List.of(
          new CapabilitySignal.Completed(
              new StageOutcome(
                  StageOutcomeClass.VALIDATION_FAILURE,
                  List.of(snapshotCandidate, reconcileCandidate),
                  reconcile.summary(),
                  null)));
    }

    MaterializationResult complete = materializer.markComplete(inputs, reconciledCore);
    if (complete.completedPhase() != MaterializationPhase.COMPLETE) {
      return List.of(
          new CapabilitySignal.Completed(
              StageOutcome.of(
                  StageOutcomeClass.CONTRACT_FAILURE,
                  "materialization result must reach COMPLETE")));
    }
    if (designExecutor == null) {
      return List.of(
          new CapabilitySignal.Completed(
              StageOutcome.of(
                  StageOutcomeClass.CONTRACT_FAILURE,
                  "design executor adapter is required for create-chain@2 Phase 6")));
    }

    MaterializationResult enriched = complete.withCatalogEvidence(snapshotRef, reconcileRef);
    ExecutionResult phase6 =
        designExecutor.completePhase6(
            context.runId(),
            resolved.materializationRequest(),
            enriched,
            snapshotRef,
            reconcileRef);
    if (phase6.outcomeClass() != StageOutcomeClass.SUCCEEDED) {
      return List.of(
          new CapabilitySignal.Completed(
              new StageOutcome(
                  phase6.outcomeClass() == null
                      ? StageOutcomeClass.VALIDATION_FAILURE
                      : phase6.outcomeClass(),
                  List.of(snapshotCandidate, reconcileCandidate),
                  phase6.message(),
                  null)));
    }

    List<ArtifactCandidate> candidates = new ArrayList<>();
    candidates.add(
        new ArtifactCandidate(
            Kind.MATERIALIZATION_MAP, complete.materializationMap(), evidenceInputs));
    candidates.add(snapshotCandidate);
    candidates.add(reconcileCandidate);
    candidates.addAll(phase6.candidates());
    return List.of(
        new CapabilitySignal.Message(ChainMaterializedSummary.format(facts)),
        new CapabilitySignal.Completed(
            new StageOutcome(
                StageOutcomeClass.SUCCEEDED,
                List.copyOf(candidates),
                "chain materialized and reconciled",
                null)));
  }

  private ResolvedInputs resolveInputs(StageExecutionContext context) {
    Optional<Reference> requestRef = findSingle(context.inputRefs(), Kind.MATERIALIZATION_REQUEST);
    Optional<Reference> validatedBundleRef =
        findSingle(context.inputRefs(), Kind.VALIDATED_EXECUTION_BUNDLE);
    Optional<Reference> manifestRef = findSingle(context.inputRefs(), Kind.RUN_MANIFEST);
    if (requestRef.isEmpty() || validatedBundleRef.isEmpty() || manifestRef.isEmpty()) {
      return ResolvedInputs.error("materialization inputs are incomplete");
    }

    Optional<Revision> requestRevision = artifactStore.get(context.runId(), requestRef.get());
    Optional<Revision> validatedRevision =
        artifactStore.get(context.runId(), validatedBundleRef.get());
    Optional<Revision> manifestRevision = artifactStore.get(context.runId(), manifestRef.get());
    if (requestRevision.isEmpty() || validatedRevision.isEmpty() || manifestRevision.isEmpty()) {
      return ResolvedInputs.error("materialization inputs must reference existing revisions");
    }

    MaterializationRequest request =
        artifactStore.payload(requestRevision.get(), MaterializationRequest.class);
    ValidatedExecutionBundle bundle =
        artifactStore.payload(validatedRevision.get(), ValidatedExecutionBundle.class);
    RunManifest runManifest = artifactStore.payload(manifestRevision.get(), RunManifest.class);
    if (request == null || bundle == null || runManifest == null) {
      return ResolvedInputs.error("materialization payloads are missing");
    }
    // Design-execution may store-back a VALIDATED_EXECUTION_BUNDLE for nested refs, then emit the
    // same payload as a produced candidate. The create-chain runtime re-appends produced candidates
    // with a new artifactId; contentHash stays identical. Match by kind+contentHash so Phase 6
    // accepts that produce-path pair (full Reference.equals would fail on artifactId alone).
    if (!sameValidatedBundleContent(validatedBundleRef.get(), request.validatedExecutionBundleRef())) {
      return ResolvedInputs.error("materialization request does not reference the validated bundle");
    }
    if (!request.approvalRef().equals(bundle.approvalRef())
        || !request.designPlanReportRef().equals(bundle.designPlanReportRef())
        || !request.designExecutionPlanRef().equals(bundle.designExecutionPlanRef())
        || !request.graphDigest().equals(bundle.graphDigest())
        || !request.orderedPatchDigest().equals(bundle.orderedPatchDigest())) {
      return ResolvedInputs.error(
          "materialization request does not match validated execution bundle");
    }
    if (!Objects.equals(
            request.designPlanReportRef().contentHash(), bundle.designPlanReportHash())
        || !Objects.equals(
            request.designExecutionPlanRef().contentHash(), bundle.designExecutionPlanHash())) {
      return ResolvedInputs.error("stale design plan report or projection hash");
    }

    Optional<Revision> approvalRevision =
        artifactStore.get(context.runId(), bundle.approvalRef());
    if (approvalRevision.isEmpty() || !"2".equals(approvalRevision.get().schemaVersion())) {
      return ResolvedInputs.error("schema-v2 approval record is required");
    }
    ApprovalRecordV2 approval =
        artifactStore.payload(approvalRevision.get(), ApprovalRecordV2.class);
    if (approval == null) {
      return ResolvedInputs.error("approval payload is missing");
    }
    if (approval.target() == null || approval.target().kind() != Kind.IMPLEMENTATION_PLAN) {
      return ResolvedInputs.error("approval target does not match implementation plan");
    }
    Optional<Revision> implementationRevision =
        artifactStore.get(context.runId(), approval.target());
    if (implementationRevision.isEmpty()) {
      return ResolvedInputs.error("implementation plan revision is missing");
    }
    if (!Objects.equals(approval.target().contentHash(), approval.targetContentHash())) {
      return ResolvedInputs.error("approval target does not match implementation plan");
    }
    if (!approval.approvedCandidates().contains(bundle.designPlanReportRef())
        || !approval.approvedCandidates().contains(bundle.designExecutionPlanRef())
        || !approval.approvedCandidates().contains(approval.target())) {
      return ResolvedInputs.error("required approved candidate set is incomplete");
    }

    Optional<Revision> graphRevision = artifactStore.get(context.runId(), bundle.graphRef());
    Optional<Revision> orderedPatchesRevision =
        artifactStore.get(context.runId(), bundle.orderedGraphPatchesRef());
    Optional<Revision> graphValidationRevision =
        artifactStore.get(context.runId(), bundle.graphValidationRef());
    Optional<Revision> planValidationRevision =
        artifactStore.get(context.runId(), bundle.planValidationRef());
    Optional<Revision> compilerValidationRevision =
        artifactStore.get(context.runId(), bundle.compilerValidationRef());
    Optional<Revision> executorValidationRevision =
        artifactStore.get(context.runId(), bundle.executorValidationRef());
    if (graphRevision.isEmpty()
        || orderedPatchesRevision.isEmpty()
        || graphValidationRevision.isEmpty()
        || planValidationRevision.isEmpty()
        || compilerValidationRevision.isEmpty()
        || executorValidationRevision.isEmpty()) {
      return ResolvedInputs.error("validated execution bundle evidence is incomplete");
    }

    ChainPlanGraph graph = artifactStore.payload(graphRevision.get(), ChainPlanGraph.class);
    OrderedGraphPatches orderedPatches =
        artifactStore.payload(orderedPatchesRevision.get(), OrderedGraphPatches.class);
    PlanValidationResult graphValidation =
        artifactStore.payload(graphValidationRevision.get(), PlanValidationResult.class);
    PlanValidationResult planValidation =
        artifactStore.payload(planValidationRevision.get(), PlanValidationResult.class);
    CompilerValidationBundle compilerBundle =
        artifactStore.payload(compilerValidationRevision.get(), CompilerValidationBundle.class);
    ExecutorValidationBundle executorValidation =
        artifactStore.payload(executorValidationRevision.get(), ExecutorValidationBundle.class);
    if (graph == null
        || orderedPatches == null
        || graphValidation == null
        || planValidation == null
        || compilerBundle == null
        || executorValidation == null) {
      return ResolvedInputs.error("validated execution bundle payloads are missing");
    }

    String liveGraphDigest = canonicalGraphDigest.sha256(graph);
    if (!Objects.equals(liveGraphDigest, bundle.graphDigest())
        || !Objects.equals(liveGraphDigest, request.graphDigest())
        || !Objects.equals(liveGraphDigest, compilerBundle.graphDigest())
        || !Objects.equals(liveGraphDigest, executorValidation.graphDigest())) {
      return ResolvedInputs.error("graph digest mismatch across validated execution evidence");
    }
    String liveOrderedPatchDigest = sha256(orderedPatches.patchRefs().toString());
    if (!Objects.equals(liveOrderedPatchDigest, bundle.orderedPatchDigest())
        || !Objects.equals(liveOrderedPatchDigest, request.orderedPatchDigest())) {
      return ResolvedInputs.error("ordered patch digest mismatch");
    }
    if (!graphValidation.approvalEligible() || !planValidation.approvalEligible()) {
      return ResolvedInputs.error("plan validation result is not approval eligible");
    }
    if (!compilerBundle.approvalEligible()) {
      return ResolvedInputs.error("compiler validation bundle did not pass");
    }
    if (!executorValidation.passed()) {
      return ResolvedInputs.error("executor validation bundle did not pass");
    }

    GraphAssemblyResult graphAssembly =
        artifactStore
            .latest(context.runId(), Kind.GRAPH_ASSEMBLY_RESULT)
            .map(revision -> artifactStore.payload(revision, GraphAssemblyResult.class))
            .filter(Objects::nonNull)
            .orElseGet(
                () ->
                    new GraphAssemblyResult(
                        1,
                        graph,
                        bundle.graphDigest(),
                        orderedPatches.patchRefs(),
                        List.of(),
                        List.of()));
    String patchRefError = validateOrderedPatchReferences(context.runId(), graphAssembly);
    if (patchRefError != null) {
      return ResolvedInputs.error(patchRefError);
    }

    return new ResolvedInputs(
        request,
        bundle,
        bundle.graphRef(),
        null,
        bundle.compilerValidationRef(),
        bundle.planValidationRef(),
        approval.target(),
        bundle.approvalRef(),
        manifestRef.get(),
        graph,
        graphAssembly,
        compilerBundle,
        runManifest,
        null);
  }

  private Reference appendArtifact(
      StageExecutionContext context,
      Kind kind,
      String schemaVersion,
      Object payload,
      List<Reference> evidenceInputs) {
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                context.runId(),
                kind,
                schemaVersion,
                CAPABILITY_ID,
                "1",
                payload,
                evidenceInputs,
                null,
                provenance(context)));
    return revision.reference();
  }

  private static ArtifactProvenance provenance(StageExecutionContext context) {
    RunManifest manifest = context.runManifest();
    return new ArtifactProvenance(
        context.runId(),
        CAPABILITY_ID,
        manifest == null || manifest.profileId() == null ? "create-chain" : manifest.profileId(),
        manifest == null || manifest.profileVersion() == null ? "1" : manifest.profileVersion(),
        manifest == null || manifest.profileDigest() == null ? "unknown" : manifest.profileDigest(),
        CAPABILITY_ID,
        "1",
        manifest == null || manifest.dependencyClosureDigest() == null
            ? "unknown"
            : manifest.dependencyClosureDigest());
  }

  private String validateOrderedPatchReferences(String runId, GraphAssemblyResult graphAssembly) {
    if (graphAssembly.orderedPatchReferences() == null
        || graphAssembly.orderedPatchReferences().isEmpty()) {
      return null;
    }
    for (Reference ref : graphAssembly.orderedPatchReferences()) {
      if (ref == null || ref.kind() != Kind.GRAPH_PATCH_ARTIFACT) {
        return "orderedPatchReferences must contain GRAPH_PATCH_ARTIFACT references";
      }
      Optional<Revision> revision = artifactStore.get(runId, ref);
      if (revision.isEmpty()) {
        return "orderedPatchReferences entry does not resolve in the materialization run";
      }
      if (!Objects.equals(revision.get().compilationId(), runId)) {
        return "orderedPatchReferences revision runId mismatch";
      }
    }
    return null;
  }

  private static Optional<Reference> findSingle(List<Reference> refs, Kind kind) {
    List<Reference> matches =
        refs == null ? List.of() : refs.stream().filter(ref -> ref != null && ref.kind() == kind).toList();
    if (matches.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(matches.get(0));
  }

  private static boolean sameValidatedBundleContent(Reference inputRef, Reference requestRef) {
    return inputRef != null
        && requestRef != null
        && inputRef.kind() == Kind.VALIDATED_EXECUTION_BUNDLE
        && requestRef.kind() == Kind.VALIDATED_EXECUTION_BUNDLE
        && Objects.equals(inputRef.contentHash(), requestRef.contentHash());
  }

  private static List<Reference> materializationInputs(ResolvedInputs resolved) {
    return List.of(
        resolved.materializationRequest().validatedExecutionBundleRef(),
        resolved.approvalRef(),
        resolved.runManifestRef(),
        resolved.graphRef());
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private record ResolvedInputs(
      MaterializationRequest materializationRequest,
      ValidatedExecutionBundle validatedExecutionBundle,
      Reference graphRef,
      Reference graphAssemblyRef,
      Reference compilerBundleRef,
      Reference planValidationRef,
      Reference implementationPlanRef,
      Reference approvalRef,
      Reference runManifestRef,
      ChainPlanGraph graph,
      GraphAssemblyResult graphAssemblyResult,
      CompilerValidationBundle compilerValidationBundle,
      RunManifest runManifest,
      String error) {

    private static ResolvedInputs error(String message) {
      return new ResolvedInputs(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          message);
    }
  }
}
