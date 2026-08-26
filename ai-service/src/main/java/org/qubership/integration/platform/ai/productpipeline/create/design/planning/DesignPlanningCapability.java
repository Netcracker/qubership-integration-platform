package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Shared create-chain design-planning capability. Runs the pinned planner, projects the catalog
 * DAG, and renders the approval-target {@link ImplementationPlan}.
 */
@ApplicationScoped
public class DesignPlanningCapability implements StageCapability {

  public static final String CAPABILITY_ID = "design-planning";

  private final CipDesignPlannerAdapter planner;
  private final DesignPlanProjector projector;
  private final DesignImplementationPlanRenderer renderer;
  private final ProductPipelineArtifactStore artifactStore;

  @Inject
  public DesignPlanningCapability(
      DesignProcessSkillRunner runner, ProductPipelineArtifactStore artifactStore) {
    this(
        new CipDesignPlannerAdapter(runner, new CipDesignPlannerReportParser()),
        new DesignPlanProjector(),
        new DesignImplementationPlanRenderer(),
        artifactStore);
  }

  DesignPlanningCapability(
      CipDesignPlannerAdapter planner,
      DesignPlanProjector projector,
      DesignImplementationPlanRenderer renderer,
      ProductPipelineArtifactStore artifactStore) {
    this.planner = Objects.requireNonNull(planner, "planner");
    this.projector = Objects.requireNonNull(projector, "projector");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    // Planner agent blocks; must not run on the Vert.x event loop.
    var turnEmit = SkillActivitySupport.captureTurnEmit(context.conversationId());
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(CipDesignPlannerAdapter.SKILL_ID)),
            Uni.createFrom()
                .item(
                    () -> {
                      SkillActivitySupport.bindWorker(
                          CipDesignPlannerAdapter.SKILL_ID, turnEmit);
                      try {
                        return SkillActivitySupport.wrapTerminal(
                            CipDesignPlannerAdapter.SKILL_ID, List.of(executeBlocking(context)));
                      } finally {
                        SkillActivitySupport.unbindWorker(turnEmit);
                      }
                    })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem()
                .transformToMulti(signals -> Multi.createFrom().iterable(signals)));
  }

  private CapabilitySignal.Completed executeBlocking(StageExecutionContext context) {
    // Held outside the try so a rejection after the planner answered still hands the halt the
    // artifact it is about. Without that, the next attempt reads the complaint and nothing else.
    DesignPlanReport report = null;
    DesignExecutionPlan projection = null;
    try {
      IdsDocument ids = requireIds(context);
      NormalizedDesignFlow flow = requireFlow(context);
      RunManifest runManifest = requireRunManifest(context);
      CompilerRunPin pin = requireCompilerPin(runManifest);
      String pinnedSkillHash = pin.skillSha256ById().get(CipDesignPlannerAdapter.SKILL_ID);
      if (pinnedSkillHash == null || pinnedSkillHash.isBlank()) {
        return new CapabilitySignal.Completed(
            StageOutcome.of(
                StageOutcomeClass.CONTRACT_FAILURE,
                "run manifest is missing pinned hash for " + CipDesignPlannerAdapter.SKILL_ID));
      }

      String release = toApiRelease(runManifest.languageVersion());
      StageRepairEvidence repair = StageRepairEvidence.from(context);
      String repairEvidenceText =
          repair == null ? "" : repairEvidenceText(repair, priorPlanMarkdown(context, repair));
      report =
          planner.plan(
              new PlannerRequest(
                  context.conversationId(),
                  buildPlannerInput(ids, flow, release),
                  pinnedSkillHash,
                  repairEvidenceText));
      projection =
          projector.project(
              report,
              flow,
              pin.resolvedDag(),
              pin.pipelineIndexDigest(),
              pin.skillSha256ById(),
              pin.addonSha256ById());
      ImplementationPlan rendering = renderer.render(report, projection, flow);

      Reference idsRef = requireInputRef(context.inputRefs(), Kind.IDS_DOCUMENT);
      Reference flowRef = requireInputRef(context.inputRefs(), Kind.NORMALIZED_DESIGN_FLOW);

      List<ArtifactCandidate> candidates = new ArrayList<>();
      candidates.add(new ArtifactCandidate(Kind.IDS_DOCUMENT, ids, List.of(idsRef)));
      candidates.add(new ArtifactCandidate(Kind.NORMALIZED_DESIGN_FLOW, flow, List.of(flowRef)));
      candidates.add(new ArtifactCandidate(Kind.DESIGN_PLAN_REPORT, report, List.of(idsRef, flowRef)));
      candidates.add(
          new ArtifactCandidate(Kind.DESIGN_EXECUTION_PLAN, projection, List.of(idsRef, flowRef)));
      candidates.add(
          new ArtifactCandidate(Kind.IMPLEMENTATION_PLAN, rendering, List.of(idsRef, flowRef)));

      return new CapabilitySignal.Completed(
          new StageOutcome(
              StageOutcomeClass.CANDIDATE,
              candidates,
              "design plan ready for approval",
              null));
    } catch (PlannerContractException ex) {
      return haltedSignal(
          ex.outcomeClass() == null ? StageOutcomeClass.CONTRACT_FAILURE : ex.outcomeClass(),
          ex.getMessage(),
          report,
          projection);
    } catch (RuntimeException ex) {
      return haltedSignal(
          TransientFailures.isTransient(ex)
              ? StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE
              : StageOutcomeClass.CONTRACT_FAILURE,
          ex.getMessage(),
          report,
          projection);
    }
  }

  /**
   * Halts carrying whatever this attempt managed to build. The runtime records those artifacts
   * against the halted attempt without approving them or marking the stage succeeded, which is what
   * lets the next attempt of this stage read the rejected plan back.
   */
  private static CapabilitySignal.Completed haltedSignal(
      StageOutcomeClass outcomeClass,
      String message,
      DesignPlanReport report,
      DesignExecutionPlan projection) {
    List<ArtifactCandidate> produced = new ArrayList<>();
    if (report != null) {
      produced.add(new ArtifactCandidate(Kind.DESIGN_PLAN_REPORT, report, List.of()));
    }
    if (projection != null) {
      produced.add(new ArtifactCandidate(Kind.DESIGN_EXECUTION_PLAN, projection, List.of()));
    }
    return new CapabilitySignal.Completed(
        new StageOutcome(outcomeClass, List.copyOf(produced), message, null));
  }

  /**
   * Markdown of the plan the halted attempt of this stage wrote, or blank when it never produced
   * one. Read through the halt evidence rather than through the committed inputs: the pipeline never
   * approved this report, and only the retry of this same stage is allowed to see it.
   */
  private String priorPlanMarkdown(StageExecutionContext context, StageRepairEvidence repair) {
    return repair
        .priorOutput(Kind.DESIGN_PLAN_REPORT)
        .flatMap(ref -> artifactStore.get(context.runId(), ref))
        .map(revision -> artifactStore.payload(revision, DesignPlanReport.class))
        .map(DesignPlanReport::markdown)
        .orElse("");
  }

  private static IdsDocument requireIds(StageExecutionContext context) {
    Object value = context.attributes().get("idsDocument");
    if (value instanceof IdsDocument document) {
      return document;
    }
    throw new PlannerContractException("IDS_DOCUMENT is required for design planning");
  }

  private static NormalizedDesignFlow requireFlow(StageExecutionContext context) {
    Object value = context.attributes().get("normalizedDesignFlow");
    if (value instanceof NormalizedDesignFlow flow) {
      return flow;
    }
    throw new PlannerContractException("NORMALIZED_DESIGN_FLOW is required for design planning");
  }

  private static RunManifest requireRunManifest(StageExecutionContext context) {
    if (context.runManifest() == null) {
      throw new PlannerContractException("RUN_MANIFEST is required for design planning");
    }
    return context.runManifest();
  }

  private static CompilerRunPin requireCompilerPin(RunManifest runManifest) {
    if (runManifest.compilerRunPin() == null) {
      throw new PlannerContractException("compiler run pin is required for design planning");
    }
    return runManifest.compilerRunPin();
  }

  private static Reference requireInputRef(List<Reference> inputRefs, Kind kind) {
    return inputRefs.stream()
        .filter(ref -> ref != null && ref.kind() == kind)
        .findFirst()
        .orElseThrow(
            () ->
                new PlannerContractException(
                    "design planning requires committed input ref for " + kind.name()));
  }

  /**
   * Formats the shared halt evidence for the planner to read on a repair turn, with the rejected
   * plan alongside the rejection. {@code priorPlanMarkdown} is blank when the failed attempt never
   * produced a report, in which case the planner reads the complaint alone, as it did before the
   * halted attempt kept its output.
   */
  static String repairEvidenceText(StageRepairEvidence repair, String priorPlanMarkdown) {
    StringBuilder sb = new StringBuilder();
    if (repair.outcomeClass() != null && !repair.outcomeClass().isBlank()) {
      sb.append("- outcomeClass: ").append(repair.outcomeClass().trim()).append('\n');
    }
    if (repair.failedStageId() != null && !repair.failedStageId().isBlank()) {
      sb.append("- failedStageId: ").append(repair.failedStageId().trim()).append('\n');
    }
    if (repair.findings() != null && !repair.findings().isBlank()) {
      sb.append("- validationFindings:\n").append(repair.findings().trim()).append('\n');
    }
    if (repair.errorEvidence() != null && !repair.errorEvidence().isBlank()) {
      sb.append("- errorEvidence:\n").append(repair.errorEvidence().trim()).append('\n');
    }
    if (repair.haltFollowUpText() != null && !repair.haltFollowUpText().isBlank()) {
      sb.append("- authorFollowUp: ").append(repair.haltFollowUpText().trim()).append('\n');
    }
    if (priorPlanMarkdown != null && !priorPlanMarkdown.isBlank()) {
      sb.append("- rejectedPlan:\n").append(priorPlanMarkdown.trim()).append('\n');
    }
    return sb.toString().trim();
  }

  /** The API release an edit or a build targets, derived from the chain's language version. */
  public static String toApiRelease(String languageVersion) {
    if (languageVersion == null || languageVersion.isBlank()) {
      return "UNSPECIFIED";
    }
    String trimmed = languageVersion.trim();
    if (trimmed.matches("\\d{2}\\.\\d+")) {
      return "20" + trimmed;
    }
    return trimmed;
  }

  /**
   * Shows the planner the flow its report is checked against.
   *
   * <p>{@link DesignPlanProjector} rejects a report that names a participant outside the flow,
   * that covers no trigger, or that leaves an explicit mapping stage without a script step. The
   * prose IDS alone does not carry those names, so a planner given only the IDS has to guess them.
   */
  static String buildPlannerInput(IdsDocument ids, NormalizedDesignFlow flow, String release) {
    return """
        API release: %s
        Flow id: %s
        Chain name: %s

        %s

        %s
        """
        .formatted(release, flow.flowId(), flow.chainName(), describeFlow(flow), ids.markdown())
        .trim();
  }

  private static String describeFlow(NormalizedDesignFlow flow) {
    StringBuilder text = new StringBuilder();
    text.append("Normalized design flow. The plan is validated against it.\n\n");

    text.append("Binding resolution policy: ")
        .append(flow.bindingResolutionPolicy())
        .append("\n");
    if (flow.bindingResolutionPolicy()
        == NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_ONLY) {
      text.append(
          "APIHub lookup, APIHub specification retrieval, API import, and replacement API search "
              + "are forbidden for this flow. Reuse the existing catalog binding.\n");
    }

    text.append("Participants. Reference only these, by id or display name:\n");
    for (NormalizedDesignFlow.Participant participant : flow.participants()) {
      text.append("- ")
          .append(participant.participantId())
          .append(" (")
          .append(participant.displayName())
          .append(", ")
          .append(participant.systemType())
          .append(")\n");
    }

    NormalizedDesignFlow.Trigger trigger = flow.trigger();
    text.append("\nTrigger: ")
        .append(trigger.kind())
        .append(trigger.operationName() == null ? "" : " " + trigger.operationName())
        .append(trigger.endpointOrTopic() == null ? "" : " " + trigger.endpointOrTopic())
        .append(" from ")
        .append(trigger.sourceParticipantId())
        .append("\nThe trigger is not one of the steps below. Plan a step for it and give that step")
        .append(" cip-trigger-generator as an owning skill.\n");

    text.append("\nSteps:\n");
    for (NormalizedDesignFlow.Step step : flow.steps()) {
      text.append("- ")
          .append(step.stepId())
          .append(" (")
          .append(step.kind())
          .append(") ")
          .append(step.fromParticipantId())
          .append(" -> ")
          .append(step.toParticipantId())
          .append(": ")
          .append(step.operationQuery())
          .append("\n");
    }

    List<NormalizedDesignFlow.DataMapping> explicitMappings =
        flow.dataMappings().stream()
            .filter(mapping -> mapping.mode() == NormalizedDesignFlow.MappingMode.EXPLICIT)
            .toList();
    if (explicitMappings.isEmpty()) {
      text.append("\nNo explicit data mappings. Do not plan mapping scripts.\n");
    } else {
      text.append(
          "\nExplicit data mapping stages. Each needs a cip-script-generator step naming it:\n");
      for (NormalizedDesignFlow.DataMapping mapping : explicitMappings) {
        text.append("- ")
            .append(mapping.mappingId())
            .append(" ")
            .append(mapping.stage())
            .append(" ")
            .append(mapping.fromStepId())
            .append(" -> ")
            .append(mapping.toStepId())
            .append("\n");
        for (NormalizedDesignFlow.MappingRule rule : mapping.rules()) {
          text.append("  - ")
              .append(rule.sourcePath())
              .append(" -> ")
              .append(rule.targetPath());
          if (rule.expression() != null) {
            text.append(" | expression: ").append(rule.expression());
          }
          text.append('\n');
        }
      }
    }
    return text.toString();
  }
}
