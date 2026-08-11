package org.qubership.integration.platform.ai.chain.presentation;

/** Short user-facing summary after a chain is materialized into the catalog. */
public final class ChainMaterializedSummary {

  private ChainMaterializedSummary() {}

  /**
   * Deterministic English summary with name, id, and a markdown graph link. Relative path works
   * inside the Integration Platform UI (same link shape as ids.md download).
   */
  public static String format(ChainCatalogFacts facts) {
    if (facts == null || facts.chainId() == null || facts.chainId().isBlank()) {
      return "Chain is ready.";
    }
    String name =
        facts.chainName() == null || facts.chainName().isBlank()
            ? facts.chainId()
            : facts.chainName().trim();
    String openPath = "/chains/" + facts.chainId() + "/graph";
    return """
        Chain "%s" is ready.
        Id: %s
        [Open graph](%s)
        """
        .formatted(name, facts.chainId(), openPath)
        .trim();
  }
}
