package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.List;

/** Immutable build-time report comparing a certified pipeline index to a candidate. */
public record PipelineCompatibilityReport(
    int schemaVersion,
    String previousCompilerDigest,
    String candidateCompilerDigest,
    PipelineChangeClass changeClass,
    List<String> changedNodes,
    List<String> changedDependencies,
    List<String> changedPhases,
    List<String> changedArtifactContracts,
    List<String> compatibleProfileVersions,
    List<String> requiredGates,
    boolean activationAllowed,
    List<String> blockingFindings) {

  public static final int SCHEMA_VERSION = 1;

  public PipelineCompatibilityReport {
    changedNodes = changedNodes == null ? List.of() : List.copyOf(changedNodes);
    changedDependencies =
        changedDependencies == null ? List.of() : List.copyOf(changedDependencies);
    changedPhases = changedPhases == null ? List.of() : List.copyOf(changedPhases);
    changedArtifactContracts =
        changedArtifactContracts == null ? List.of() : List.copyOf(changedArtifactContracts);
    compatibleProfileVersions =
        compatibleProfileVersions == null ? List.of() : List.copyOf(compatibleProfileVersions);
    requiredGates = requiredGates == null ? List.of() : List.copyOf(requiredGates);
    blockingFindings = blockingFindings == null ? List.of() : List.copyOf(blockingFindings);
  }
}
