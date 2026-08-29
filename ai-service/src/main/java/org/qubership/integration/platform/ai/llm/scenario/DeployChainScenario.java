package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.model.ScenarioType;

@ApplicationScoped
@ForScenario(ScenarioType.DEPLOY_CHAIN)
public class DeployChainScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(DeployChainScenario.class);

  private static final String NO_CHAIN_MESSAGE =
      "No chain context found. Open a chain in the UI or implement a chain first,"
          + " then ask for a snapshot.";

  private static final String SNAPSHOT_ONLY_MESSAGE =
      "I can take a snapshot of this chain. Deploy, undeploy, and deployment status"
          + " are not available.";

  private final ChainContextExtractor chainContextExtractor;
  private final CatalogRestClient catalogRestClient;
  private final ObjectMapper objectMapper;

  @Inject
  public DeployChainScenario(
      ChainContextExtractor chainContextExtractor,
      @RestClient CatalogRestClient catalogRestClient,
      ObjectMapper objectMapper) {
    this.chainContextExtractor = chainContextExtractor;
    this.catalogRestClient = catalogRestClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public Multi<ChatEvent> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    String userMessage = request != null ? request.getEffectiveUserText() : "";
    String chainId =
        chainContextExtractor.resolveChainId(request, conversationId).orElse(null);

    if (chainId == null) {
      LOG.infof("DEPLOY_CHAIN without chain context conversationId=%s", conversationId);
      return Multi.createFrom().item(ChatEvent.token(NO_CHAIN_MESSAGE));
    }

    if (!UserIntentPatterns.matchesSnapshotIntent(userMessage)) {
      LOG.infof(
          "DEPLOY_CHAIN non-snapshot turn conversationId=%s chainId=%s", conversationId, chainId);
      return Multi.createFrom().item(ChatEvent.token(SNAPSHOT_ONLY_MESSAGE));
    }

    LOG.infof("DEPLOY_CHAIN snapshot conversationId=%s chainId=%s", conversationId, chainId);
    try {
      SnapshotDto snapshot = catalogRestClient.createSnapshot(chainId);
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  "Created catalog snapshot "
                      + snapshot.name()
                      + " (id: "
                      + snapshot.id()
                      + ")."));
    } catch (CatalogNonRetryableResponseException e) {
      LOG.warnf(
          e,
          "DEPLOY_CHAIN snapshot refused conversationId=%s chainId=%s",
          conversationId,
          chainId);
      return Multi.createFrom().item(ChatEvent.error(formatCatalogRefusal(e)));
    } catch (RuntimeException e) {
      LOG.errorf(
          e, "DEPLOY_CHAIN snapshot failed conversationId=%s chainId=%s", conversationId, chainId);
      return Multi.createFrom()
          .item(ChatEvent.error("Failed to create a catalog snapshot: " + e.getMessage()));
    }
  }

  private String formatCatalogRefusal(CatalogNonRetryableResponseException e) {
    String body = CatalogRestSupport.readResponseBodySnippet(e.getResponse());
    if (body == null || body.isBlank()) {
      return "Catalog could not create a snapshot.";
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      String errorMessage = root.path("errorMessage").asText("");
      JsonNode details = root.path("details");
      String elementName = details.path("elementName").asText("");
      String elementId = details.path("elementId").asText("");
      StringBuilder reply = new StringBuilder("Catalog could not create a snapshot");
      if (!errorMessage.isBlank()) {
        reply.append(": ").append(errorMessage);
      }
      if (!elementName.isBlank() || !elementId.isBlank()) {
        reply.append(". Element");
        if (!elementName.isBlank()) {
          reply.append(" ").append(elementName);
        }
        if (!elementId.isBlank()) {
          reply.append(" (").append(elementId).append(")");
        }
      }
      reply.append(".");
      return reply.toString();
    } catch (Exception parseFailed) {
      LOG.warnf(parseFailed, "DEPLOY_CHAIN could not parse catalog 400 body");
      return "Catalog could not create a snapshot.";
    }
  }
}
