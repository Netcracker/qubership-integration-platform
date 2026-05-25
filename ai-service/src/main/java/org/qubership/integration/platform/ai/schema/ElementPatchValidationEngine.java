package org.qubership.integration.platform.ai.schema;

/** Element patch JSON Schema validation backend. */
public enum ElementPatchValidationEngine {
  LEGACY,
  NETWORKNT;

  public static ElementPatchValidationEngine fromConfig(String engine) {
    if (engine == null || engine.isBlank()) {
      return LEGACY;
    }
    return "networknt".equalsIgnoreCase(engine.trim()) ? NETWORKNT : LEGACY;
  }
}
