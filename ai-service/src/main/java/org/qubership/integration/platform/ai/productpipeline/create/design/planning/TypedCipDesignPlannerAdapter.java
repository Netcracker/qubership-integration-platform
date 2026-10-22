package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Invokes the pinned planner and accepts only a validated typed capture. */
public final class TypedCipDesignPlannerAdapter {

  private final DesignPlanSkillRunner runner;

  public TypedCipDesignPlannerAdapter(DesignPlanSkillRunner runner) {
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  public Result plan(
      PlannerRequest request,
      String apiRelease,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin) {
    Optional<String> repair =
        request.repairEvidenceText().isBlank()
            ? Optional.empty()
            : Optional.of(request.repairEvidenceText());
    DesignPlanSkillRunner.Result first =
        runner.runOnce(
            request.conversationId(),
            request.input(),
            Optional.empty(),
            repair,
            request.pinnedSkillHash(),
            apiRelease,
            revision,
            brief,
            pin);
    if (first.contract() != null) {
      return new Result(first.contract(), first.rawResponse());
    }
    if (first.terminal()) {
      throw new PlannerContractException(rejection(first), first.findings());
    }
    String firstFailure = rejection(first);
    DesignPlanSkillRunner.Result second =
        runner.runOnce(
            request.conversationId(),
            request.input(),
            Optional.of(firstFailure),
            repair,
            request.pinnedSkillHash(),
            apiRelease,
            revision,
            brief,
            pin);
    if (second.contract() != null) {
      return new Result(second.contract(), second.rawResponse());
    }
    java.util.List<DesignPlanContractFinding> findings = second.findings();
    if (findings.isEmpty()) {
      findings =
          java.util.List.of(
              new DesignPlanContractFinding(
                  DesignPlanContractFinding.Code.CAPTURE_MISSING,
                  null,
                  "",
                  "",
                  true,
                  rejection(second)));
    }
    throw new PlannerContractException(
        "cip-design-planner typed capture failed after one correction: " + rejection(second),
        findings);
  }

  private static String rejection(DesignPlanSkillRunner.Result result) {
    if (result.rejection() != null && !result.rejection().isBlank()) {
      return result.rejection();
    }
    return "captureDesignPlan was not called";
  }

  public record Result(DesignPlanContract contract, String rawResponse) {}
}
