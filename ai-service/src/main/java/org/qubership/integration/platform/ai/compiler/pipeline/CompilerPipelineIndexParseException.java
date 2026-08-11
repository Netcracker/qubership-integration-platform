package org.qubership.integration.platform.ai.compiler.pipeline;

/** Raised when compiler pipeline metadata cannot be converted to the canonical index. */
public class CompilerPipelineIndexParseException extends RuntimeException {

  public CompilerPipelineIndexParseException(String message) {
    super(message);
  }
}
