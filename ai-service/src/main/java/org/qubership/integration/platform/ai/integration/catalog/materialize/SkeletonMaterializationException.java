package org.qubership.integration.platform.ai.integration.catalog.materialize;

/** Element skeleton materialization failed after chain creation. */
public final class SkeletonMaterializationException extends RuntimeException {

  private final String chainId;
  private final boolean chainDeleted;

  public SkeletonMaterializationException(String chainId, boolean chainDeleted, Throwable cause) {
    super(cause);
    this.chainId = requireChainId(chainId);
    this.chainDeleted = chainDeleted;
  }

  public String chainId() {
    return chainId;
  }

  public boolean chainDeleted() {
    return chainDeleted;
  }

  private static String requireChainId(String chainId) {
    if (chainId == null || chainId.isBlank()) {
      throw new IllegalArgumentException("chainId is required");
    }
    return chainId;
  }
}
