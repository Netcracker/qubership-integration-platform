package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;
import java.util.Map;

/**
 * Result of a scoped apply. {@code runRevision} is set after the run commit succeeds and stays
 * null for an in-memory apply.
 */
public record WorkCommit(
    String documentRevision,
    List<String> acceptedRecordIds,
    WorkOutcome outcome,
    String commandId,
    WorkDocumentState state,
    Map<String, String> aliasToId,
    Long runRevision) {

  public WorkCommit(
      String documentRevision,
      List<String> acceptedRecordIds,
      WorkOutcome outcome,
      String commandId,
      WorkDocumentState state,
      Map<String, String> aliasToId) {
    this(documentRevision, acceptedRecordIds, outcome, commandId, state, aliasToId, null);
  }
}
