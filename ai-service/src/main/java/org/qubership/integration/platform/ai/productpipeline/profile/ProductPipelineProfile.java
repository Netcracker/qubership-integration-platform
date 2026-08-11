package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.List;

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
}
