package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackRepository;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Builds the validated plan from approved targets without requesting optional model descriptions. */
@ApplicationScoped
public class DefaultDesignPlanSkillRunner implements DesignPlanSkillRunner {

  private final CipKnowledgePackRepository repository;

  @Inject
  public DefaultDesignPlanSkillRunner(CipKnowledgePackRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public Result runOnce(
      String conversationId,
      String input,
      Optional<String> formatFailure,
      Optional<String> repairEvidence,
      String pinnedSkillHash,
      String apiRelease,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin) {
    String actualHash = repository.loadManifest().addonSha256().get(CipDesignPlannerAdapter.SKILL_ID);
    if (actualHash == null || !pinnedSkillHash.equals(actualHash)) {
      throw new PlannerContractException(
          "pinned skill hash mismatch for " + CipDesignPlannerAdapter.SKILL_ID
              + ": expected " + pinnedSkillHash + " but was " + actualHash);
    }
    var contract = new DesignPlanCaptureAdapter().adapt(
        new DesignPlanCapture(List.of()), revision, brief, pin, apiRelease);
    return new Result(contract, "", null);
  }
}
