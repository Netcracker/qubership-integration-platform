package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

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
import java.util.Set;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.mapping.MappingContractBlockedException;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerPlanningRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ApiOperationBindings;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionTrace;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ExecutorValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.MaterializationRequest;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.OrderedGraphPatches;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ValidatedExecutionBundle;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationPhase;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationResult;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

/**
 * Java adapter for {@code cip-design-executor}. Owns Phase 5 through
 * {@link DesignExecutionPhase#WAITING_FOR_MATERIALIZATION} and Phase 6 completion after catalog
 * materialization.
 */
@ApplicationScoped
public class CipDesignExecutorJavaAdapter {

  public static final String SKILL_ID = "cip-design-executor";
  public static final String SCHEMA_VERSION = "1";

  private final ApprovedCompilerExecutionRunner runner;
  private final ExecutorCatalogBindingAdapter bindingAdapter;
  private final ProductPipelineArtifactStore artifactStore;
  private final CompilerPlanValidator planValidator;

  @Inject
  public CipDesignExecutorJavaAdapter(
      ApprovedCompilerExecutionRunner runner,
      ExecutorCatalogBindingAdapter bindingAdapter,
      ProductPipelineArtifactStore artifactStore,
      CompilerPlanValidator planValidator) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.bindingAdapter = Objects.requireNonNull(bindingAdapter, "bindingAdapter");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.planValidator = Objects.requireNonNull(planValidator, "planValidator");
  }

  /**
   * Completes Phase 6 after successful materialization and catalog reconciliation.
   *
   * <p>Appends {@code MATERIALIZATION_RESULT} and {@code DESIGN_EXECUTION_RESULT}, then advances the
   * executor checkpoint from {@link DesignExecutionPhase#WAITING_FOR_MATERIALIZATION} to {@link
   * DesignExecutionPhase#COMPLETE}.
   */
  public ExecutionResult completePhase6(
      String runId,
      MaterializationRequest request,
      MaterializationResult materializationResult,
      Reference catalogSnapshotRef,
      Reference reconcileRef) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(materializationResult, "materializationResult");
    Objects.requireNonNull(catalogSnapshotRef, "catalogSnapshotRef");
    Objects.requireNonNull(reconcileRef, "reconcileRef");

    Optional<Revision> checkpointRevision =
        artifactStore.latest(runId, Kind.DESIGN_EXECUTION_CHECKPOINT);
    if (checkpointRevision.isEmpty()) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE,
          "design execution checkpoint is required for Phase 6");
    }
    DesignExecutionCheckpoint prior =
        artifactStore.payload(checkpointRevision.get(), DesignExecutionCheckpoint.class);
    if (prior == null || prior.phase() != DesignExecutionPhase.WAITING_FOR_MATERIALIZATION) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE,
          "design execution checkpoint must be WAITING_FOR_MATERIALIZATION");
    }
    if (!Objects.equals(prior.approvalRef(), request.approvalRef())) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE,
          "materialization request approval does not match executor checkpoint");
    }
    if (!Objects.equals(prior.designPlanReportHash(), request.designPlanReportRef().contentHash())
        || !Objects.equals(
            prior.designExecutionPlanHash(), request.designExecutionPlanRef().contentHash())) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE,
          "materialization request plan hashes do not match executor checkpoint");
    }
    if (materializationResult.completedPhase() != MaterializationPhase.COMPLETE) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE,
          "materialization result must reach COMPLETE before Phase 6");
    }

    List<Reference> evidenceInputs =
        List.of(
            request.approvalRef(),
            request.designPlanReportRef(),
            request.designExecutionPlanRef(),
            request.validatedExecutionBundleRef(),
            catalogSnapshotRef,
            reconcileRef);

    Reference materializationResultRef =
        appendPhase6(
            runId, Kind.MATERIALIZATION_RESULT, "1", materializationResult, evidenceInputs);

    DesignExecutionResult designExecutionResult =
        new DesignExecutionResult(
            SCHEMA_VERSION,
            request.approvalRef(),
            request.designPlanReportRef().contentHash(),
            request.designExecutionPlanRef().contentHash(),
            materializationResultRef,
            reconcileRef,
            DesignExecutionPhase.COMPLETE.name());

    Reference designExecutionResultRef =
        appendPhase6(
            runId,
            Kind.DESIGN_EXECUTION_RESULT,
            SCHEMA_VERSION,
            designExecutionResult,
            evidenceInputs);

    DesignExecutionCheckpoint completeCheckpoint =
        new DesignExecutionCheckpoint(
            SCHEMA_VERSION,
            prior.approvalRef(),
            prior.designPlanReportHash(),
            prior.designExecutionPlanHash(),
            prior.runManifestHash(),
            DesignExecutionPhase.COMPLETE,
            appendCompletedStep(
                prior,
                new DesignExecutionCheckpoint.CompletedStep(
                    "phase-6",
                    List.of(
                        request.designPlanReportRef().contentHash(),
                        request.designExecutionPlanRef().contentHash(),
                        materializationResultRef.contentHash(),
                        reconcileRef.contentHash()),
                    List.of(materializationResultRef, designExecutionResultRef, reconcileRef),
                    List.of(
                        materializationResultRef.contentHash(),
                        designExecutionResultRef.contentHash(),
                        reconcileRef.contentHash()),
                    checkpointRevision.get().provenance() == null
                        ? phase6Provenance(runId)
                        : checkpointRevision.get().provenance(),
                    DesignExecutionPhase.COMPLETE.name())));

    appendPhase6(
        runId,
        Kind.DESIGN_EXECUTION_CHECKPOINT,
        SCHEMA_VERSION,
        completeCheckpoint,
        evidenceInputs);

    return new ExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        "design execution complete after materialization",
        completeCheckpoint,
        List.of(
            new ArtifactCandidate(Kind.MATERIALIZATION_RESULT, materializationResult, evidenceInputs),
            new ArtifactCandidate(
                Kind.DESIGN_EXECUTION_RESULT, designExecutionResult, evidenceInputs),
            new ArtifactCandidate(
                Kind.DESIGN_EXECUTION_CHECKPOINT, completeCheckpoint, evidenceInputs)));
  }

  private static List<DesignExecutionCheckpoint.CompletedStep> appendCompletedStep(
      DesignExecutionCheckpoint prior, DesignExecutionCheckpoint.CompletedStep step) {
    List<DesignExecutionCheckpoint.CompletedStep> steps = new ArrayList<>(prior.completedSteps());
    steps.add(step);
    return List.copyOf(steps);
  }

  private Reference appendPhase6(
      String runId,
      Kind kind,
      String schemaVersion,
      Object payload,
      List<Reference> evidenceInputs) {
    ArtifactProvenance provenance =
        artifactStore
            .latest(runId, Kind.DESIGN_EXECUTION_CHECKPOINT)
            .map(Revision::provenance)
            .filter(Objects::nonNull)
            .orElseGet(() -> phase6Provenance(runId));
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                runId,
                kind,
                schemaVersion,
                DesignExecutionCapability.CAPABILITY_ID,
                "1",
                payload,
                evidenceInputs,
                null,
                provenance));
    return revision.reference();
  }

  private static ArtifactProvenance phase6Provenance(String runId) {
    return new ArtifactProvenance(
        runId,
        "design-execution",
        "create-chain",
        "2",
        "profile",
        DesignExecutionCapability.CAPABILITY_ID,
        "1",
        "closure");
  }

  public ExecutionResult executeAfterApproval(ExecutionInputs inputs) {
    return executeAfterApproval(inputs, null, (skillId, status) -> {});
  }

  public ExecutionResult executeAfterApproval(
      ExecutionInputs inputs, BiConsumer<String, String> skillProgress) {
    return executeAfterApproval(inputs, null, skillProgress);
  }

  public ExecutionResult executeAfterApproval(
      ExecutionInputs inputs, String attemptId, BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(inputs, "inputs");
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;
    String preconditionError = verifyPreconditions(inputs);
    if (preconditionError != null) {
      return ExecutionResult.failure(StageOutcomeClass.CONTRACT_FAILURE, preconditionError);
    }

    List<BindingResolutionResult> bindingResults =
        bindingAdapter.resolve(
            inputs.conversationId(),
            inputs.revision(),
            inputs.bindingHints(),
            inputs.approval());
    Optional<ExecutionResult> bindingFailure = toBindingFailure(bindingResults);
    if (bindingFailure.isPresent()) {
      return bindingFailure.get();
    }
    List<ResolvedServiceCallBinding> bindings =
        bindingResults.stream()
            .map(BindingResolutionResult.Resolved.class::cast)
            .map(BindingResolutionResult.Resolved::binding)
            .toList();

    CompilerDagExecutionResult engineResult;
    try {
      engineResult =
          inputs.repairEvidence() != null
              ? runner.execute(
                  inputs.approvedPlan(),
                  inputs.revision(),
                  bindings,
                  inputs.runManifest(),
                  attemptId,
                  inputs.repairEvidence(),
                  inputs.priorGraph(),
                  progress)
              : attemptId == null
                  ? runner.execute(
                      inputs.approvedPlan(),
                      inputs.revision(),
                      bindings,
                      inputs.runManifest(),
                      progress)
                  : runner.execute(
                      inputs.approvedPlan(),
                      inputs.revision(),
                      bindings,
                      inputs.runManifest(),
                      attemptId,
                      progress);
    } catch (MappingContractBlockedException blocked) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE,
          blocked.getMessage(),
          RecoveryCause.missingBriefFacts(List.of(blocked.getMessage())));
    }
    if (engineResult.outcomeClass() != StageOutcomeClass.SUCCEEDED
        && engineResult.outcomeClass() != StageOutcomeClass.CANDIDATE) {
      return ExecutionResult.failure(
          engineResult.outcomeClass() == null
              ? StageOutcomeClass.CONTRACT_FAILURE
              : engineResult.outcomeClass(),
          engineResult.message() == null ? "compiler DAG execution failed" : engineResult.message());
    }

    String outsideClosure =
        rejectOutsideClosure(
            inputs.approvedPlan(),
            inputs.runManifest().compilerRunPin().resolvedDag(),
            engineResult);
    if (outsideClosure != null) {
      return ExecutionResult.failure(StageOutcomeClass.CONTRACT_FAILURE, outsideClosure);
    }

    return emitValidatedBundle(inputs, bindings, engineResult);
  }

  private String verifyPreconditions(ExecutionInputs inputs) {
    if (inputs.approval() == null) {
      return "implementation approval record is required";
    }
    if (!ApprovalPolicy.CATALOG_FIRST_V1.equals(inputs.approval().bindingResolutionPolicy())
        || !ApprovalPolicy.CATALOG_FIRST_V1_HASH.equals(
            inputs.approval().bindingResolutionPolicyHash())) {
      return "implementation approval must declare CATALOG_FIRST_V1";
    }
    if (!inputs.implementationPlanRef().equals(inputs.approval().target())
        || !Objects.equals(
            inputs.implementationPlanRef().contentHash(), inputs.approval().targetContentHash())) {
      return "approval target does not match IMPLEMENTATION_PLAN";
    }
    if (!containsCandidate(inputs.approval(), inputs.reportRef())) {
      return "approved candidate set is missing the design plan report hash";
    }
    if (!containsCandidate(inputs.approval(), inputs.planRef())) {
      return "approved candidate set is missing the design execution plan (projection) hash";
    }
    if (!containsCandidate(inputs.approval(), inputs.revisionRef())) {
      return "approved candidate set is missing the chain semantic revision hash";
    }
    if (!containsCandidate(inputs.approval(), inputs.idsRef())) {
      return "approved candidate set is missing the IDS document hash";
    }
    if (!containsCandidate(inputs.approval(), inputs.implementationPlanRef())) {
      return "approved candidate set is missing the implementation plan hash";
    }

    CompilerRunPin pin = inputs.runManifest() == null ? null : inputs.runManifest().compilerRunPin();
    if (pin == null) {
      return "compiler run pin is required";
    }
    DesignExecutionPlan plan = inputs.approvedPlan();
    if (!Objects.equals(plan.compilerCatalogHash(), pin.pipelineIndexDigest())) {
      return "compiler catalog hash mismatch between projection and run pin";
    }
    List<String> plannedClosure =
        DefaultApprovedCompilerExecutionRunner.orderedOwningSkillIds(plan);
    for (String skillId : plannedClosure) {
      boolean known =
          pin.resolvedDag().nodes().stream().anyMatch(node -> skillId.equals(node.skillId()));
      if (!known) {
        return "approved skill closure references unknown skill " + skillId;
      }
      String plannedSkillHash = plan.pinnedSkillHashes().get(skillId);
      String pinnedSkillHash = pin.skillSha256ById().get(skillId);
      if (plannedSkillHash != null && !Objects.equals(plannedSkillHash, pinnedSkillHash)) {
        return "pinned skill hash mismatch for " + skillId;
      }
      String plannedAddonHash = plan.pinnedAddonHashes().get(skillId);
      String pinnedAddonHash = pin.addonSha256ById().get(skillId);
      if (plannedAddonHash != null && !Objects.equals(plannedAddonHash, pinnedAddonHash)) {
        return "pinned addon hash mismatch for " + skillId;
      }
    }
    for (var entry : plan.pinnedAddonHashes().entrySet()) {
      if (!Objects.equals(entry.getValue(), pin.addonSha256ById().get(entry.getKey()))) {
        return "pinned addon hash mismatch for " + entry.getKey();
      }
    }

    int previousOrdinal = 0;
    for (DesignExecutionPlan.Step step : plan.steps()) {
      if (step.reportOrdinal() <= previousOrdinal) {
        return "design execution plan step ordinals are out of order";
      }
      previousOrdinal = step.reportOrdinal();
    }

    Optional<ChainSemanticRevision> storedRevision =
        loadPayload(inputs.runId(), inputs.revisionRef(), ChainSemanticRevision.class);
    if (storedRevision.isEmpty()) {
      return "Required artifact CHAIN_SEMANTIC_REVISION is missing for design-execution";
    }
    if (!Objects.equals(storedRevision.get().mappingIntents(), inputs.revision().mappingIntents())) {
      return "mapping intent mismatch between live revision and approved chain semantic revision";
    }
    return null;
  }

  private static boolean containsCandidate(ApprovalRecordV2 approval, Reference ref) {
    return approval.approvedCandidates().stream().anyMatch(candidate -> Objects.equals(candidate, ref));
  }

  private Optional<ExecutionResult> toBindingFailure(List<BindingResolutionResult> results) {
    for (BindingResolutionResult result : results) {
      if (result instanceof BindingResolutionResult.NeedsInput needsInput) {
        return Optional.of(
            ExecutionResult.failure(
                StageOutcomeClass.NEEDS_INPUT,
                "ambiguous catalog binding for " + needsInput.serviceCallId(),
                RecoveryCause.catalogResolution("catalog operation")));
      }
      if (result instanceof BindingResolutionResult.Failed failed) {
        return Optional.of(
            ExecutionResult.failure(
                failed.outcomeClass(),
                failed.reason(),
                RecoveryCause.catalogResolution(failed.requestedFact())));
      }
    }
    return Optional.empty();
  }

  private static String rejectOutsideClosure(
      DesignExecutionPlan plan,
      ResolvedCompilerDag fullDag,
      CompilerDagExecutionResult engineResult) {
    Set<String> allowed = DefaultApprovedCompilerExecutionRunner.skillClosureIds(plan, fullDag);
    for (String executed : engineResult.executedSkillIds()) {
      if (!allowed.contains(executed)) {
        return "engine output includes skill outside the ordered closure: " + executed;
      }
    }
    return null;
  }

  private ExecutionResult emitValidatedBundle(
      ExecutionInputs inputs,
      List<ResolvedServiceCallBinding> bindings,
      CompilerDagExecutionResult engineResult) {
    ChainPlanGraph graph = engineResult.graph();
    GraphAssemblyResult assembly = engineResult.assemblyResult();
    CompilerValidationBundle compilerBundle = engineResult.validationBundle();
    if (graph == null) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE, "assembled graph is required for Phase 5 validation");
    }
    if (compilerBundle == null) {
      return ExecutionResult.failure(
          StageOutcomeClass.VALIDATION_FAILURE,
          "compiler validation bundle is required for Phase 5 validation");
    }

    ValidationResult freshPlanValidation =
        planValidator.validate(
            new PlanGraphValidationInput(
                graph,
                inputs.revision() == null ? List.of() : inputs.revision().mappingIntents()));
    PlanValidationResult planValidation =
        mergeCompilerBundleFindings(
            CompilerPlanningRunner.buildValidationResult(freshPlanValidation, List.of()),
            compilerBundle);
    if (!compilerBundle.approvalEligible() || !planValidation.approvalEligible()) {
      // The rejected graph goes out with the findings. The runtime keeps it against the halted
      // attempt without approving it, which is how the retry gets the steps it must correct
      // instead of rebuilding the chain from nothing.
      return new ExecutionResult(
          StageOutcomeClass.VALIDATION_FAILURE,
          formatValidationFailureMessage(planValidation),
          null,
          List.of(
              new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, graph, List.of()),
              new ArtifactCandidate(Kind.PLAN_VALIDATION_RESULT, planValidation, List.of()),
              new ArtifactCandidate(
                  Kind.COMPILER_VALIDATION_BUNDLE, compilerBundle, List.of())),
          RecoveryCause.fromFindings(
              planValidation.findings(), StageOutcomeClass.VALIDATION_FAILURE));
    }
    // One fresh CompilerPlanValidator pass covers graph structure and plan rules.
    PlanValidationResult graphValidation = planValidation;

    String graphDigest =
        assembly != null && assembly.graphDigest() != null
            ? assembly.graphDigest()
            : compilerBundle.graphDigest();
    String orderedPatchDigest =
        sha256(
            assembly == null || assembly.orderedPatchReferences() == null
                ? "[]"
                : assembly.orderedPatchReferences().toString());

    List<Reference> evidenceInputs =
        List.of(
            inputs.approvalRef(),
            inputs.reportRef(),
            inputs.planRef(),
            inputs.revisionRef(),
            inputs.idsRef(),
            inputs.implementationPlanRef(),
            inputs.runManifestRef());

    ApiOperationBindings apiBindings =
        new ApiOperationBindings(
            SCHEMA_VERSION,
            bindings.stream()
                .map(
                    binding ->
                        new ApiOperationBindings.Binding(
                            binding.serviceCallId(),
                            binding.systemId(),
                            binding.specificationGroupId(),
                            binding.specificationId(),
                            binding.operationId(),
                            binding.packageId(),
                            binding.release()))
                .toList());
    OrderedGraphPatches orderedPatches =
        new OrderedGraphPatches(
            SCHEMA_VERSION,
            assembly == null ? List.of() : assembly.orderedPatchReferences());
    ExecutorValidationBundle executorValidation =
        new ExecutorValidationBundle(
            SCHEMA_VERSION,
            graphDigest,
            inputs.reportRef(),
            inputs.reportRef().contentHash(),
            inputs.planRef(),
            inputs.planRef().contentHash(),
            true,
            List.of());

    // Append validation and graph artifacts first so nested bundle refs are store-backed.
    Reference graphRef = append(inputs, Kind.CHAIN_PLAN_GRAPH, "1", graph, evidenceInputs);
    Reference orderedPatchesRef =
        append(inputs, Kind.ORDERED_GRAPH_PATCHES, SCHEMA_VERSION, orderedPatches, evidenceInputs);
    Reference graphValidationRef =
        append(inputs, Kind.PLAN_VALIDATION_RESULT, "1", graphValidation, evidenceInputs);
    Reference planValidationRef =
        append(inputs, Kind.PLAN_VALIDATION_RESULT, "1", planValidation, evidenceInputs);
    Reference compilerValidationRef =
        append(inputs, Kind.COMPILER_VALIDATION_BUNDLE, "1", compilerBundle, evidenceInputs);
    Reference executorValidationRef =
        append(
            inputs, Kind.EXECUTOR_VALIDATION_BUNDLE, SCHEMA_VERSION, executorValidation, evidenceInputs);

    ValidatedExecutionBundle validated =
        new ValidatedExecutionBundle(
            SCHEMA_VERSION,
            inputs.approvalRef(),
            inputs.reportRef(),
            inputs.reportRef().contentHash(),
            inputs.planRef(),
            inputs.planRef().contentHash(),
            inputs.runManifestRef(),
            graphRef,
            graphDigest,
            orderedPatchesRef,
            orderedPatchDigest,
            graphValidationRef,
            planValidationRef,
            compilerValidationRef,
            executorValidationRef);

    Reference validatedBundleRef =
        append(inputs, Kind.VALIDATED_EXECUTION_BUNDLE, SCHEMA_VERSION, validated, evidenceInputs);
    MaterializationRequest materializationRequest =
        new MaterializationRequest(
            SCHEMA_VERSION,
            inputs.approvalRef(),
            inputs.reportRef(),
            inputs.planRef(),
            graphDigest,
            orderedPatchDigest,
            validatedBundleRef);

    DesignExecutionTrace trace =
        new DesignExecutionTrace(
            SCHEMA_VERSION,
            List.of(
                new DesignExecutionTrace.Entry(
                    DesignExecutionPhase.PRECONDITIONS,
                    null,
                    evidenceInputs,
                    List.of(),
                    "ok"),
                new DesignExecutionTrace.Entry(
                    DesignExecutionPhase.BINDINGS_RESOLVED,
                    null,
                    evidenceInputs,
                    List.of(),
                    "ok"),
                new DesignExecutionTrace.Entry(
                    DesignExecutionPhase.GENERATORS_COMPLETE,
                    null,
                    evidenceInputs,
                    List.of(),
                    "ok"),
                new DesignExecutionTrace.Entry(
                    DesignExecutionPhase.ASSEMBLY_COMPLETE,
                    null,
                    evidenceInputs,
                    List.of(),
                    "ok"),
                new DesignExecutionTrace.Entry(
                    DesignExecutionPhase.VALIDATION_COMPLETE,
                    null,
                    evidenceInputs,
                    List.of(validatedBundleRef),
                    "ok"),
                new DesignExecutionTrace.Entry(
                    DesignExecutionPhase.WAITING_FOR_MATERIALIZATION,
                    null,
                    evidenceInputs,
                    List.of(validatedBundleRef),
                    "ok")));

    DesignExecutionCheckpoint checkpoint =
        new DesignExecutionCheckpoint(
            SCHEMA_VERSION,
            inputs.approvalRef(),
            inputs.reportRef().contentHash(),
            inputs.planRef().contentHash(),
            sha256(inputs.runManifest().toString()),
            DesignExecutionPhase.WAITING_FOR_MATERIALIZATION,
            List.of(
                new DesignExecutionCheckpoint.CompletedStep(
                    "phase-5",
                    List.of(
                        inputs.reportRef().contentHash(),
                        inputs.planRef().contentHash(),
                        inputs.revisionRef().contentHash()),
                    List.of(validatedBundleRef),
                    List.of(validatedBundleRef.contentHash()),
                    provenance(inputs),
                    "WAITING_FOR_MATERIALIZATION")));

    List<ArtifactCandidate> candidates = new ArrayList<>();
    candidates.add(new ArtifactCandidate(Kind.API_OPERATION_BINDINGS, apiBindings, evidenceInputs));
    candidates.add(new ArtifactCandidate(Kind.ORDERED_GRAPH_PATCHES, orderedPatches, evidenceInputs));
    candidates.add(new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, graph, evidenceInputs));
    if (assembly != null) {
      candidates.add(new ArtifactCandidate(Kind.GRAPH_ASSEMBLY_RESULT, assembly, evidenceInputs));
    }
    candidates.add(new ArtifactCandidate(Kind.PLAN_VALIDATION_RESULT, planValidation, evidenceInputs));
    candidates.add(
        new ArtifactCandidate(Kind.COMPILER_VALIDATION_BUNDLE, compilerBundle, evidenceInputs));
    candidates.add(
        new ArtifactCandidate(Kind.EXECUTOR_VALIDATION_BUNDLE, executorValidation, evidenceInputs));
    // VALIDATED_EXECUTION_BUNDLE is store-backed above so MaterializationRequest can embed its
    // contentHash. Do not pre-append MATERIALIZATION_REQUEST here — runtime appendCandidates
    // persists produced candidates once. Re-appending the bundle as a candidate yields a new
    // artifactId with the same contentHash; MaterializationCapability matches on contentHash.
    candidates.add(
        new ArtifactCandidate(Kind.VALIDATED_EXECUTION_BUNDLE, validated, evidenceInputs));
    candidates.add(
        new ArtifactCandidate(Kind.MATERIALIZATION_REQUEST, materializationRequest, evidenceInputs));
    append(inputs, Kind.DESIGN_EXECUTION_CHECKPOINT, SCHEMA_VERSION, checkpoint, evidenceInputs);
    candidates.add(
        new ArtifactCandidate(Kind.DESIGN_EXECUTION_CHECKPOINT, checkpoint, evidenceInputs));
    candidates.add(new ArtifactCandidate(Kind.EXECUTION_TRACE, trace, evidenceInputs));

    return new ExecutionResult(
        StageOutcomeClass.CANDIDATE,
        "design execution ready for materialization",
        checkpoint,
        List.copyOf(candidates));
  }

  private Reference append(
      ExecutionInputs inputs,
      Kind kind,
      String schemaVersion,
      Object payload,
      List<Reference> evidenceInputs) {
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                inputs.runId(),
                kind,
                schemaVersion,
                DesignExecutionCapability.CAPABILITY_ID,
                "1",
                payload,
                evidenceInputs,
                null,
                provenance(inputs)));
    return revision.reference();
  }

  private static PlanValidationResult mergeCompilerBundleFindings(
      PlanValidationResult base, CompilerValidationBundle bundle) {
    List<PlanValidationFinding> findings =
        new ArrayList<>(base == null ? List.of() : base.findings());
    if (bundle != null && bundle.passes() != null) {
      for (var pass : bundle.passes()) {
        if (pass == null || pass.result() == null) {
          continue;
        }
        findings.addAll(
            CompilerPlanningRunner.buildValidationResult(pass.result(), List.of()).findings());
      }
    }
    return new PlanValidationResult(findings);
  }

  private static String formatValidationFailureMessage(PlanValidationResult planValidation) {
    StringBuilder message = new StringBuilder("Phase 5 plan validation failed");
    if (planValidation == null || planValidation.findings() == null) {
      return message.toString();
    }
    List<String> blockers = new ArrayList<>();
    for (PlanValidationFinding finding : planValidation.findings()) {
      if (finding == null || !finding.blocker()) {
        continue;
      }
      String text =
          finding.message() == null || finding.message().isBlank()
              ? finding.code()
              : (finding.code() == null || finding.code().isBlank()
                  ? finding.message()
                  : finding.code() + ": " + finding.message());
      if (text != null && !text.isBlank()) {
        blockers.add(text.trim());
      }
    }
    if (blockers.isEmpty()) {
      return message.toString();
    }
    message.append(". Findings: ");
    int limit = Math.min(5, blockers.size());
    for (int i = 0; i < limit; i++) {
      if (i > 0) {
        message.append("; ");
      }
      message.append(blockers.get(i));
    }
    return message.toString();
  }

  private <T> Optional<T> loadPayload(String runId, Reference ref, Class<T> type) {
    return artifactStore
        .get(runId, ref)
        .map(revision -> artifactStore.payload(revision, type));
  }

  private static ArtifactProvenance provenance(ExecutionInputs inputs) {
    RunManifest manifest = inputs.runManifest();
    return new ArtifactProvenance(
        inputs.runId(),
        "design-execution",
        manifest == null ? "create-chain" : manifest.profileId(),
        manifest == null ? "2" : manifest.profileVersion(),
        manifest == null ? "profile" : manifest.profileDigest(),
        DesignExecutionCapability.CAPABILITY_ID,
        "1",
        manifest == null ? "closure" : manifest.dependencyClosureDigest());
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  /** Inputs for one post-approval Phase 5 execution. */
  public record ExecutionInputs(
      String runId,
      String conversationId,
      Reference approvalRef,
      ApprovalRecordV2 approval,
      DesignPlanReport report,
      Reference reportRef,
      DesignExecutionPlan approvedPlan,
      Reference planRef,
      ChainSemanticRevision revision,
      Reference revisionRef,
      IdsDocument ids,
      Reference idsRef,
      ImplementationPlan implementationPlan,
      Reference implementationPlanRef,
      RunManifest runManifest,
      Reference runManifestRef,
      List<CatalogBindingHint> bindingHints,
      DesignExecutionCheckpoint priorCheckpoint,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph) {

    public ExecutionInputs {
      bindingHints = bindingHints == null ? List.of() : List.copyOf(bindingHints);
    }

    public ExecutionInputs withReportRef(Reference reportRef) {
      return new ExecutionInputs(
          runId,
          conversationId,
          approvalRef,
          approval,
          report,
          reportRef,
          approvedPlan,
          planRef,
          revision,
          revisionRef,
          ids,
          idsRef,
          implementationPlan,
          implementationPlanRef,
          runManifest,
          runManifestRef,
          bindingHints,
          priorCheckpoint,
          repairEvidence,
          priorGraph);
    }

    public ExecutionInputs withPlan(DesignExecutionPlan approvedPlan, Reference planRef) {
      return new ExecutionInputs(
          runId,
          conversationId,
          approvalRef,
          approval,
          report,
          reportRef,
          approvedPlan,
          planRef,
          revision,
          revisionRef,
          ids,
          idsRef,
          implementationPlan,
          implementationPlanRef,
          runManifest,
          runManifestRef,
          bindingHints,
          priorCheckpoint,
          repairEvidence,
          priorGraph);
    }

    public ExecutionInputs withRevision(ChainSemanticRevision revision, Reference revisionRef) {
      return new ExecutionInputs(
          runId,
          conversationId,
          approvalRef,
          approval,
          report,
          reportRef,
          approvedPlan,
          planRef,
          revision,
          revisionRef,
          ids,
          idsRef,
          implementationPlan,
          implementationPlanRef,
          runManifest,
          runManifestRef,
          bindingHints,
          priorCheckpoint,
          repairEvidence,
          priorGraph);
    }
  }

  /** Phase 5 terminal result before materialization. */
  public record ExecutionResult(
      StageOutcomeClass outcomeClass,
      String message,
      DesignExecutionCheckpoint checkpoint,
      List<ArtifactCandidate> candidates,
      RecoveryCause recoveryCause) {

    public ExecutionResult {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public ExecutionResult(
        StageOutcomeClass outcomeClass,
        String message,
        DesignExecutionCheckpoint checkpoint,
        List<ArtifactCandidate> candidates) {
      this(outcomeClass, message, checkpoint, candidates, null);
    }

    static ExecutionResult failure(StageOutcomeClass outcomeClass, String message) {
      return new ExecutionResult(outcomeClass, message, null, List.of(), null);
    }

    static ExecutionResult failure(
        StageOutcomeClass outcomeClass, String message, RecoveryCause recoveryCause) {
      return new ExecutionResult(outcomeClass, message, null, List.of(), recoveryCause);
    }
  }
}
