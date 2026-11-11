package org.qubership.integration.platform.ai.plan.workdocument;

/** A capture was rejected before the document changed. */
public final class WorkDocumentRejectedException extends RuntimeException {

  private final String code;

  public WorkDocumentRejectedException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
