package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/** Requirement coverage and transfer inventory stored under one target step. */
public record DataOutline(
    List<String> requirementIds, List<String> transferIds, List<CoverageEntry> coverage) {

  public DataOutline {
    requirementIds = Lists.copy(requirementIds);
    transferIds = Lists.copy(transferIds);
    coverage = Lists.copy(coverage);
  }

  public static DataOutline empty() {
    return new DataOutline(List.of(), List.of(), List.of());
  }
}

record CoverageEntry(String requirementId, String passageId, CoverageDisposition disposition) {

  public CoverageEntry {
    requirementId = requirementId == null ? "" : requirementId;
    passageId = passageId == null ? "" : passageId;
    disposition = disposition == null ? CoverageDisposition.ASSIGNED : disposition;
  }
}
