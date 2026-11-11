package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

/** Identifies a rejected semantic input before execution can produce a graph. */
public final class SemanticRevisionValidationException extends IllegalArgumentException {

  private final String fieldPointer;

  public SemanticRevisionValidationException(String message, String fieldPointer) {
    super(message);
    this.fieldPointer = fieldPointer;
  }

  /** An empty JSON Pointer identifies the whole revision. */
  public String fieldPointer() {
    return fieldPointer;
  }
}
