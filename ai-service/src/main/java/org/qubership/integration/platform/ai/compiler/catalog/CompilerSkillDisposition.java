package org.qubership.integration.platform.ai.compiler.catalog;

/** Backend disposition for a compiler skill discovered from the production compiler package. */
public enum CompilerSkillDisposition {
  PUBLIC_RUNTIME,
  VALIDATOR,
  BUILD_TIME,
  PRIVATE,
  SPECIFICATION_ONLY,
  UNSUPPORTED
}
