package org.qubership.integration.platform.ai.productpipeline.create;

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
import java.util.function.BiFunction;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.capture.ToolArgumentsFailures;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.ImplementationPlanRenderer;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFacts;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFactsService;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerPlanningRunner.PlanningSpineOutcome;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Builds full CREATE candidate sets from compiler-derived planning outputs. */
@ApplicationScoped
public class CompilerDerivedPlanningRunner {

  private final BiFunction<CompilerPlanningRequest, BiConsumer<String, String>, DerivedPlanningResult>
      planner;

  @Inject
  public CompilerDerivedPlanningRunner(
      CompilerDerivedPlanningSpine spine,
      InMemorySkillWorkspaceStore workspaceStore,
      PlanPresentationFactsService presentationFactsService,
      CompilerPlanValidator planValidator) {
    this(
        (request, skillProgress) ->
            collectFromSpine(
                request,
                skillProgress,
                spine,
                workspaceStore,
                presentationFactsService,
                planValidator));
  }

  CompilerDerivedPlanningRunner(
      BiFunction<CompilerPlanningRequest, BiConsumer<String, String>, DerivedPlanningResult>
          planner) {
    this.planner = Objects.requireNonNull(planner, "planner");
  }

  static CompilerDerivedPlanningRunner forTests(
      BiFunction<CompilerPlanningRequest, BiConsumer<String, String>, DerivedPlanningResult>
          planner) {
    return new CompilerDerivedPlanningRunner(planner);
  }

