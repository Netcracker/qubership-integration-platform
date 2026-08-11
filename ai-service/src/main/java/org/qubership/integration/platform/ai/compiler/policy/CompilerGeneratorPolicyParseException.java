package org.qubership.integration.platform.ai.compiler.policy;

/** Raised when compiler pack docs cannot be parsed into a generator policy. */
public class CompilerGeneratorPolicyParseException extends RuntimeException {

  public CompilerGeneratorPolicyParseException(String message) {
    super(message);
  }

  public CompilerGeneratorPolicyParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
