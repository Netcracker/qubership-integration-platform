package org.qubership.integration.platform.ai.chat;

import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;

/** Shared window, pin, and catalog facts for one open-chain chat turn. */
public record OpenChainTurnContext(
    String conversationId,
    String chainId,
    String userMessage,
    String transcriptWindow,
    Optional<PinnedFailure> pinnedFailure,
    CatalogRead<ChainCatalogFacts> facts,
    CatalogRead<List<SnapshotDto>> snapshots,
    CatalogRead<List<DeploymentDto>> deployments,
    Optional<LastAssistantTurn> lastAssistantTurn) {

  /** Keeps direct scenario tests and older adapters on the original facts-only constructor. */
  public OpenChainTurnContext(
      String conversationId,
      String chainId,
      String userMessage,
      String transcriptWindow,
      Optional<PinnedFailure> pinnedFailure,
      Optional<ChainCatalogFacts> chainFacts,
      boolean factsUnavailable) {
    this(
        conversationId,
        chainId,
        userMessage,
        transcriptWindow,
        pinnedFailure,
        factsUnavailable
            ? CatalogRead.unavailable()
            : chainFacts.map(CatalogRead::available).orElseGet(CatalogRead::notRequested),
        CatalogRead.notRequested(),
        CatalogRead.notRequested(),
        Optional.empty());
  }

  public Optional<ChainCatalogFacts> chainFacts() {
    return facts.availableValue();
  }

  public boolean factsUnavailable() {
    return facts.state() == CatalogRead.State.UNAVAILABLE;
  }
}
