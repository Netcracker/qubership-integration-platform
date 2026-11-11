package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/**
 * One open corrective assignment. The cause stays on the original finding. The responsible task
 * and the consumer that must confirm the repair are stored separately.
 */
public record RepairAssignment(
    String findingId,
    String causeKey,
    String recordRef,
    String issueCategory,
    String fieldPointer,
    String contradiction,
    String detectionTaskKey,
    String consumerTaskKey,
    WorkTaskKind responsibleKind,
    String responsibleRecordId,
    List<String> updateIds,
    List<String> createParentIds,
    String candidateRevision,
    String phase) {

  public static final String OWNER = "OWNER";
  public static final String VERIFY = "VERIFY";

  public RepairAssignment {
    findingId = text(findingId);
    causeKey = text(causeKey);
    recordRef = text(recordRef);
    issueCategory = text(issueCategory);
    fieldPointer = text(fieldPointer);
    contradiction = text(contradiction);
    detectionTaskKey = text(detectionTaskKey);
    consumerTaskKey = text(consumerTaskKey);
    responsibleKind = responsibleKind == null ? WorkTaskKind.UNSPECIFIED : responsibleKind;
    responsibleRecordId = text(responsibleRecordId);
    updateIds = Lists.copy(updateIds);
    createParentIds = Lists.copy(createParentIds);
    candidateRevision = text(candidateRevision);
    phase = text(phase).isBlank() ? OWNER : phase;
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