  public Uni<StageOutcome> plan(CompilerPlanningRequest request) {
    Objects.requireNonNull(request, "request");
    return Uni.createFrom()
        .item(
            () -> {
              try {
                return toOutcome(
                    planner.apply(request, (skillId, status) -> {}));
              } catch (PlanningSkillArtifactUnavailableException failure) {
                return missingArtifactOutcome(failure);
              } catch (RuntimeException ex) {
                if (TransientFailures.isTransient(ex)) {
                  return transientOutcome(ex);
                }
                if (ToolArgumentsFailures.isToolArgumentsFailure(ex)) {
                  return toolArgumentsOutcome(ex);
                }
                if (isContractFailure(ex)) {
                  return StageOutcome.of(
                      StageOutcomeClass.CONTRACT_FAILURE,
                      ex.getMessage() == null ? "contract failure" : ex.getMessage());
                }
                throw ex;
              }
            })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  public Multi<CapabilitySignal> planWithProgress(CompilerPlanningRequest request) {
    Objects.requireNonNull(request, "request");
    // Spine + LLM skills block; must not run on the Vert.x event loop.
    return Multi.createFrom()
        .<CapabilitySignal>emitter(
            emitter -> {
              try {
                DerivedPlanningResult result =
                    planner.apply(
                        request,
                        (skillId, status) ->
                            emitter.emit(new CapabilitySignal.SkillProgress(skillId, status)));
                emitter.emit(new CapabilitySignal.Completed(toOutcome(result)));
                emitter.complete();
              } catch (PlanningSkillArtifactUnavailableException failure) {
                emitter.emit(
                    new CapabilitySignal.Completed(
                        missingArtifactOutcome(failure)));
                emitter.complete();
              } catch (RuntimeException ex) {
                if (TransientFailures.isTransient(ex)) {
                  emitter.emit(new CapabilitySignal.Completed(transientOutcome(ex)));
                  emitter.complete();
                } else if (ToolArgumentsFailures.isToolArgumentsFailure(ex)) {
                  emitter.emit(new CapabilitySignal.Completed(toolArgumentsOutcome(ex)));
                  emitter.complete();
                } else if (isContractFailure(ex)) {
                  emitter.emit(
                      new CapabilitySignal.Completed(
                          StageOutcome.of(
                              StageOutcomeClass.CONTRACT_FAILURE,
                              ex.getMessage() == null ? "contract failure" : ex.getMessage())));
                  emitter.complete();
                } else {
                  emitter.fail(ex);
                }
              }
            })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  private static boolean isContractFailure(Throwable ex) {
    String message = ex.getMessage();
    return message != null && message.startsWith("contract failure:");
  }

  private static StageOutcome missingArtifactOutcome(
      PlanningSkillArtifactUnavailableException failure) {
    return StageOutcome.of(
        StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
        failure.getMessage());
  }

  private static StageOutcome toolArgumentsOutcome(Throwable failure) {
    String message = ToolArgumentsFailures.message(failure);
    if (message == null || message.isBlank()) {
      message = "invalid tool arguments";
    }
    return StageOutcome.of(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, message);
  }

  private static StageOutcome transientOutcome(Throwable failure) {
    String message = failure.getMessage();
    return StageOutcome.of(
        StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
        message == null || message.isBlank() ? failure.toString() : message);
  }

  private static DerivedPlanningResult collectFromSpine(
      CompilerPlanningRequest request,
      BiConsumer<String, String> skillProgress,
      CompilerDerivedPlanningSpine spine,
      InMemorySkillWorkspaceStore workspaceStore,
      PlanPresentationFactsService presentationFactsService,
      CompilerPlanValidator planValidator) {
    Objects.requireNonNull(spine, "spine");
    Objects.requireNonNull(workspaceStore, "workspaceStore");
    Objects.requireNonNull(presentationFactsService, "presentationFactsService");
    Objects.requireNonNull(planValidator, "planValidator");
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;

    PlanningSpineOutcome spineOutcome =
        spine.execute(request, progress).await().indefinitely();
    SkillWorkspace workspace = workspaceStore.getOrCreate(request.conversationId());

    ChainPlanGraph graph =
        workspace
            .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
            .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
            .orElse(spineOutcome == null ? null : spineOutcome.graph());
    GraphAssemblyResult assembly =
        workspace
            .get(SkillArtifactType.GRAPH_ASSEMBLY_RESULT)
            .map(a -> ((SkillArtifactPayload.GraphAssemblyResultPayload) a.payload()).result())
            .orElse(null);
    CompilerValidationBundle bundle =
        workspace
            .get(SkillArtifactType.COMPILER_VALIDATION_BUNDLE)
            .map(a -> ((SkillArtifactPayload.CompilerValidationBundlePayload) a.payload()).bundle())
            .orElse(null);

    if (graph == null || assembly == null || bundle == null) {
      return new DerivedPlanningResult(
          null,
          null,
          graph,
          assembly,
          bundle,
          spineOutcome == null ? List.of() : spineOutcome.executedSkillIds());
    }

    ValidationResult planValidation =
        planValidator.validate(
            new PlanGraphValidationInput(graph, mappingIntents(workspace)));
    PlanValidationResult planValidationResult =
        withDegradations(
            mergeCompilerBundleFindings(
                CompilerPlanningRunner.buildValidationResult(planValidation, List.of()), bundle),
            spineOutcome);

    PlanPresentationFacts presentationFacts = presentationFactsService.build(workspace);
    ImplementationPlan plan =
        ImplementationPlanRenderer.render(
            presentationFacts,
            "planning-capability",
            "1",
            List.of("requirement-brief", "ids-bypass", "compiler-pipeline"),
            request.dependencyClosure());
    Optional<String> coverage = ImplementationPlanRenderer.verifyCoverage(plan);
    if (coverage.isPresent()) {
      List<PlanValidationFinding> findings = new ArrayList<>(planValidationResult.findings());
      findings.add(new PlanValidationFinding("PLAN_COVERAGE", coverage.get(), true));
      planValidationResult = new PlanValidationResult(findings);
    }

    return new DerivedPlanningResult(
        plan,
        planValidationResult,
        graph,
        assembly,
        bundle,
        spineOutcome == null ? List.of() : spineOutcome.executedSkillIds());
  }

  /**
   * Carry the fail-open skips and substitutions the spine reported onto the candidate plan. They are
   * non-blockers, so the plan still reaches approval and the author reads what was degraded.
   */
  private static PlanValidationResult withDegradations(
      PlanValidationResult base, PlanningSpineOutcome spineOutcome) {
    if (spineOutcome == null || spineOutcome.degradationFindings().isEmpty()) {
      return base;
    }
    List<PlanValidationFinding> findings =
        new ArrayList<>(base == null ? List.of() : base.findings());
    findings.addAll(spineOutcome.degradationFindings());
    return new PlanValidationResult(findings);
  }

  /**
   * Fold deterministic compiler-pass blockers into the plan validation result so VALIDATION_FAILURE
   * commits visible findings (empty plan findings alone previously hid bundle failures).
   */
  static PlanValidationResult mergeCompilerBundleFindings(
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

  /**
   * User-facing VALIDATION_FAILURE text. Includes up to five blocker findings so chat is not stuck
   * with only "planning validation failed".
   */
  static String formatValidationFailureMessage(PlanValidationResult planValidation) {
    StringBuilder message = new StringBuilder("planning validation failed");
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
    if (blockers.size() > limit) {
      message.append(" (+").append(blockers.size() - limit).append(" more)");
    }
    return message.toString();
  }

  private static StageOutcome toOutcome(DerivedPlanningResult result) {
    if (result == null
        || result.implementationPlan() == null
        || result.graph() == null
        || result.graphAssemblyResult() == null
        || result.compilerValidationBundle() == null
        || result.planValidationResult() == null) {
      return StageOutcome.of(
          StageOutcomeClass.CONTRACT_FAILURE,
          "implementation plan, graph, assembly result, compiler bundle, and plan validation are required");
    }
    List<ArtifactCandidate> candidates = new ArrayList<>();
    candidates.add(new ArtifactCandidate(Kind.IMPLEMENTATION_PLAN, result.implementationPlan(), List.of()));
    candidates.add(
        new ArtifactCandidate(Kind.PLAN_VALIDATION_RESULT, result.planValidationResult(), List.of()));
    candidates.add(new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, result.graph(), List.of()));
    candidates.add(
        new ArtifactCandidate(Kind.GRAPH_ASSEMBLY_RESULT, result.graphAssemblyResult(), List.of()));
    candidates.add(
        new ArtifactCandidate(
            Kind.COMPILER_VALIDATION_BUNDLE, result.compilerValidationBundle(), List.of()));
    if (!result.compilerValidationBundle().approvalEligible()
        || !result.planValidationResult().approvalEligible()) {
      return new StageOutcome(
          StageOutcomeClass.VALIDATION_FAILURE,
          List.of(
              new ArtifactCandidate(
                  Kind.PLAN_VALIDATION_RESULT, result.planValidationResult(), List.of()),
              new ArtifactCandidate(
                  Kind.COMPILER_VALIDATION_BUNDLE, result.compilerValidationBundle(), List.of())),
          formatValidationFailureMessage(result.planValidationResult()),
          null,
          RecoveryCause.fromFindings(
              result.planValidationResult().findings(), StageOutcomeClass.VALIDATION_FAILURE));
    }
    return new StageOutcome(
        StageOutcomeClass.CANDIDATE,
        List.copyOf(candidates),
        "planning candidate ready",
        null);
  }

  public record DerivedPlanningResult(
      ImplementationPlan implementationPlan,
      PlanValidationResult planValidationResult,
      ChainPlanGraph graph,
      GraphAssemblyResult graphAssemblyResult,
      CompilerValidationBundle compilerValidationBundle,
      List<String> executedSkillIds) {

    public DerivedPlanningResult {
      executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
    }
  }

  private static List<MappingIntent> mappingIntents(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.REQUIREMENT_BRIEF)
        .map(
            artifact ->
                ((SkillArtifactPayload.RequirementBriefPayload) artifact.payload())
                    .brief()
                    .mappingIntents())
        .orElse(List.of());
  }
}
