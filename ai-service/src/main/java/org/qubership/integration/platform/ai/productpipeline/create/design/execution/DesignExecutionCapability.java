package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionInputs;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * create-chain@2 design-execution stage. Resolves the implementation {@link ApprovalRecordV2} and
 * delegates Phase 5 to {@link CipDesignExecutorJavaAdapter}.
 */
@ApplicationScoped
public class DesignExecutionCapability implements StageCapability {

  public static final String CAPABILITY_ID = "design-execution";

  private final ProductPipelineArtifactStore artifactStore;
  private final CipDesignExecutorJavaAdapter adapter;

  @Inject
  public DesignExecutionCapability(
      ProductPipelineArtifactStore artifactStore, CipDesignExecutorJavaAdapter adapter) {
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.adapter = Objects.requireNonNull(adapter, "adapter");
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    // Catalog RestClient + compiler DAG await block; must not run on the Vert.x event loop.
    // Stream SkillProgress (executor + DAG generators/validators) like brainstorming / planning.
    var turnEmit = SkillActivitySupport.captureTurnEmit(context.conversationId());
    return Multi.createFrom()
        .emitter(
            emitter ->
                Uni.createFrom()
                    .item(
                        () -> {
                          String skillId = CipDesignExecutorJavaAdapter.SKILL_ID;
                          emitter.emit(SkillActivitySupport.running(skillId));
                          SkillActivitySupport.bindWorker(skillId, turnEmit);
                          BiConsumer<String, String> dagProgress =
                              (id, status) ->
                                  emitter.emit(new CapabilitySignal.SkillProgress(id, status));
                          try {
                            CapabilitySignal.Completed completed =
                                executeBlocking(context, dagProgress);
                            return SkillActivitySupport.wrapTerminal(
                                skillId, List.of(completed));
                          } finally {
                            SkillActivitySupport.unbindWorker(turnEmit);
                          }
                        })
                    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                    .subscribe()
                    .with(
                        signals -> {
                          for (CapabilitySignal signal : signals) {
                            emitter.emit(signal);
                          }
                          emitter.complete();
                        },
                        emitter::fail));
  }

  private CapabilitySignal.Completed executeBlocking(
      StageExecutionContext context, BiConsumer<String, String> skillProgress) {
    try {
      ResolvedInputs resolved = resolveInputs(context);
      if (resolved.error() != null) {
        return completedSignal(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, resolved.error()));
      }
      ExecutionResult result =
          adapter.executeAfterApproval(resolved.inputs(), context.attemptId(), skillProgress);
      // Adapter uses CANDIDATE to mean Phase 5 checkpoint (WAITING_FOR_MATERIALIZATION). The
      // create-chain@2 design-execution stage has no approval gate, so map that to SUCCEEDED so
      // Flow continues into materialization with the Phase 5 candidates.
      if (result.outcomeClass() == StageOutcomeClass.CANDIDATE
          || result.outcomeClass() == StageOutcomeClass.SUCCEEDED) {
        return completedSignal(
            new StageOutcome(
                StageOutcomeClass.SUCCEEDED,
                result.candidates() == null ? List.of() : result.candidates(),
                result.message(),
                null));
      }
      return completedSignal(StageOutcome.of(result.outcomeClass(), result.message()));
    } catch (RuntimeException ex) {
      String message = ex.getMessage();
      if (message == null || message.isBlank()) {
        message = "design execution failed: " + ex.getClass().getSimpleName();
      }
      return completedSignal(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, message));
    }
  }

  private ResolvedInputs resolveInputs(StageExecutionContext context) {
    Optional<Reference> idsRef = findSingle(context.inputRefs(), Kind.IDS_DOCUMENT);
    Optional<Reference> flowRef = findSingle(context.inputRefs(), Kind.NORMALIZED_DESIGN_FLOW);
    Optional<Reference> reportRef = findSingle(context.inputRefs(), Kind.DESIGN_PLAN_REPORT);
    Optional<Reference> planRef = findSingle(context.inputRefs(), Kind.DESIGN_EXECUTION_PLAN);
    Optional<Reference> implementationRef =
        findSingle(context.inputRefs(), Kind.IMPLEMENTATION_PLAN);
    Optional<Reference> manifestRef = findSingle(context.inputRefs(), Kind.RUN_MANIFEST);
    if (idsRef.isEmpty()
        || flowRef.isEmpty()
        || reportRef.isEmpty()
        || planRef.isEmpty()
        || implementationRef.isEmpty()
        || manifestRef.isEmpty()) {
      return ResolvedInputs.error("design execution inputs are incomplete");
    }

    MatchingApproval matching =
        findImplementationApproval(
            context.runId(), context.inputRefs(), implementationRef.get(), planRef.get());
    if (matching.error() != null) {
      return ResolvedInputs.error(matching.error());
    }

    IdsDocument ids = attributeOrLoad(context, "idsDocument", idsRef.get(), IdsDocument.class);
    NormalizedDesignFlow flow =
        attributeOrLoad(context, "normalizedDesignFlow", flowRef.get(), NormalizedDesignFlow.class);
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
                .map(revision -> artifactStore.payload(revision, RunManifest.class))
                .orElse(null);
    if (ids == null
        || flow == null
        || report == null
        || plan == null
        || implementationPlan == null
        || runManifest == null) {
      return ResolvedInputs.error("design execution payloads are missing");
    }

    List<CatalogBindingHint> hints = loadBindingHints(context);
    DesignExecutionCheckpoint prior =
        artifactStore
            .latest(context.runId(), Kind.DESIGN_EXECUTION_CHECKPOINT)
            .map(revision -> artifactStore.payload(revision, DesignExecutionCheckpoint.class))
            .orElse(null);

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
            flow,
            flowRef.get(),
            ids,
            idsRef.get(),
            implementationPlan,
            implementationRef.get(),
            runManifest,
            manifestRef.get(),
            hints,
            prior);
    return new ResolvedInputs(inputs, null);
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
