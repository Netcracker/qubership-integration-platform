package org.qubership.integration.platform.ai.productpipeline.knowledge;

/** Non-checked knowledge client failure with stable classification. */
public final class KnowledgeClientException extends RuntimeException {
  private final KnowledgeFailureKind kind;

  public KnowledgeClientException(KnowledgeFailureKind kind, String message) {
    super(message);
    this.kind = kind == null ? KnowledgeFailureKind.KNOWLEDGE_UNKNOWN : kind;
  }

  public KnowledgeClientException(KnowledgeFailureKind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind == null ? KnowledgeFailureKind.KNOWLEDGE_UNKNOWN : kind;
  }

  public KnowledgeFailureKind kind() {
    return kind;
  }

  public boolean retryable() {
    return kind.retryable();
  }
}
