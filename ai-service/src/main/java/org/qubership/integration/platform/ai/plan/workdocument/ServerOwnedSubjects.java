package org.qubership.integration.platform.ai.plan.workdocument;

/**
 * Subjects recovery may use before a child record exists. The model cannot invent another owner.
 */
public final class ServerOwnedSubjects {

  public static final String FLOW_POINTER = "/flow";
  public static final String OUTLINE_POINTER = "/data/outline";

  private ServerOwnedSubjects() {}

  public static WorkStage require(ChainWorkDocument document, String recordId, String pointer) {
    if (FLOW_POINTER.equals(pointer) && document.documentId().equals(recordId)) {
      return WorkStage.LOGICAL_FLOW;
    }
    if (OUTLINE_POINTER.equals(pointer) && hasStep(document, recordId)) {
      return WorkStage.DATA_BEHAVIOR;
    }
    throw new WorkDocumentRejectedException(
        "UNKNOWN_RECORD",
        "Record "
            + recordId
            + " with "
            + pointer
            + " is not a server-owned subject. Use the document /flow pointer or an existing step /data/outline pointer.");
  }

  private static boolean hasStep(ChainWorkDocument document, String stepId) {
    for (LogicalStep step : document.flow().steps()) {
      if (step.id().equals(stepId)) {
        return true;
      }
    }
    return false;
  }
}
