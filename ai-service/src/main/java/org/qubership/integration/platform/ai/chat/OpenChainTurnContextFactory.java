package org.qubership.integration.platform.ai.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.failure.CatalogOperation;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.AnswerShape;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.InfoNeed;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.TurnReferent;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlanner;
import org.qubership.integration.platform.ai.model.ScenarioType;

/** Builds {@link OpenChainTurnContext} when a chain is open; otherwise returns null. */
@ApplicationScoped
public class OpenChainTurnContextFactory {

  private final ChainContextExtractor chainContextExtractor;
  private final ConversationService conversationService;
  private final PinnedFailureStore pinnedFailureStore;
  private final ChainCatalogFactsService chainCatalogFactsService;
  private final KnownFailureMapper knownFailureMapper;
  private final CatalogRestClient catalogRestClient;
  private final OpenChainTurnPlanner turnPlanner;
  private final LastAssistantTurnStore lastAssistantTurnStore;

  @Inject
  public OpenChainTurnContextFactory(
      ChainContextExtractor chainContextExtractor,
      ConversationService conversationService,
      PinnedFailureStore pinnedFailureStore,
      ChainCatalogFactsService chainCatalogFactsService,
      KnownFailureMapper knownFailureMapper,
      @RestClient CatalogRestClient catalogRestClient,
      OpenChainTurnPlanner turnPlanner,
      LastAssistantTurnStore lastAssistantTurnStore) {
    this.chainContextExtractor = chainContextExtractor;
    this.conversationService = conversationService;
    this.pinnedFailureStore = pinnedFailureStore;
    this.chainCatalogFactsService = chainCatalogFactsService;
    this.knownFailureMapper = knownFailureMapper;
    this.catalogRestClient = catalogRestClient;
    this.turnPlanner = turnPlanner;
    this.lastAssistantTurnStore = lastAssistantTurnStore;
  }

  public OpenChainTurnContext build(ChatRequest request, String conversationId) {
    Optional<String> resolved = chainContextExtractor.resolveChainId(request, conversationId);
    if (resolved.isEmpty()) {
      return null;
    }
    String chainId = resolved.get();
    List<ConversationMessage> messages = conversationService.getMessages(conversationId);
    String window = TranscriptWindow.format(messages);
    Optional<LastAssistantTurn> lastTurn =
        lastAssistantTurnStore
            .find(conversationId, chainId)
            .or(() -> lastAssistantMessage(messages));

    OpenChainTurnPlan plan = plan(request, window, lastTurn);
    if (plan != null) {
      request.setOpenChainTurnPlan(plan);
      request.setScenarioHint(plan.scenario());
    }

    Set<InfoNeed> needs = needs(plan);
    CompletableFuture<CatalogRead<ChainCatalogFacts>> facts =
        readAsync(
            needs.contains(InfoNeed.FACTS),
            () -> chainCatalogFactsService.load(chainId),
            CatalogOperation.FACTS);
    CompletableFuture<CatalogRead<List<SnapshotDto>>> snapshots =
        readAsync(
            needs.contains(InfoNeed.SNAPSHOTS),
            () -> copy(catalogRestClient.listSnapshots(chainId)),
            CatalogOperation.SNAPSHOT);
    CompletableFuture<CatalogRead<List<DeploymentDto>>> deployments =
        readAsync(
            needs.contains(InfoNeed.DEPLOYMENTS),
            () -> copy(catalogRestClient.listDeployments(chainId)),
            CatalogOperation.STATUS);

    return new OpenChainTurnContext(
        conversationId,
        chainId,
        request.getEffectiveUserText(),
        window,
        pinnedFailureStore.find(conversationId, chainId),
        join(facts),
        join(snapshots),
        join(deployments),
        lastTurn);
  }

  private OpenChainTurnPlan plan(
      ChatRequest request, String transcriptWindow, Optional<LastAssistantTurn> lastTurn) {
    if (request.getDecision() != null) {
      return null;
    }
    ScenarioType hint = request.getScenarioHint();
    if (hint == ScenarioType.ASK_CHAIN) {
      return new OpenChainTurnPlan.Ask(
          TurnReferent.OPEN_CHAIN, Set.of(InfoNeed.FACTS), AnswerShape.EXPLAIN);
    }
    if (hint == ScenarioType.COMPARE_AND_PATCH || hint == ScenarioType.DEPLOY_CHAIN) {
      return null;
    }
    OpenChainTurnPlanner.Capture capture =
        turnPlanner.plan(
            formatLastTurn(lastTurn),
            transcriptWindow,
            request.getEffectiveUserText() == null ? "" : request.getEffectiveUserText());
    return OpenChainTurnPlanner.validate(capture);
  }

  private static Set<InfoNeed> needs(OpenChainTurnPlan plan) {
    if (plan instanceof OpenChainTurnPlan.Ask ask) {
      return ask.needs();
    }
    if (plan instanceof OpenChainTurnPlan.Patch) {
      return Set.of(InfoNeed.FACTS);
    }
    return Set.of();
  }

  private <T> CompletableFuture<CatalogRead<T>> readAsync(
      boolean requested, Supplier<T> read, CatalogOperation operation) {
    if (!requested) {
      return CompletableFuture.completedFuture(CatalogRead.notRequested());
    }
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return CatalogRead.available(read.get());
          } catch (RuntimeException error) {
            if (knownFailureMapper.tryMap(error, operation).isPresent()) {
              return CatalogRead.unavailable();
            }
            throw error;
          }
        });
  }

  private static <T> CatalogRead<T> join(CompletableFuture<CatalogRead<T>> read) {
    try {
      return read.join();
    } catch (CompletionException error) {
      if (error.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw error;
    }
  }

  private static <T> List<T> copy(List<T> listed) {
    return listed == null ? List.of() : List.copyOf(listed);
  }

  private static Optional<LastAssistantTurn> lastAssistantMessage(
      List<ConversationMessage> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      ConversationMessage message = messages.get(i);
      if (message.role() == ConversationMessage.Role.ASSISTANT) {
        return Optional.of(new LastAssistantTurn(LastAssistantTurn.Kind.OTHER, message.content()));
      }
    }
    return Optional.empty();
  }

  private static String formatLastTurn(Optional<LastAssistantTurn> lastTurn) {
    if (lastTurn.isEmpty()) {
      return "kind: OTHER\ntext: (none)";
    }
    LastAssistantTurn turn = lastTurn.get();
    return "kind: " + turn.kind() + "\ntext: " + turn.text();
  }
}
