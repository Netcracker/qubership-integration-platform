package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.Set;
import java.util.TreeSet;

public final class PlanningSkillArtifactUnavailableException
    extends RuntimeException {

  private final String skillId;
  private final Set<String> missingArtifactTypes;

  public PlanningSkillArtifactUnavailableException(
      String skillId,
      Set<String> missingArtifactTypes,
      Throwable cause) {
    super(
        "Planning stopped because skill '"
            + skillId
            + "' did not produce required artifacts: "
            + new TreeSet<>(missingArtifactTypes)
            + ". Retry the planning stage; downstream generators were not started.",
        cause);
    this.skillId = skillId;
    this.missingArtifactTypes =
        Set.copyOf(new TreeSet<>(missingArtifactTypes));
  }

  public String skillId() {
    return skillId;
  }

  public Set<String> missingArtifactTypes() {
    return missingArtifactTypes;
  }
}
