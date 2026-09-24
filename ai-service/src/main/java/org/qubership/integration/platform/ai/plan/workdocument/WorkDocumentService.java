package org.qubership.integration.platform.ai.plan.workdocument;

/** In-memory boundary. Run lookup and publication stay with the run store. */
public final class WorkDocumentService {

  private final WorkDocumentEditor editor = new WorkDocumentEditor();

  public WorkDocumentState read(WorkDocumentState state) {
    return state;
  }

  public WorkCommit apply(
      WorkDocumentState state, WorkTaskScope taskScope, WorkTaskCapture capture, String commandId) {
    return editor.apply(state, taskScope, capture, commandId);
  }

  public WorkDocumentState attachResolvedBinding(
      WorkDocumentState state, String stepId, ResolvedWorkBinding binding) {
    return editor.attachResolvedBinding(state, stepId, binding);
  }
}
