package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.ToolArgumentsFailures;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.SupersededBriefLineageGuard;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningSkillArtifactUnavailableException;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionInputs;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

/**
 * create-chain@2 design-execution stage. Resolves the implementation {@link ApprovalRecordV2} and
 * delegates Phase 5 to {@link CipDesignExecutorJavaAdapter}.
 */
@ApplicationScoped
public class DesignExecutionCapability implements StageCapability {

  public static final String CAPABILITY_ID = "design-execution";

  /**
   * Live recovery injects {@link RecoveryCauseCode#MISSING_REQUIRED_PROPERTY}, which auto-reopens
   * the owner. A second injection on the same run parks because that owner already reopened.
   */
  static final int MAX_E2E_RECOVERY_FAULT_INJECTIONS = 2;

  private final ProductPipelineArtifactStore artifactStore;
  private final CipDesignExecutorJavaAdapter adapter;
  private final String recoveryFaultChainPrefix;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final ConcurrentHashMap<String, Integer> recoveryFaultInjections =
      new ConcurrentHashMap<>();

  @Inject
  public DesignExecutionCapability(
      ProductPipelineArtifactStore artifactStore,
      CipDesignExecutorJavaAdapter adapter,
      AppConfig appConfig,
      CaptureAttemptFeedbackStore feedbackStore) {
    this(
        artifactStore,
        adapter,
        appConfig.e2e().recoveryFaultChainPrefix().orElse(""),
        feedbackStore);
  }

  public DesignExecutionCapability(
      ProductPipelineArtifactStore artifactStore, CipDesignExecutorJavaAdapter adapter) {
    this(artifactStore, adapter, "", null);
  }

  /** Test constructor: sets the recovery-fault chain-name prefix directly. */
  DesignExecutionCapability(
      ProductPipelineArtifactStore artifactStore,
      CipDesignExecutorJavaAdapter adapter,
      String recoveryFaultChainPrefix) {
    this(artifactStore, adapter, recoveryFaultChainPrefix, null);
  }

  DesignExecutionCapability(
      ProductPipelineArtifactStore artifactStore,
      CipDesignExecutorJavaAdapter adapter,
      String recoveryFaultChainPrefix,
      CaptureAttemptFeedbackStore feedbackStore) {
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.adapter = Objects.requireNonNull(adapter, "adapter");
    this.recoveryFaultChainPrefix =
        recoveryFaultChainPrefix == null ? "" : recoveryFaultChainPrefix.trim();
    this.feedbackStore = feedbackStore;
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    // Catalog RestClient + compiler DAG await block; must not run on the Vert.x event loop.
    // Generator skill rows go out as CapabilitySignal.SkillProgress; the stage executor forwards
    // them live. Tool steps still nest through ToolInvocationSink after bindWorker.
    return Multi.createFrom()
        .emitter(
            emitter -> {
              AtomicReference<Cancellable> subscription = new AtomicReference<>();
              emitter.onTermination(
                  () -> {
                    Cancellable cancellable = subscription.get();
                    if (cancellable != null) {
                      cancellable.cancel();
                    }
                  });
              subscription.set(
                  Uni.createFrom()
                      .item(
                          () -> {
                            String skillId = CipDesignExecutorJavaAdapter.SKILL_ID;
                            try {
                              SkillActivitySupport.bindWorker(skillId, context.conversationId());
                              emitter.emit(SkillActivitySupport.running(skillId));
                              BiConsumer<String, String> dagProgress =
                                  (id, status) ->
                                      emitter.emit(new CapabilitySignal.SkillProgress(id, status));
                              CapabilitySignal.Completed completed =
                                  executeBlocking(context, dagProgress);
                              return SkillActivitySupport.wrapTerminal(
                                  skillId, List.of(completed));
                            } finally {
                              SkillActivitySupport.unbindWorker();
                            }
                          })
                      .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                      .subscribe()
                      .with(
                          signals -> {
                            if (emitter.isCancelled()) {
                              return;
                            }
                            for (CapabilitySignal signal : signals) {
                              if (emitter.isCancelled()) {
                                return;
                              }
                              emitter.emit(signal);
                            }
                            emitter.complete();
                          },
                          failure -> {
                            if (!emitter.isCancelled()) {
                              emitter.fail(failure);
                            }
                          }));
            });
  }

