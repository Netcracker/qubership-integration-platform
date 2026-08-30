package org.qubership.integration.platform.ai.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.failure.CatalogOperation;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;

/** Builds {@link OpenChainTurnContext} when a chain is open; otherwise returns null. */
@ApplicationScoped
public class OpenChainTurnContextFactory {

  private final ChainContextExtractor chainContextExtractor;
  private final ConversationService conversationService;
  private final PinnedFailureStore pinnedFailureStore;
  private final ChainCatalogFactsService chainCatalogFactsService;
  private final KnownFailureMapper knownFailureMapper;

  @Inject
  public OpenChainTurnContextFactory(
      ChainContextExtractor chainContextExtractor,
      ConversationService conversationService,
      PinnedFailureStore pinnedFailureStore,
      ChainCatalogFactsService chainCatalogFactsService,
      KnownFailureMapper knownFailureMapper) {
    this.chainContextExtractor = chainContextExtractor;
    this.conversationService = conversationService;
    this.pinnedFailureStore = pinnedFailureStore;
    this.chainCatalogFactsService = chainCatalogFactsService;
    this.knownFailureMapper = knownFailureMapper;
  }

  public OpenChainTurnContext build(ChatRequest request, String conversationId) {
    Optional<String> resolved = chainContextExtractor.resolveChainId(request, conversationId);
    if (resolved.isEmpty()) {
      return null;
    }
    String chainId = resolved.get();
    String window = TranscriptWindow.format(conversationService.getMessages(conversationId));
    Optional<ChainCatalogFacts> facts = Optional.empty();
    boolean factsUnavailable = false;
    try {
      facts = Optional.of(chainCatalogFactsService.load(chainId));
    } catch (RuntimeException error) {
      if (knownFailureMapper.tryMap(error, CatalogOperation.FACTS).isPresent()) {
        factsUnavailable = true;
      } else {
        throw error;
      }
    }
    return new OpenChainTurnContext(
        conversationId,
        chainId,
        request.getEffectiveUserText(),
        window,
        pinnedFailureStore.find(conversationId, chainId),
        facts,
        factsUnavailable);
  }
}
