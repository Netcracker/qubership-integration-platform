package org.qubership.integration.platform.ai.plan.mapping;

import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

/**
 * One mapping-contract violation produced by {@link
 * org.qubership.integration.platform.ai.plan.BriefMappingValidator}. Artifact identity is filled by
 * the caller; this type stays free of the run store.
 */
public record MappingRuleFinding(
    MappingFindingCode code,
    String message,
    boolean blocker,
    String mappingIntentId,
    String sourceRef,
    MappingPort sourcePort,
    String targetRef,
    MappingPort targetPort,
    String sourcePath,
    String targetPath,
    String expression,
    String ruleStatus,
    String expectedContract,
    String observed) {

  public MappingRuleFinding {
    code = code == null ? MappingFindingCode.MAPPING_UNRESOLVED_RULE : code;
    message = message == null ? "" : message;
    mappingIntentId = mappingIntentId == null ? "" : mappingIntentId;
    sourceRef = sourceRef == null ? "" : sourceRef;
    targetRef = targetRef == null ? "" : targetRef;
    sourcePath = sourcePath == null ? "" : sourcePath;
    targetPath = targetPath == null ? "" : targetPath;
    expression = expression == null ? "" : expression;
    ruleStatus = ruleStatus == null ? "" : ruleStatus;
    expectedContract = expectedContract == null ? "" : expectedContract;
    observed = observed == null ? "" : observed;
  }
}
