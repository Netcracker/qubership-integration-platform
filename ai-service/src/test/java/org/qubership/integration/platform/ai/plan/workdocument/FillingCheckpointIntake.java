package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/**
 * Initial source document for the filling checkpoint. WorkSource is package-private, so the
 * checkpoint package cannot construct it.
 */
public final class FillingCheckpointIntake {

  private FillingCheckpointIntake() {}

  public static WorkDocumentState sourceState(
      String documentId, String sourceId, String text, String hash, String name) {
    WorkSource source =
        new WorkSource(sourceId, "PRIMARY", "", hash, name, "", List.of(), text, List.of());
    return WorkDocumentState.create(documentId, List.of(source));
  }
}
