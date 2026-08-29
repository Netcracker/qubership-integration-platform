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
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CreateDeploymentRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.RuntimeStateDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;

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

  private static final String ALREADY_DEPLOYED_MESSAGE =
      "This chain is already deployed on domain default.";

  private static final String DEFAULT_DOMAIN = "default";
  private static final String STATUS_DEPLOYED = "DEPLOYED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_PROCESSING = "PROCESSING";
  private static final int DEFAULT_POLL_ATTEMPTS = 3;
  private static final long DEFAULT_POLL_DELAY_MS = 500L;

  private final ChainContextExtractor chainContextExtractor;
  private final CatalogRestClient catalogRestClient;
  private final ObjectMapper objectMapper;
  private final int pollAttempts;
  private final long pollDelayMillis;

  @Inject
  public DeployChainScenario(
      ChainContextExtractor chainContextExtractor,
      @RestClient CatalogRestClient catalogRestClient,
      ObjectMapper objectMapper) {
    this(
        chainContextExtractor,
        catalogRestClient,
        objectMapper,
        DEFAULT_POLL_ATTEMPTS,
        DEFAULT_POLL_DELAY_MS);
  }

  DeployChainScenario(
      ChainContextExtractor chainContextExtractor,
      CatalogRestClient catalogRestClient,
      ObjectMapper objectMapper,
      int pollAttempts,
      long pollDelayMillis) {
    this.chainContextExtractor = chainContextExtractor;
    this.catalogRestClient = catalogRestClient;
    this.objectMapper = objectMapper;
    this.pollAttempts = pollAttempts;
    this.pollDelayMillis = pollDelayMillis;
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

    if (UserIntentPatterns.matchesSnapshotIntent(userMessage)) {
      return createSnapshot(conversationId, chainId);
    }

    if (UserIntentPatterns.matchesDeployIntent(userMessage)) {
      return deployToDefault(conversationId, chainId);
    }

    LOG.infof(
        "DEPLOY_CHAIN neither snapshot nor deploy conversationId=%s chainId=%s",
        conversationId, chainId);
    return Multi.createFrom().item(ChatEvent.token(SNAPSHOT_ONLY_MESSAGE));
  }

  private Multi<ChatEvent> createSnapshot(String conversationId, String chainId) {
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

  private Multi<ChatEvent> deployToDefault(String conversationId, String chainId) {
    LOG.infof("DEPLOY_CHAIN deploy conversationId=%s chainId=%s", conversationId, chainId);
    try {
      List<DeploymentDto> existing = catalogRestClient.listDeployments(chainId);
      if (existing.stream().anyMatch(DeployChainScenario::isDefaultDomain)) {
        return Multi.createFrom().item(ChatEvent.token(ALREADY_DEPLOYED_MESSAGE));
      }

      SnapshotDto snapshot = resolveBareDeploySnapshot(chainId);
      catalogRestClient.createDeployment(
          chainId, new CreateDeploymentRequest(DEFAULT_DOMAIN, snapshot.id()));
      String status = pollDeploymentStatus(chainId);
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  "Deployed snapshot "
                      + snapshot.name()
                      + " (id: "
                      + snapshot.id()
                      + ") to domain default. Status: "
                      + status
                      + "."));
    } catch (CatalogNonRetryableResponseException e) {
      LOG.warnf(
          e,
          "DEPLOY_CHAIN deploy refused conversationId=%s chainId=%s",
          conversationId,
          chainId);
      return Multi.createFrom().item(ChatEvent.error(formatCatalogRefusal(e)));
    } catch (RuntimeException e) {
      LOG.errorf(
          e, "DEPLOY_CHAIN deploy failed conversationId=%s chainId=%s", conversationId, chainId);
      return Multi.createFrom()
          .item(ChatEvent.error("Failed to deploy this chain: " + e.getMessage()));
    }
  }

  private SnapshotDto resolveBareDeploySnapshot(String chainId) {
    ChainDto chain = catalogRestClient.getChain(chainId);
    if (chain.unsavedChanges()) {
      return catalogRestClient.createSnapshot(chainId);
    }
    if (chain.currentSnapshot() != null) {
      return new SnapshotDto(chain.currentSnapshot().id(), chain.currentSnapshot().name());
    }
    List<SnapshotDto> listed = catalogRestClient.listSnapshots(chainId);
    if (!listed.isEmpty()) {
      return listed.get(listed.size() - 1);
    }
    return catalogRestClient.createSnapshot(chainId);
  }

  private String pollDeploymentStatus(String chainId) {
    String status = STATUS_PROCESSING;
    for (int attempt = 0; attempt < pollAttempts; attempt++) {
      if (attempt > 0) {
        awaitPollDelay();
      }
      List<DeploymentDto> listed = catalogRestClient.listDeployments(chainId);
      DeploymentDto onDefault =
          listed.stream().filter(DeployChainScenario::isDefaultDomain).findFirst().orElse(null);
      status = catalogStatus(onDefault);
      if (STATUS_DEPLOYED.equals(status) || STATUS_FAILED.equals(status)) {
        break;
      }
    }
    return status;
  }

  private void awaitPollDelay() {
    if (pollDelayMillis <= 0) {
      return;
    }
    try {
      Thread.sleep(pollDelayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isDefaultDomain(DeploymentDto deployment) {
    return deployment.domain() != null && DEFAULT_DOMAIN.equalsIgnoreCase(deployment.domain());
  }

  private static String catalogStatus(DeploymentDto deployment) {
    if (deployment == null
        || deployment.runtime() == null
        || deployment.runtime().states() == null
        || deployment.runtime().states().isEmpty()) {
      return STATUS_PROCESSING;
    }
    boolean anyFailed = false;
    boolean allDeployed = true;
    boolean anyPresent = false;
    for (RuntimeStateDto state : deployment.runtime().states().values()) {
      if (state == null || state.status() == null || state.status().isBlank()) {
        allDeployed = false;
        continue;
      }
      anyPresent = true;
      if (STATUS_FAILED.equalsIgnoreCase(state.status())) {
        anyFailed = true;
      } else if (!STATUS_DEPLOYED.equalsIgnoreCase(state.status())) {
        allDeployed = false;
      }
    }
    if (anyFailed) {
      return STATUS_FAILED;
    }
    if (anyPresent && allDeployed) {
      return STATUS_DEPLOYED;
    }
    return STATUS_PROCESSING;
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
