package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/** One pinned compiler capability node from the selected run closure. */
public record ResolvedCompilerNode(
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
    int topologicalLevel,
    int stableTieBreaker,
    boolean mandatory,
    CompilerNodeExecutionMode executionMode,
    String adapterId,
    GraphPatchOwnershipPolicy ownership) {

  public ResolvedCompilerNode {
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

  public ResolvedCompilerNode(
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
        topologicalLevel,
        stableTieBreaker,
        mandatory,
        executionMode,
        adapterId,
        GraphPatchOwnershipPolicy.denyAll());
  }
}
