package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/** One runtime capability node in compiler pipeline index schema v2. */
public record CompilerPipelineNode(
    String skillId,
    String compilerPhase,
    String generatorId,
    List<String> consumes,
    List<String> produces,
    List<String> dependsOn,
    String captureTool,
    List<String> applicabilitySignals,
    List<String> readinessSignals,
    boolean runtimeReady,
    List<String> runtimeReadinessFindings,
    String skillSha256,
    String addonSha256,
    int topologicalLevel,
    int stableTieBreaker,
    boolean mandatory,
    CompilerNodeExecutionMode executionMode,
    String adapterId,
    GraphPatchOwnershipPolicy ownership) {

  public CompilerPipelineNode {
    consumes = consumes == null ? List.of() : List.copyOf(consumes);
    produces = produces == null ? List.of() : List.copyOf(produces);
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    applicabilitySignals =
        applicabilitySignals == null ? List.of() : List.copyOf(applicabilitySignals);
    readinessSignals = readinessSignals == null ? List.of() : List.copyOf(readinessSignals);
    runtimeReadinessFindings =
        runtimeReadinessFindings == null ? List.of() : List.copyOf(runtimeReadinessFindings);
    ownership = ownership == null ? GraphPatchOwnershipPolicy.denyAll() : ownership;
  }

  public CompilerPipelineNode(
      String skillId,
      String compilerPhase,
      String generatorId,
      List<String> consumes,
      List<String> produces,
      List<String> dependsOn,
      String captureTool,
      List<String> applicabilitySignals,
      List<String> readinessSignals,
      boolean runtimeReady,
      List<String> runtimeReadinessFindings,
      String skillSha256,
      String addonSha256,
      int topologicalLevel,
      int stableTieBreaker,
      boolean mandatory,
      CompilerNodeExecutionMode executionMode,
      String adapterId) {
    this(
        skillId,
        compilerPhase,
        generatorId,
        consumes,
        produces,
        dependsOn,
        captureTool,
        applicabilitySignals,
        readinessSignals,
        runtimeReady,
        runtimeReadinessFindings,
        skillSha256,
        addonSha256,
        topologicalLevel,
        stableTieBreaker,
        mandatory,
        executionMode,
        adapterId,
        GraphPatchOwnershipPolicy.denyAll());
  }
}
