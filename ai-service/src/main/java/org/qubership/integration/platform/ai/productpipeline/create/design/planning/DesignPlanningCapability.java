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
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
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

  @Inject
  public DesignPlanningCapability(DesignProcessSkillRunner runner) {
    this(
        new CipDesignPlannerAdapter(runner, new CipDesignPlannerReportParser()),
        new DesignPlanProjector(),
        new DesignImplementationPlanRenderer());
  }

  DesignPlanningCapability(
      CipDesignPlannerAdapter planner,
      DesignPlanProjector projector,
      DesignImplementationPlanRenderer renderer) {
    this.planner = Objects.requireNonNull(planner, "planner");
    this.projector = Objects.requireNonNull(projector, "projector");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    // Planner agent blocks; must not run on the Vert.x event loop.
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(CipDesignPlannerAdapter.SKILL_ID)),
            Uni.createFrom()
                .item(
                    () -> {
                      SkillActivitySupport.bindParents(CipDesignPlannerAdapter.SKILL_ID);
                      try {
                        return SkillActivitySupport.wrapTerminal(
                            CipDesignPlannerAdapter.SKILL_ID, List.of(executeBlocking(context)));
                      } finally {
                        SkillActivitySupport.clearParents();
                      }
                    })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem()
                .transformToMulti(signals -> Multi.createFrom().iterable(signals)));
  }

  private CapabilitySignal.Completed executeBlocking(StageExecutionContext context) {
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
      DesignPlanReport report =
          planner.plan(
              new PlannerRequest(
                  context.conversationId(),
                  buildPlannerInput(ids, flow, release),
                  pinnedSkillHash));
      DesignExecutionPlan projection =
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
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              ex.outcomeClass() == null ? StageOutcomeClass.CONTRACT_FAILURE : ex.outcomeClass(),
              ex.getMessage()));
    } catch (RuntimeException ex) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, ex.getMessage()));
    }
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

  static String toApiRelease(String languageVersion) {
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
   * that covers no trigger, or that leaves a mapping stage without a script step. The prose IDS
   * alone does not carry those names, so a planner given only the IDS has to guess them.
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

    if (flow.dataMappings().isEmpty()) {
      text.append("\nNo data mappings. Do not plan mapping scripts.\n");
    } else {
      text.append("\nData mapping stages. Each needs a cip-script-generator step naming it:\n");
      for (NormalizedDesignFlow.DataMapping mapping : flow.dataMappings()) {
        text.append("- ").append(mapping.stage()).append("\n");
      }
    }
    return text.toString();
  }
}
