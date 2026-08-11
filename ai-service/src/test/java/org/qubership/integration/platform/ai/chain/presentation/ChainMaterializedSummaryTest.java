package org.qubership.integration.platform.ai.chain.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChainMaterializedSummaryTest {

  @Test
  void formatsNameIdAndMarkdownOpenLink() {
    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-abc",
            "HealthProxy",
            "",
            3,
            1,
            "",
            List.of(),
            List.of(),
            "built_in_catalog");

    String summary = ChainMaterializedSummary.format(facts);

    assertEquals(
        """
        Chain "HealthProxy" is ready.
        Id: chain-abc
        [Open graph](/chains/chain-abc/graph)""".trim(),
        summary);
  }

  @Test
  void fallsBackToChainIdWhenNameBlank() {
    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-xyz", "", "", 0, 0, "", List.of(), List.of(), "built_in_catalog");

    String summary = ChainMaterializedSummary.format(facts);

    assertTrue(summary.contains("Chain \"chain-xyz\" is ready."));
    assertTrue(summary.contains("[Open graph](/chains/chain-xyz/graph)"));
  }
}
