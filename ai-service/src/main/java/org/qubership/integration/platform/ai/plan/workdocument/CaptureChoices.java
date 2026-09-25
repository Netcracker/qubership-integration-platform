package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/** Server-supplied enums for one narrow capture. Java checks the same values after the call. */
public record CaptureChoices(
    List<String> sourceRefs, List<String> evidenceRefs, List<String> ruleIds, List<String> retainedIds) {

  public CaptureChoices {
    sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    retainedIds = retainedIds == null ? List.of() : List.copyOf(retainedIds);
  }

  public static CaptureChoices none() {
    return new CaptureChoices(List.of(), List.of(), List.of(), List.of());
  }
}
