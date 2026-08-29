package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeploy;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
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
import java.util.Optional;
import java.util.UUID;

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

  private static final String REDEPLOY_GONE_MESSAGE = "That redeploy is no longer on offer.";

  private static final String CANCEL_UNCHANGED_MESSAGE =
      "The live deployment on domain default is unchanged.";

  private static final String DEFAULT_DOMAIN = "default";
  private static final String STATUS_DEPLOYED = "DEPLOYED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_PROCESSING = "PROCESSING";
  private static final int DEFAULT_POLL_ATTEMPTS = 3;
  private static final long DEFAULT_POLL_DELAY_MS = 500L;

  private final ChainContextExtractor chainContextExtractor;
  private final CatalogRestClient catalogRestClient;
  private final ObjectMapper objectMapper;
  private final PendingRedeployStore pendingRedeployStore;
  private final int pollAttempts;
  private final long pollDelayMillis;

  @Inject
  public DeployChainScenario(
      ChainContextExtractor chainContextExtractor,
      @RestClient CatalogRestClient catalogRestClient,
      ObjectMapper objectMapper,
      PendingRedeployStore pendingRedeployStore) {
    this(
        chainContextExtractor,
        catalogRestClient,
        objectMapper,
        pendingRedeployStore,
        DEFAULT_POLL_ATTEMPTS,
        DEFAULT_POLL_DELAY_MS);
  }

  DeployChainScenario(
      ChainContextExtractor chainContextExtractor,
      CatalogRestClient catalogRestClient,
      ObjectMapper objectMapper,
      PendingRedeployStore pendingRedeployStore,
      int pollAttempts,
      long pollDelayMillis) {
    this.chainContextExtractor = chainContextExtractor;
    this.catalogRestClient = catalogRestClient;
    this.objectMapper = objectMapper;
    this.pendingRedeployStore = pendingRedeployStore;
    this.pollAttempts = pollAttempts;
    this.pollDelayMillis = pollDelayMillis;
  }

  @Override
  public Multi<ChatEvent> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    ChatDecisionCommand decision = request == null ? null : request.getDecision();
    if (decision != null && ChatEvent.REDEPLOY_ACTION.equals(decision.getAction())) {
      return applyRedeploy(conversationId, decision);
    }
    if (decision != null && ChatEvent.CANCEL_REDEPLOY_ACTION.equals(decision.getAction())) {
      return cancelRedeploy(conversationId, decision);
    }

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
      Optional<DeploymentDto> onDefault =
          existing.stream().filter(DeployChainScenario::isDefaultDomain).findFirst();
      if (onDefault.isPresent()) {
        return offerRedeploy(conversationId, chainId, onDefault.get());
      }

      SnapshotDto snapshot = resolveBareDeploySnapshot(chainId);
      return deploySnapshot(chainId, snapshot);
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

  private Multi<ChatEvent> offerRedeploy(
      String conversationId, String chainId, DeploymentDto existing) {
    String operationId = UUID.randomUUID().toString();
    pendingRedeployStore.put(
        conversationId,
        new PendingRedeploy(chainId, DEFAULT_DOMAIN, existing.id(), operationId));
    return Multi.createFrom()
        .item(ChatEvent.redeployDecision(operationId, redeployQuestion(chainId)));
  }

  private String redeployQuestion(String chainId) {
    ChainDto chain = catalogRestClient.getChain(chainId);
    return "Chain "
        + chainLabel(chain, chainId)
        + " is already deployed on domain default. "
        + snapshotActionText(chain, chainId)
        + " Replace the live deployment?";
  }

  private static String chainLabel(ChainDto chain, String chainId) {
    String name = chain.name();
    if (name == null || name.isBlank()) {
      return chainId;
    }
    return name + " (" + chainId + ")";
  }

  private String snapshotActionText(ChainDto chain, String chainId) {
    if (chain.unsavedChanges()) {
      return "Redeploying will build a new snapshot.";
    }
    if (chain.currentSnapshot() != null) {
      return reuseSnapshotText(chain.currentSnapshot().name());
    }
    List<SnapshotDto> listed = catalogRestClient.listSnapshots(chainId);
    if (!listed.isEmpty()) {
      return reuseSnapshotText(listed.get(listed.size() - 1).name());
    }
    return "Redeploying will build a new snapshot.";
  }

  private static String reuseSnapshotText(String snapshotName) {
    if (snapshotName == null || snapshotName.isBlank()) {
      return "Redeploying will reuse the current snapshot.";
    }
    return "Redeploying will reuse snapshot " + snapshotName + ".";
  }

  private Multi<ChatEvent> applyRedeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(REDEPLOY_GONE_MESSAGE));
    }
    PendingRedeploy replace = pending.get();
    LOG.infof(
        "DEPLOY_CHAIN redeploy conversationId=%s chainId=%s deploymentId=%s",
        conversationId, replace.chainId(), replace.existingDeploymentId());
    try {
      SnapshotDto snapshot = resolveBareDeploySnapshot(replace.chainId());
      catalogRestClient.deleteDeployment(replace.chainId(), replace.existingDeploymentId());
      Multi<ChatEvent> result = deploySnapshot(replace.chainId(), snapshot);
      pendingRedeployStore.clear(conversationId);
      return result;
    } catch (CatalogNonRetryableResponseException e) {
      LOG.warnf(
          e,
          "DEPLOY_CHAIN redeploy refused conversationId=%s chainId=%s",
          conversationId,
          replace.chainId());
      return Multi.createFrom().item(ChatEvent.error(formatCatalogRefusal(e)));
    } catch (RuntimeException e) {
      LOG.errorf(
          e,
          "DEPLOY_CHAIN redeploy failed conversationId=%s chainId=%s",
          conversationId,
          replace.chainId());
      return Multi.createFrom()
          .item(ChatEvent.error("Failed to deploy this chain: " + e.getMessage()));
    }
  }

  private Multi<ChatEvent> cancelRedeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(REDEPLOY_GONE_MESSAGE));
    }
    pendingRedeployStore.clear(conversationId);
    return Multi.createFrom().item(ChatEvent.token(CANCEL_UNCHANGED_MESSAGE));
  }

  private Optional<PendingRedeploy> matchingPending(
      String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = pendingRedeployStore.find(conversationId);
    if (pending.isEmpty() || !pending.get().operationId().equals(decision.getArtifactHash())) {
      return Optional.empty();
    }
    return pending;
  }

  private Multi<ChatEvent> deploySnapshot(String chainId, SnapshotDto snapshot) {
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
