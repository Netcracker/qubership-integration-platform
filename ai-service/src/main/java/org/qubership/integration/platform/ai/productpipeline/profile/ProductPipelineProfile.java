package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.List;
import java.util.Locale;

/** Immutable declarative profile for a durable sequential product pipeline. */
public record ProductPipelineProfile(
    int schemaVersion,
    String profileId,
    String profileVersion,
    List<ArtifactTypeRef> runInputs,
    List<ProfileStage> stages,
    TerminalPolicy terminal,
    List<String> dependencyRoots,
    CompilerPipelinePolicy compilerPipeline,
    ImplementationGatePolicy implementationGate) {

  public ProductPipelineProfile {
    runInputs = runInputs == null ? List.of() : List.copyOf(runInputs);
    stages = stages == null ? List.of() : List.copyOf(stages);
    dependencyRoots = dependencyRoots == null ? List.of() : List.copyOf(dependencyRoots);
  }

  /** Compatibility constructor for profiles without compiler or implementation-gate policies. */
  public ProductPipelineProfile(
      int schemaVersion,
      String profileId,
      String profileVersion,
      List<ArtifactTypeRef> runInputs,
      List<ProfileStage> stages,
      TerminalPolicy terminal,
      List<String> dependencyRoots) {
    this(
        schemaVersion,
        profileId,
        profileVersion,
        runInputs,
        stages,
        terminal,
        dependencyRoots,
        null,
        null);
  }

  /** Compatibility constructor for profiles without an implementation-gate policy. */
  public ProductPipelineProfile(
      int schemaVersion,
      String profileId,
      String profileVersion,
      List<ArtifactTypeRef> runInputs,
      List<ProfileStage> stages,
      TerminalPolicy terminal,
      List<String> dependencyRoots,
      CompilerPipelinePolicy compilerPipeline) {
    this(
        schemaVersion,
        profileId,
        profileVersion,
        runInputs,
        stages,
        terminal,
        dependencyRoots,
        compilerPipeline,
        null);
  }

  /** Whether {@code stageId} lists {@code artifactType} in its mandatory consumes (type match only). */
  public boolean stageConsumes(String stageId, String artifactType) {
    if (stageId == null || artifactType == null) {
      return false;
    }
    String normalizedType = artifactType.trim().toLowerCase(Locale.ROOT);
    for (ProfileStage stage : stages) {
      if (!stageId.equals(stage.stageId())) {
        continue;
      }
      for (ArtifactTypeRef ref : stage.consumes()) {
        if (ref != null
            && ref.type() != null
            && normalizedType.equals(ref.type().trim().toLowerCase(Locale.ROOT))) {
          return true;
        }
      }
      return false;
    }
    return false;
  }

  /** create-chain@2+ design-execution materialization (Phase 6) contract. */
  public boolean usesDesignExecutionMaterializationContract() {
    return stageConsumes("materialization", "materialization-request");
  }

  /** create-chain@1 receives every committed reference; every other version receives only declared consumes. */
  public boolean usesDeclaredStageInputs() {
    return !"1".equals(profileVersion);
  }
}