  private CapabilitySignal.Completed executeBlocking(
      StageExecutionContext context, BiConsumer<String, String> skillProgress) {
    try {
      ResolvedInputs resolved = resolveInputs(context);
      if (resolved.error() != null) {
        return completedSignal(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, resolved.error()));
      }
      if (injectRecoveryFault(context.runId(), resolved.inputs().revision().chainIdentity())) {
        return completedSignal(
            StageOutcome.of(
                StageOutcomeClass.VALIDATION_FAILURE,
                "E2E recovery fault: the implementation plan is missing required setting "
                    + "'recovery-check'. Revise design-planning before materialization.",
                RecoveryCause.of(RecoveryCauseCode.MISSING_REQUIRED_PROPERTY)));
      }
      ExecutionResult result =
          adapter.executeAfterApproval(resolved.inputs(), context.attemptId(), skillProgress);
      // Adapter uses CANDIDATE to mean Phase 5 checkpoint (WAITING_FOR_MATERIALIZATION). The
      // create-chain@2 design-execution stage has no approval gate, so map that to SUCCEEDED so
      // Flow continues into materialization with the Phase 5 candidates.
      StageOutcomeClass mapped =
          result.outcomeClass() == StageOutcomeClass.CANDIDATE
                  || result.outcomeClass() == StageOutcomeClass.SUCCEEDED
              ? StageOutcomeClass.SUCCEEDED
              : result.outcomeClass();
      return completedSignal(
          new StageOutcome(
              mapped,
              result.candidates() == null ? List.of() : result.candidates(),
              result.message(),
              null,
              result.recoveryCause()));
    } catch (RuntimeException ex) {
      if (TransientFailures.isTransient(ex)) {
        return completedSignal(
            StageOutcome.of(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, ex.getMessage()));
      }
      if (ToolArgumentsFailures.isToolArgumentsFailure(ex)) {
        return completedSignal(
            StageOutcome.of(
                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                ToolArgumentsFailures.message(ex)));
      }
      if (ex instanceof PlanningSkillArtifactUnavailableException missing) {
        return completedSignal(
            StageOutcome.of(
                StageOutcomeClass.VALIDATION_FAILURE,
                structureUnavailableMessage(context.conversationId(), missing)));
      }
      String message = ex.getMessage();
      if (message == null || message.isBlank()) {
        message = "design execution failed: " + ex.getClass().getSimpleName();
      }
      return completedSignal(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, message));
    }
  }

  private boolean injectRecoveryFault(String runId, String chainName) {
    if (recoveryFaultChainPrefix.isBlank()
        || chainName == null
        || !chainName.startsWith(recoveryFaultChainPrefix)) {
      return false;
    }
    int injected = recoveryFaultInjections.merge(runId, 1, Integer::sum);
    return injected <= MAX_E2E_RECOVERY_FAULT_INJECTIONS;
  }

  private ResolvedInputs resolveInputs(StageExecutionContext context) {
    Optional<Reference> idsRef = findSingle(context.inputRefs(), Kind.IDS_DOCUMENT);
    Optional<Reference> revisionRef = findSingle(context.inputRefs(), Kind.CHAIN_SEMANTIC_REVISION);
    Optional<Reference> reportRef = findSingle(context.inputRefs(), Kind.DESIGN_PLAN_REPORT);
    Optional<Reference> planRef = findSingle(context.inputRefs(), Kind.DESIGN_EXECUTION_PLAN);
    Optional<Reference> implementationRef =
        findSingle(context.inputRefs(), Kind.IMPLEMENTATION_PLAN);
    Optional<Reference> manifestRef = findSingle(context.inputRefs(), Kind.RUN_MANIFEST);
    if (idsRef.isEmpty()
        || revisionRef.isEmpty()
        || reportRef.isEmpty()
        || planRef.isEmpty()
        || implementationRef.isEmpty()
        || manifestRef.isEmpty()) {
      if (revisionRef.isEmpty()) {
        return ResolvedInputs.error(
            "Required artifact CHAIN_SEMANTIC_REVISION is missing for design-execution");
      }
      return ResolvedInputs.error("design execution inputs are incomplete");
    }

    MatchingApproval matching =
        findImplementationApproval(
            context.runId(), context.inputRefs(), implementationRef.get(), planRef.get());
    if (matching.error() != null) {
      return ResolvedInputs.error(matching.error());
    }

    IdsDocument ids = attributeOrLoad(context, "idsDocument", idsRef.get(), IdsDocument.class);
    ChainSemanticRevision revision =
        attributeOrLoad(
            context, "chainSemanticRevision", revisionRef.get(), ChainSemanticRevision.class);
    DesignPlanReport report =
        attributeOrLoad(context, "designPlanReport", reportRef.get(), DesignPlanReport.class);
    DesignExecutionPlan plan =
        attributeOrLoad(context, "designExecutionPlan", planRef.get(), DesignExecutionPlan.class);
    ImplementationPlan implementationPlan =
        attributeOrLoad(
            context, "implementationPlan", implementationRef.get(), ImplementationPlan.class);
    RunManifest runManifest =
        context.runManifest() != null
            ? context.runManifest()
            : artifactStore
                .get(context.runId(), manifestRef.get())
                .map(stored -> artifactStore.payload(stored, RunManifest.class))
                .orElse(null);
    if (ids == null
        || revision == null
        || report == null
        || plan == null
        || implementationPlan == null
        || runManifest == null) {
      if (revision == null) {
        return ResolvedInputs.error(
            "Required artifact CHAIN_SEMANTIC_REVISION is missing for design-execution");
      }
      return ResolvedInputs.error("design execution payloads are missing");
    }

    List<CatalogBindingHint> hints = loadBindingHints(context);
    DesignExecutionCheckpoint prior =
        artifactStore
            .latest(context.runId(), Kind.DESIGN_EXECUTION_CHECKPOINT)
            .map(stored -> artifactStore.payload(stored, DesignExecutionCheckpoint.class))
            .orElse(null);

    // Repair turn: fold the halt evidence and the graph the failing attempt left behind into the
    // execution inputs, so the compiler DAG's generator skills correct that step instead of
    // rebuilding the chain from scratch. Null on a first turn — StageRepairEvidence.from already
    // returns null then, and there is no prior graph to reach for.
    StageRepairEvidence repairEvidence = StageRepairEvidence.from(context);
    ChainPlanGraph priorGraph =
        repairEvidence == null ? null : loadPriorGraph(context, repairEvidence);

    ExecutionInputs inputs =
        new ExecutionInputs(
            context.runId(),
            context.conversationId(),
            matching.approvalRef(),
            matching.approval(),
            report,
            reportRef.get(),
            plan,
            planRef.get(),
            revision,
            revisionRef.get(),
            ids,
            idsRef.get(),
            implementationPlan,
            implementationRef.get(),
            runManifest,
            manifestRef.get(),
            hints,
            prior,
            repairEvidence,
            priorGraph);
    return new ResolvedInputs(inputs, null);
  }

  /**
   * The graph the repair turn compares against: the one the halted attempt of this stage assembled,
   * so a stage failing on its first pass has something to correct. Falls back to the latest graph in
   * the run when the halted attempt recorded none, which is the graph of an earlier successful pass.
   */
  private ChainPlanGraph loadPriorGraph(
      StageExecutionContext context, StageRepairEvidence repairEvidence) {
    Optional<Revision> revision =
        repairEvidence
            .priorOutput(Kind.CHAIN_PLAN_GRAPH)
            .flatMap(ref -> artifactStore.get(context.runId(), ref))
            .or(() -> artifactStore.latest(context.runId(), Kind.CHAIN_PLAN_GRAPH));
    if (revision.isEmpty()) {
      return null;
    }
    if (SupersededBriefLineageGuard.isSupersededCompileInput(
        artifactStore, context.runId(), context.attributes(), revision.get())) {
      return null;
    }
    return artifactStore.payload(revision.get(), ChainPlanGraph.class);
  }

  private MatchingApproval findImplementationApproval(
      String runId,
      List<Reference> inputRefs,
      Reference implementationRef,
      Reference planRef) {
    List<Reference> approvalRefs =
        inputRefs == null
            ? List.of()
            : inputRefs.stream()
                .filter(ref -> ref != null && ref.kind() == Kind.APPROVAL_RECORD)
                .toList();
    List<MatchingApproval> matches = new ArrayList<>();
    for (Reference approvalRef : approvalRefs) {
      Optional<Revision> revision = artifactStore.get(runId, approvalRef);
      if (revision.isEmpty() || !"2".equals(revision.get().schemaVersion())) {
        continue;
      }
      ApprovalRecordV2 approval =
          artifactStore.payload(revision.get(), ApprovalRecordV2.class);
      if (approval == null) {
        continue;
      }
      // Ignore earlier IDS (or any non-IMPLEMENTATION_PLAN) approval records.
      if (!implementationRef.equals(approval.target())) {
        continue;
      }
      if (!Objects.equals(implementationRef.contentHash(), approval.targetContentHash())) {
        continue;
      }
      // Projection must be in the candidate set; report hash is verified later against the live
      // DESIGN_PLAN_REPORT input so a stale report surfaces as a report-hash contract failure.
      if (!approval.approvedCandidates().contains(planRef)) {
        continue;
      }
      boolean hasReportCandidate =
          approval.approvedCandidates().stream()
              .anyMatch(candidate -> candidate != null && candidate.kind() == Kind.DESIGN_PLAN_REPORT);
      if (!hasReportCandidate) {
        continue;
      }
      matches.add(new MatchingApproval(approvalRef, approval, null));
    }
    if (matches.isEmpty()) {
      return MatchingApproval.error(
          "implementation approval record matching IMPLEMENTATION_PLAN is missing");
    }
    if (matches.size() > 1) {
      return MatchingApproval.error("ambiguous implementation approval records");
    }
    return matches.getFirst();
  }

  private <T> T attributeOrLoad(
      StageExecutionContext context, String attributeKey, Reference ref, Class<T> type) {
    Object attribute = context.attributes().get(attributeKey);
    if (type.isInstance(attribute)) {
      return type.cast(attribute);
    }
    return artifactStore
        .get(context.runId(), ref)
        .map(revision -> artifactStore.payload(revision, type))
        .orElse(null);
  }

  private List<CatalogBindingHint> loadBindingHints(StageExecutionContext context) {
    Object attribute = context.attributes().get("catalogBindingHints");
    if (attribute instanceof List<?> list) {
      List<CatalogBindingHint> hints = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof CatalogBindingHint hint) {
          hints.add(hint);
        }
      }
      return List.copyOf(hints);
    }
    return artifactStore.history(context.runId(), Kind.CATALOG_BINDING_HINT).stream()
        .map(revision -> artifactStore.payload(revision, CatalogBindingHint.class))
        .filter(Objects::nonNull)
        .toList();
  }

  private static Optional<Reference> findSingle(List<Reference> refs, Kind kind) {
    List<Reference> matches =
        refs == null
            ? List.of()
            : refs.stream().filter(ref -> ref != null && ref.kind() == kind).toList();
    if (matches.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(matches.getFirst());
  }

  /** Last capture rejection, or a skill-id summary when capture never recorded one. */
  private String structureUnavailableMessage(
      String conversationId, PlanningSkillArtifactUnavailableException missing) {
    if (feedbackStore != null && conversationId != null && !conversationId.isBlank()) {
      Optional<String> summary =
          feedbackStore
              .lastPlanFailure(conversationId)
              .map(CaptureAttemptFeedback::summary)
              .filter(text -> text != null && !text.isBlank());
      if (summary.isPresent()) {
        return summary.get();
      }
    }
    return "Skill '"
        + missing.skillId()
        + "' did not produce required artifacts: "
        + missing.missingArtifactTypes()
        + ".";
  }

  private static CapabilitySignal.Completed completedSignal(StageOutcome outcome) {
    return new CapabilitySignal.Completed(outcome);
  }

  private record ResolvedInputs(ExecutionInputs inputs, String error) {
    static ResolvedInputs error(String message) {
      return new ResolvedInputs(null, message);
    }
  }

  private record MatchingApproval(
      Reference approvalRef, ApprovalRecordV2 approval, String error) {
    static MatchingApproval error(String message) {
      return new MatchingApproval(null, null, message);
    }
  }
}
