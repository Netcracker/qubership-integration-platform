package org.qubership.integration.platform.ai.productpipeline.knowledge;

/** Stable knowledge sidecar failure codes. */
public enum KnowledgeFailureKind {
  KNOWLEDGE_INVALID_REQUEST(false),
  KNOWLEDGE_NOT_FOUND(false),
  KNOWLEDGE_ARTIFACT_UNAVAILABLE(false),
  KNOWLEDGE_PROFILE_MISMATCH(false),
  KNOWLEDGE_VERSION_MISMATCH(false),
  KNOWLEDGE_ENGINE_MISMATCH(false),
  KNOWLEDGE_INTEGRITY_FAILURE(false),
  KNOWLEDGE_PACKAGE_PIN_MISMATCH(false),
  KNOWLEDGE_TEMPORARILY_UNAVAILABLE(true),
  KNOWLEDGE_TRANSPORT_FAILURE(true),
  KNOWLEDGE_UNKNOWN(false);

  private final boolean retryable;

  KnowledgeFailureKind(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }

  public static KnowledgeFailureKind fromCode(String code) {
    if (code == null || code.isBlank()) {
      return KNOWLEDGE_UNKNOWN;
    }
    try {
      return KnowledgeFailureKind.valueOf(code.trim());
    } catch (IllegalArgumentException e) {
      return KNOWLEDGE_UNKNOWN;
    }
  }
}
