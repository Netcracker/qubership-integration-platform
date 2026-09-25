package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/** Server-supplied enums for one narrow capture. Java checks the same values after the call. */
public record CaptureChoices(
    List<String> sourceRefs,
    List<String> evidenceRefs,
    List<String> ruleIds,
    List<String> retainedIds,
    List<String> stepIds,
    List<String> sourcePorts,
    List<String> targetPorts) {

  public CaptureChoices {
    sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    retainedIds = retainedIds == null ? List.of() : List.copyOf(retainedIds);
    stepIds = stepIds == null ? List.of() : List.copyOf(stepIds);
    sourcePorts = sourcePorts == null ? List.of() : List.copyOf(sourcePorts);
    targetPorts = targetPorts == null ? List.of() : List.copyOf(targetPorts);
  }

  public CaptureChoices(
      List<String> sourceRefs, List<String> evidenceRefs, List<String> ruleIds, List<String> retainedIds) {
    this(sourceRefs, evidenceRefs, ruleIds, retainedIds, List.of(), List.of(), List.of());
  }

  public static CaptureChoices none() {
    return new CaptureChoices(List.of(), List.of(), List.of(), List.of());
  }
}
