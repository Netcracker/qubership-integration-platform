package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Executes the pinned design-planner skill and returns its structured capture. */
public interface DesignPlanSkillRunner {

  Result runOnce(
      String conversationId,
      String input,
      Optional<String> formatFailure,
      Optional<String> repairEvidence,
      String pinnedSkillHash,
      String apiRelease,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin);

  record Result(
      DesignPlanContract contract,
      String rawResponse,
      String rejection,
      java.util.List<DesignPlanContractFinding> findings,
      boolean terminal) {

    public Result(DesignPlanContract contract, String rawResponse, String rejection) {
      this(contract, rawResponse, rejection, java.util.List.of(), false);
    }

    public Result(DesignPlanContract contract, String rawResponse, String rejection,
        java.util.List<DesignPlanContractFinding> findings) {
      this(contract, rawResponse, rejection, findings, false);
    }

    public Result {
      findings = findings == null ? java.util.List.of() : java.util.List.copyOf(findings);
    }
  }
}
