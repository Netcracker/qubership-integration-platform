package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;
import java.util.Map;

/** Result of a scoped apply. Publication onto a run is a later ticket. */
public record WorkCommit(
    String documentRevision,
    List<String> acceptedRecordIds,
    WorkOutcome outcome,
    String commandId,
    WorkDocumentState state,
    Map<String, String> aliasToId) {}
