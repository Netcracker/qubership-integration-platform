package org.qubership.integration.platform.ai.chat;

import java.util.Optional;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;

/** Shared window, pin, and catalog facts for one open-chain chat turn. */
public record OpenChainTurnContext(
    String conversationId,
    String chainId,
    String userMessage,
    String transcriptWindow,
    Optional<PinnedFailure> pinnedFailure,
    Optional<ChainCatalogFacts> chainFacts,
    boolean factsUnavailable) {}
