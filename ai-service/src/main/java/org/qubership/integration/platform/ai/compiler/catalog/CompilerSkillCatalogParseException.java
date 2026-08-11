package org.qubership.integration.platform.ai.compiler.catalog;

/** Raised when production compiler skill catalog YAML cannot be parsed. */
public class CompilerSkillCatalogParseException extends RuntimeException {

  public CompilerSkillCatalogParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
