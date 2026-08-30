package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeploy;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.failure.CatalogOperation;
import org.qubership.integration.platform.ai.chat.failure.KnownFailure;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CreateDeploymentRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DomainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.RuntimeStateDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.FolderItemDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogIdPatterns;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  private static final String DEPLOY_GONE_MESSAGE = "That deploy is no longer on offer.";

  private static final String UNDEPLOY_GONE_MESSAGE = "That undeploy is no longer on offer.";

  private static final String CANCEL_NOT_DEPLOYED_MESSAGE = "The chain was not deployed.";

  private static final String NOT_DEPLOYED_MESSAGE = "This chain is not deployed.";

  private static final String CHAIN_ITEM_TYPE = "CHAIN";

  private static final Pattern NAME_AFTER_CHAIN = Pattern.compile("(?iU)\\bchain\\s+(.+)$");

  private static final Pattern SNAPSHOT_VERSION = Pattern.compile("(?i)\\b(v\\d+)\\b");

  private static final Pattern SNAPSHOT_PREFIX = Pattern.compile("(?i)\\bsnapshot\\s+(\\S+)");

  private static final Pattern WHICH_ENGINE_OR_DOMAIN =
      Pattern.compile("(?isU)\\bwhich\\s+(engine|domain)s?\\b");

  private static final String DEFAULT_DOMAIN = "default";
  private static final String STATUS_DEPLOYED = "DEPLOYED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_PROCESSING = "PROCESSING";
  private static final int DEFAULT_POLL_ATTEMPTS = 3;
  private static final long DEFAULT_POLL_DELAY_MS = 500L;

  private final ChainContextExtractor chainContextExtractor;
  private final CatalogRestClient catalogRestClient;
  private final PendingRedeployStore pendingRedeployStore;
  private final KnownFailureMapper knownFailureMapper;
  private final PinnedFailureStore pinnedFailureStore;
  private final int pollAttempts;
  private final long pollDelayMillis;

  @Inject
  public DeployChainScenario(
      ChainContextExtractor chainContextExtractor,
      @RestClient CatalogRestClient catalogRestClient,
      PendingRedeployStore pendingRedeployStore,
      KnownFailureMapper knownFailureMapper,
      PinnedFailureStore pinnedFailureStore) {
    this(
        chainContextExtractor,
        catalogRestClient,
        pendingRedeployStore,
        knownFailureMapper,
        pinnedFailureStore,
        DEFAULT_POLL_ATTEMPTS,
        DEFAULT_POLL_DELAY_MS);
  }

  DeployChainScenario(
      ChainContextExtractor chainContextExtractor,
      CatalogRestClient catalogRestClient,
      PendingRedeployStore pendingRedeployStore,
      KnownFailureMapper knownFailureMapper,
      PinnedFailureStore pinnedFailureStore,
      int pollAttempts,
      long pollDelayMillis) {
    this.chainContextExtractor = chainContextExtractor;
    this.catalogRestClient = catalogRestClient;
    this.pendingRedeployStore = pendingRedeployStore;
    this.knownFailureMapper = knownFailureMapper;
    this.pinnedFailureStore = pinnedFailureStore;
    this.pollAttempts = pollAttempts;
    this.pollDelayMillis = pollDelayMillis;
  }

  @Override
  public Multi<ChatEvent> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    ChatDecisionCommand decision = request == null ? null : request.getDecision();
    Multi<ChatEvent> answered = answerCard(conversationId, decision);
    if (answered != null) {
      return answered;
    }

    String userMessage = request != null ? request.getEffectiveUserText() : "";
    Optional<PendingRedeploy> domainWait =
        pendingRedeployStore.find(conversationId).filter(PendingRedeploy::waitingForDomain);
    if (domainWait.isPresent()) {
      return resumeDomainWait(conversationId, domainWait.get(), userMessage);
    }
    String chainId =
        chainContextExtractor.resolveChainId(request, conversationId).orElse(null);
    boolean openGraph = chainId != null;
    if (chainId == null) {
      return resolveUnopenedChain(conversationId, userMessage);
    }
    return continueWithResolvedChain(conversationId, chainId, userMessage, openGraph);
  }

  private Multi<ChatEvent> answerCard(String conversationId, ChatDecisionCommand decision) {
    if (decision == null || decision.getAction() == null) {
      return null;
    }
    return switch (decision.getAction()) {
      case ChatEvent.DEPLOY_ACTION -> applyDeploy(conversationId, decision);
      case ChatEvent.CANCEL_DEPLOY_ACTION -> cancelDeploy(conversationId, decision);
      case ChatEvent.REDEPLOY_ACTION -> applyRedeploy(conversationId, decision);
      case ChatEvent.CANCEL_REDEPLOY_ACTION -> cancelRedeploy(conversationId, decision);
      case ChatEvent.UNDEPLOY_ACTION -> applyUndeploy(conversationId, decision);
      case ChatEvent.CANCEL_UNDEPLOY_ACTION -> cancelUndeploy(conversationId, decision);
      default -> null;
    };
  }

  private Multi<ChatEvent> continueWithResolvedChain(
      String conversationId, String chainId, String userMessage, boolean openGraph) {
    if (UserIntentPatterns.matchesSnapshotIntent(userMessage)) {
      return createSnapshot(conversationId, chainId);
    }

    if (UserIntentPatterns.matchesUndeployIntent(userMessage)) {
      return undeployChain(conversationId, chainId, userMessage);
    }

    if (UserIntentPatterns.matchesDeploymentStatusIntent(userMessage)) {
      return reportStatus(conversationId, chainId);
    }

    if (UserIntentPatterns.matchesDeployIntent(userMessage)) {
      return deployChain(conversationId, chainId, userMessage, !openGraph);
    }

    LOG.infof(
        "DEPLOY_CHAIN neither snapshot nor deploy conversationId=%s chainId=%s",
        conversationId, chainId);
    return Multi.createFrom().item(ChatEvent.token(SNAPSHOT_ONLY_MESSAGE));
  }

  private Multi<ChatEvent> resolveUnopenedChain(String conversationId, String userMessage) {
    try {
      Optional<String> uuid = findChainUuid(userMessage);
      if (uuid.isPresent()) {
        String chainId = loadChainId(uuid.get());
        if (chainId == null) {
          return Multi.createFrom()
              .item(ChatEvent.token("No chain with id " + uuid.get() + " was found."));
        }
        return continueWithResolvedChain(conversationId, chainId, userMessage, false);
      }
      Optional<String> name = extractChainName(userMessage);
      if (name.isEmpty()) {
        LOG.infof("DEPLOY_CHAIN without chain context conversationId=%s", conversationId);
        return Multi.createFrom().item(ChatEvent.token(NO_CHAIN_MESSAGE));
      }
      return continueFromNameSearch(conversationId, userMessage, name.get());
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.LOOKUP, conversationId, null);
    }
  }

  private Multi<ChatEvent> continueFromNameSearch(
      String conversationId, String userMessage, String name) {
    List<FolderItemDto> hits = searchChainItems(name);
    List<FolderItemDto> exact =
        hits.stream().filter(item -> name.equalsIgnoreCase(item.name())).toList();
    List<FolderItemDto> chosen = exact.isEmpty() ? hits : exact;
    if (chosen.isEmpty()) {
      return Multi.createFrom()
          .item(ChatEvent.token("No chain named " + name + " was found."));
    }
    if (chosen.size() > 1) {
      return Multi.createFrom().item(ChatEvent.token(ambiguousChainsMessage(chosen)));
    }
    return continueWithResolvedChain(conversationId, chosen.get(0).id(), userMessage, false);
  }

  private List<FolderItemDto> searchChainItems(String name) {
    List<FolderItemDto> results =
        catalogRestClient.searchFolderItems(new CatalogChainSearchRequest(name));
    if (results == null || results.isEmpty()) {
      return List.of();
    }
    return results.stream()
        .filter(item -> item != null && item.id() != null && !item.id().isBlank())
        .filter(item -> CHAIN_ITEM_TYPE.equals(item.itemType()))
        .toList();
  }

  private static String ambiguousChainsMessage(List<FolderItemDto> chosen) {
    StringBuilder reply = new StringBuilder("Several chains match that name. Which chain did you mean?");
    for (FolderItemDto item : chosen) {
      reply.append('\n').append(item.name() == null ? item.id() : item.name());
      reply.append(" (").append(item.id()).append(')');
    }
    return reply.toString();
  }

  private static Optional<String> findChainUuid(String message) {
    if (message == null || message.isBlank()) {
      return Optional.empty();
    }
    for (String token : message.split("[^0-9a-fA-F-]+")) {
      if (CatalogIdPatterns.isUuidLike(token)) {
        return Optional.of(token);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> extractChainName(String message) {
    String intent = UserIntentPatterns.extractLeadingIntent(message);
    Matcher matcher = NAME_AFTER_CHAIN.matcher(intent);
    if (!matcher.find()) {
      return Optional.empty();
    }
    String name = matcher.group(1).trim();
    if (name.length() >= 2
        && ((name.startsWith("\"") && name.endsWith("\""))
            || (name.startsWith("'") && name.endsWith("'")))) {
      name = name.substring(1, name.length() - 1).trim();
    }
    if (name.isBlank() || CatalogIdPatterns.isUuidLike(name)) {
      return Optional.empty();
    }
    return Optional.of(name);
  }

  private String loadChainId(String chainId) {
    try {
      ChainDto chain = catalogRestClient.getChain(chainId);
      if (chain == null || chain.id() == null || chain.id().isBlank()) {
        return null;
      }
      return chain.id();
    } catch (CatalogNonRetryableResponseException e) {
      if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
        return null;
      }
      throw e;
    }
  }

  private Multi<ChatEvent> createSnapshot(String conversationId, String chainId) {
    LOG.infof("DEPLOY_CHAIN snapshot conversationId=%s chainId=%s", conversationId, chainId);
    try {
      SnapshotDto snapshot = catalogRestClient.createSnapshot(chainId);
      pinnedFailureStore.clear(conversationId, chainId);
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  "Created catalog snapshot "
                      + snapshot.name()
                      + " (id: "
                      + snapshot.id()
                      + ")."));
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.SNAPSHOT, conversationId, chainId);
    }
  }

  private Multi<ChatEvent> deployChain(
      String conversationId, String chainId, String userMessage, boolean confirmFirstDeploy) {
    LOG.infof("DEPLOY_CHAIN deploy conversationId=%s chainId=%s", conversationId, chainId);
    try {
      Optional<String> snapshotHint = namedSnapshotHint(userMessage);
      SnapshotDto namedSnapshot;
      if (snapshotHint.isPresent()) {
        namedSnapshot = findListedSnapshot(chainId, snapshotHint.get());
        if (namedSnapshot == null) {
          return Multi.createFrom()
              .item(ChatEvent.token(unknownSnapshotMessage(snapshotHint.get())));
        }
      } else {
        namedSnapshot = findSnapshotUuidIfListed(chainId, userMessage);
      }

      List<DomainDto> domains = loadDomains();
      if (asksWhichDomain(userMessage)) {
        return waitForDomain(conversationId, chainId, namedSnapshot, confirmFirstDeploy, domains);
      }
      String domain =
          namedDomain(userMessage, domains).or(() -> defaultDomainName(domains)).orElse(null);
      if (domain == null) {
        return waitForDomain(conversationId, chainId, namedSnapshot, confirmFirstDeploy, domains);
      }

      return deployResolved(conversationId, chainId, namedSnapshot, domain, confirmFirstDeploy);
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.DEPLOY, conversationId, chainId);
    }
  }

  private Multi<ChatEvent> waitForDomain(
      String conversationId,
      String chainId,
      SnapshotDto namedSnapshot,
      boolean confirmFirstDeploy,
      List<DomainDto> domains) {
    pendingRedeployStore.put(
        conversationId,
        PendingRedeploy.domainWait(
            chainId, namedSnapshot == null ? null : namedSnapshot.id(), confirmFirstDeploy));
    return Multi.createFrom().item(ChatEvent.token(availableDomainsMessage(domains)));
  }

  private Multi<ChatEvent> resumeDomainWait(
      String conversationId, PendingRedeploy wait, String userMessage) {
    LOG.infof(
        "DEPLOY_CHAIN domain-wait conversationId=%s chainId=%s", conversationId, wait.chainId());
    try {
      if (wait.undeploy()) {
        return resumeUndeployDomainWait(conversationId, wait, userMessage);
      }
      List<DomainDto> domains = loadDomains();
      List<String> matches = matchingDomainNames(userMessage, domains);
      if (matches.size() != 1) {
        return Multi.createFrom().item(ChatEvent.token(availableDomainsMessage(domains)));
      }
      pendingRedeployStore.clear(conversationId);
      SnapshotDto named =
          wait.snapshotId() == null ? null : findListedSnapshot(wait.chainId(), wait.snapshotId());
      if (wait.snapshotId() != null && named == null) {
        return Multi.createFrom().item(ChatEvent.token(unknownSnapshotMessage(wait.snapshotId())));
      }
      return deployResolved(
          conversationId, wait.chainId(), named, matches.get(0), wait.confirmFirstDeploy());
    } catch (RuntimeException e) {
      CatalogOperation operation =
          wait.undeploy() ? CatalogOperation.UNDEPLOY : CatalogOperation.DEPLOY;
      return knownOrRethrow(e, operation, conversationId, wait.chainId());
    }
  }

  private Multi<ChatEvent> deployResolved(
      String conversationId,
      String chainId,
      SnapshotDto namedSnapshot,
      String domain,
      boolean confirmFirstDeploy) {
    List<DeploymentDto> existing = catalogRestClient.listDeployments(chainId);
    Optional<DeploymentDto> onDomain =
        existing.stream().filter(item -> isDomain(item, domain)).findFirst();
    if (onDomain.isPresent()) {
      return offerRedeploy(conversationId, chainId, domain, namedSnapshot, onDomain.get());
    }
    if (confirmFirstDeploy) {
      return offerFirstDeploy(conversationId, chainId, domain, namedSnapshot);
    }
    SnapshotDto snapshot =
        namedSnapshot != null ? namedSnapshot : resolveBareDeploySnapshot(chainId);
    return deploySnapshot(conversationId, chainId, snapshot, domain);
  }

  private Multi<ChatEvent> offerRedeploy(
      String conversationId,
      String chainId,
      String domain,
      SnapshotDto namedSnapshot,
      DeploymentDto existing) {
    String operationId = UUID.randomUUID().toString();
    pendingRedeployStore.put(
        conversationId,
        new PendingRedeploy(
            chainId,
            domain,
            existing.id(),
            operationId,
            namedSnapshot == null ? null : namedSnapshot.id()));
    return Multi.createFrom()
        .item(
            ChatEvent.redeployDecision(
                operationId, redeployQuestion(chainId, domain, namedSnapshot)));
  }

  private Multi<ChatEvent> offerFirstDeploy(
      String conversationId, String chainId, String domain, SnapshotDto namedSnapshot) {
    String operationId = UUID.randomUUID().toString();
    pendingRedeployStore.put(
        conversationId,
        new PendingRedeploy(
            chainId, domain, null, operationId, namedSnapshot == null ? null : namedSnapshot.id()));
    return Multi.createFrom()
        .item(ChatEvent.deployDecision(operationId, deployQuestion(chainId, domain)));
  }

  private String deployQuestion(String chainId, String domain) {
    ChainDto chain = catalogRestClient.getChain(chainId);
    return "Deploy chain " + chainLabel(chain, chainId) + " to domain " + domain + "?";
  }

  private String redeployQuestion(String chainId, String domain, SnapshotDto namedSnapshot) {
    ChainDto chain = catalogRestClient.getChain(chainId);
    return "Chain "
        + chainLabel(chain, chainId)
        + " is already deployed on domain "
        + domain
        + ". "
        + snapshotActionText(chain, chainId, namedSnapshot)
        + " Replace the live deployment?";
  }

  private static String chainLabel(ChainDto chain, String chainId) {
    String name = chain.name();
    if (name == null || name.isBlank()) {
      return chainId;
    }
    return name + " (" + chainId + ")";
  }

  private String snapshotActionText(
      ChainDto chain, String chainId, SnapshotDto namedSnapshot) {
    if (namedSnapshot != null) {
      return reuseSnapshotText(namedSnapshot.name());
    }
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

  private Multi<ChatEvent> applyDeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision, false);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(DEPLOY_GONE_MESSAGE));
    }
    PendingRedeploy confirm = pending.get();
    LOG.infof(
        "DEPLOY_CHAIN confirm-deploy conversationId=%s chainId=%s",
        conversationId, confirm.chainId());
    try {
      SnapshotDto snapshot = resolvePendingSnapshot(confirm);
      if (snapshot == null) {
        return Multi.createFrom()
            .item(ChatEvent.token(unknownSnapshotMessage(confirm.snapshotId())));
      }
      Multi<ChatEvent> result =
          deploySnapshot(conversationId, confirm.chainId(), snapshot, confirm.domain());
      pendingRedeployStore.clear(conversationId);
      return result;
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.DEPLOY, conversationId, confirm.chainId());
    }
  }

  private Multi<ChatEvent> applyRedeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision, false);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(REDEPLOY_GONE_MESSAGE));
    }
    PendingRedeploy replace = pending.get();
    LOG.infof(
        "DEPLOY_CHAIN redeploy conversationId=%s chainId=%s deploymentId=%s",
        conversationId, replace.chainId(), replace.existingDeploymentId());
    try {
      SnapshotDto snapshot = resolvePendingSnapshot(replace);
      if (snapshot == null) {
        return Multi.createFrom()
            .item(ChatEvent.token(unknownSnapshotMessage(replace.snapshotId())));
      }
      catalogRestClient.deleteDeployment(replace.chainId(), replace.existingDeploymentId());
      Multi<ChatEvent> result =
          deploySnapshot(conversationId, replace.chainId(), snapshot, replace.domain());
      pendingRedeployStore.clear(conversationId);
      return result;
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.DEPLOY, conversationId, replace.chainId());
    }
  }

  private Multi<ChatEvent> cancelDeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision, false);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(DEPLOY_GONE_MESSAGE));
    }
    pendingRedeployStore.clear(conversationId);
    return Multi.createFrom().item(ChatEvent.token(CANCEL_NOT_DEPLOYED_MESSAGE));
  }

  private Multi<ChatEvent> cancelRedeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision, false);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(REDEPLOY_GONE_MESSAGE));
    }
    pendingRedeployStore.clear(conversationId);
    return Multi.createFrom()
        .item(ChatEvent.token("The live deployment on domain " + pending.get().domain() + " is unchanged."));
  }

  private Multi<ChatEvent> reportStatus(String conversationId, String chainId) {
    LOG.infof("DEPLOY_CHAIN status conversationId=%s chainId=%s", conversationId, chainId);
    try {
      List<DeploymentDto> listed = catalogRestClient.listDeployments(chainId);
      if (listed == null || listed.isEmpty()) {
        return Multi.createFrom().item(ChatEvent.token(NOT_DEPLOYED_MESSAGE));
      }
      return Multi.createFrom().item(ChatEvent.token(statusMessage(listed)));
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.STATUS, conversationId, chainId);
    }
  }

  private static String statusMessage(List<DeploymentDto> listed) {
    StringBuilder reply = new StringBuilder();
    for (DeploymentDto deployment : listed) {
      if (deployment == null) {
        continue;
      }
      if (!reply.isEmpty()) {
        reply.append('\n');
      }
      String domain = deployment.domain() == null || deployment.domain().isBlank()
          ? "unknown"
          : deployment.domain();
      reply.append("Domain ").append(domain);
      if (deployment.name() != null && !deployment.name().isBlank()) {
        reply.append(", snapshot ").append(deployment.name());
      }
      if (deployment.snapshotId() != null && !deployment.snapshotId().isBlank()) {
        reply.append(" (id: ").append(deployment.snapshotId()).append(')');
      }
      reply.append('.');
      appendPodStatuses(reply, deployment);
    }
    return reply.isEmpty() ? NOT_DEPLOYED_MESSAGE : reply.toString();
  }

  private static void appendPodStatuses(StringBuilder reply, DeploymentDto deployment) {
    if (deployment.runtime() == null || deployment.runtime().states() == null) {
      return;
    }
    for (var entry : deployment.runtime().states().entrySet()) {
      reply.append('\n').append(entry.getKey()).append(": ");
      RuntimeStateDto state = entry.getValue();
      reply.append(state == null || state.status() == null || state.status().isBlank()
          ? STATUS_PROCESSING
          : state.status());
    }
  }

  private Multi<ChatEvent> undeployChain(
      String conversationId, String chainId, String userMessage) {
    LOG.infof("DEPLOY_CHAIN undeploy conversationId=%s chainId=%s", conversationId, chainId);
    try {
      List<DeploymentDto> listed = catalogRestClient.listDeployments(chainId);
      if (listed == null || listed.isEmpty()) {
        return Multi.createFrom().item(ChatEvent.token(NOT_DEPLOYED_MESSAGE));
      }
      List<DeploymentDto> live = liveDeployments(listed);
      if (live.isEmpty()) {
        return Multi.createFrom().item(ChatEvent.token(NOT_DEPLOYED_MESSAGE));
      }
      List<String> named = matchingDeploymentDomains(userMessage, live);
      if (named.size() == 1) {
        return offerUndeployForDomain(conversationId, chainId, live, named.get(0));
      }
      if (uniqueDomains(live).size() == 1) {
        return offerUndeploy(conversationId, chainId, live.get(0));
      }
      pendingRedeployStore.put(conversationId, PendingRedeploy.undeployDomainWait(chainId));
      return Multi.createFrom().item(ChatEvent.token(whichUndeployDomainMessage(live)));
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.UNDEPLOY, conversationId, chainId);
    }
  }

  private Multi<ChatEvent> resumeUndeployDomainWait(
      String conversationId, PendingRedeploy wait, String userMessage) {
    List<DeploymentDto> listed = catalogRestClient.listDeployments(wait.chainId());
    List<DeploymentDto> live = liveDeployments(listed);
    if (live.isEmpty()) {
      pendingRedeployStore.clear(conversationId);
      return Multi.createFrom().item(ChatEvent.token(NOT_DEPLOYED_MESSAGE));
    }
    List<String> named = matchingDeploymentDomains(userMessage, live);
    if (named.size() != 1) {
      return Multi.createFrom().item(ChatEvent.token(whichUndeployDomainMessage(live)));
    }
    pendingRedeployStore.clear(conversationId);
    return offerUndeployForDomain(conversationId, wait.chainId(), live, named.get(0));
  }

  private Multi<ChatEvent> offerUndeployForDomain(
      String conversationId, String chainId, List<DeploymentDto> live, String domain) {
    Optional<DeploymentDto> onDomain =
        live.stream().filter(item -> isDomain(item, domain)).findFirst();
    if (onDomain.isEmpty()) {
      return Multi.createFrom()
          .item(ChatEvent.token("This chain is not deployed on domain " + domain + "."));
    }
    return offerUndeploy(conversationId, chainId, onDomain.get());
  }

  private Multi<ChatEvent> offerUndeploy(
      String conversationId, String chainId, DeploymentDto existing) {
    String operationId = UUID.randomUUID().toString();
    pendingRedeployStore.put(
        conversationId,
        PendingRedeploy.pendingUndeploy(
            chainId, existing.domain(), existing.id(), operationId));
    return Multi.createFrom()
        .item(
            ChatEvent.undeployDecision(
                operationId, undeployQuestion(chainId, existing.domain())));
  }

  private String undeployQuestion(String chainId, String domain) {
    ChainDto chain = catalogRestClient.getChain(chainId);
    return "Undeploy chain " + chainLabel(chain, chainId) + " from domain " + domain + "?";
  }

  private Multi<ChatEvent> applyUndeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision, true);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(UNDEPLOY_GONE_MESSAGE));
    }
    PendingRedeploy remove = pending.get();
    LOG.infof(
        "DEPLOY_CHAIN confirm-undeploy conversationId=%s chainId=%s deploymentId=%s",
        conversationId, remove.chainId(), remove.existingDeploymentId());
    try {
      catalogRestClient.deleteDeployment(remove.chainId(), remove.existingDeploymentId());
      pendingRedeployStore.clear(conversationId);
      pinnedFailureStore.clear(conversationId, remove.chainId());
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  "Undeployed the chain from domain " + remove.domain() + "."));
    } catch (RuntimeException e) {
      return knownOrRethrow(e, CatalogOperation.UNDEPLOY, conversationId, remove.chainId());
    }
  }

  private Multi<ChatEvent> cancelUndeploy(String conversationId, ChatDecisionCommand decision) {
    Optional<PendingRedeploy> pending = matchingPending(conversationId, decision, true);
    if (pending.isEmpty()) {
      return Multi.createFrom().item(ChatEvent.token(UNDEPLOY_GONE_MESSAGE));
    }
    pendingRedeployStore.clear(conversationId);
    return Multi.createFrom().item(ChatEvent.token("The live deployment remains in place."));
  }

  private static List<DeploymentDto> liveDeployments(List<DeploymentDto> listed) {
    if (listed == null) {
      return List.of();
    }
    return listed.stream()
        .filter(item -> item != null && item.id() != null && !item.id().isBlank())
        .filter(item -> item.domain() != null && !item.domain().isBlank())
        .toList();
  }

  private static List<String> uniqueDomains(List<DeploymentDto> live) {
    return live.stream().map(DeploymentDto::domain).distinct().toList();
  }

  private static List<String> matchingDeploymentDomains(
      String userMessage, List<DeploymentDto> live) {
    List<DomainDto> domains =
        uniqueDomains(live).stream().map(name -> new DomainDto(name, "")).toList();
    return matchingDomainNames(userMessage, domains);
  }

  private static String whichUndeployDomainMessage(List<DeploymentDto> live) {
    String names = String.join(" and ", uniqueDomains(live));
    return "This chain is deployed on " + names + ". Which domain should I undeploy from?";
  }

  private Optional<PendingRedeploy> matchingPending(
      String conversationId, ChatDecisionCommand decision, boolean undeploy) {
    Optional<PendingRedeploy> pending = pendingRedeployStore.find(conversationId);
    if (pending.isEmpty()
        || pending.get().undeploy() != undeploy
        || pending.get().operationId() == null
        || !pending.get().operationId().equals(decision.getArtifactHash())) {
      return Optional.empty();
    }
    return pending;
  }

  private Multi<ChatEvent> deploySnapshot(
      String conversationId, String chainId, SnapshotDto snapshot, String domain) {
    catalogRestClient.createDeployment(
        chainId, new CreateDeploymentRequest(domain, snapshot.id()));
    String status = pollDeploymentStatus(chainId, domain);
    pinnedFailureStore.clear(conversationId, chainId);
    return Multi.createFrom()
        .item(
            ChatEvent.token(
                "Deployed snapshot "
                    + snapshot.name()
                    + " (id: "
                    + snapshot.id()
                    + ") to domain "
                    + domain
                    + ". Status: "
                    + status
                    + "."));
  }

  private SnapshotDto resolvePendingSnapshot(PendingRedeploy pending) {
    if (pending.snapshotId() != null && !pending.snapshotId().isBlank()) {
      return findListedSnapshot(pending.chainId(), pending.snapshotId());
    }
    return resolveBareDeploySnapshot(pending.chainId());
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

  private static Optional<String> namedSnapshotHint(String message) {
    if (message == null || message.isBlank()) {
      return Optional.empty();
    }
    Matcher prefix = SNAPSHOT_PREFIX.matcher(message);
    if (prefix.find()) {
      return Optional.of(trimTrailingPunctuation(prefix.group(1)));
    }
    Matcher version = SNAPSHOT_VERSION.matcher(message);
    if (version.find()) {
      return Optional.of(version.group(1));
    }
    return Optional.empty();
  }

  private static String trimTrailingPunctuation(String token) {
    int end = token.length();
    while (end > 0 && !Character.isLetterOrDigit(token.charAt(end - 1))) {
      end--;
    }
    return end == 0 ? token : token.substring(0, end);
  }

  private SnapshotDto findListedSnapshot(String chainId, String hint) {
    List<SnapshotDto> listed = catalogRestClient.listSnapshots(chainId);
    if (listed == null || hint == null || hint.isBlank()) {
      return null;
    }
    for (SnapshotDto snapshot : listed) {
      if (snapshot == null) {
        continue;
      }
      if (hint.equalsIgnoreCase(snapshot.name()) || hint.equalsIgnoreCase(snapshot.id())) {
        return snapshot;
      }
    }
    return null;
  }

  private SnapshotDto findSnapshotUuidIfListed(String chainId, String userMessage) {
    Optional<String> uuid = findChainUuid(userMessage);
    if (uuid.isEmpty() || uuid.get().equalsIgnoreCase(chainId)) {
      return null;
    }
    return findListedSnapshot(chainId, uuid.get());
  }

  private static String unknownSnapshotMessage(String hint) {
    if (CatalogIdPatterns.isUuidLike(hint)) {
      return "No snapshot with id " + hint + " was found.";
    }
    return "No snapshot named " + hint + " was found.";
  }

  private List<DomainDto> loadDomains() {
    List<DomainDto> listed = catalogRestClient.listDomains();
    return listed == null ? List.of() : listed;
  }

  private static boolean asksWhichDomain(String message) {
    return message != null && WHICH_ENGINE_OR_DOMAIN.matcher(message).find();
  }

  private static Optional<String> namedDomain(String message, List<DomainDto> domains) {
    List<String> matches = matchingDomainNames(message, domains);
    return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
  }

  private static List<String> matchingDomainNames(String message, List<DomainDto> domains) {
    if (message == null || message.isBlank() || domains == null) {
      return List.of();
    }
    List<String> matches = new ArrayList<>();
    for (DomainDto domain : domains) {
      if (domain == null || domain.name() == null || domain.name().isBlank()) {
        continue;
      }
      Pattern word = Pattern.compile("(?i)\\b" + Pattern.quote(domain.name()) + "\\b");
      if (word.matcher(message).find()) {
        matches.add(domain.name());
      }
    }
    return matches;
  }

  private static Optional<String> defaultDomainName(List<DomainDto> domains) {
    if (domains == null) {
      return Optional.empty();
    }
    for (DomainDto domain : domains) {
      if (domain != null
          && domain.name() != null
          && DEFAULT_DOMAIN.equalsIgnoreCase(domain.name())) {
        return Optional.of(domain.name());
      }
    }
    return Optional.empty();
  }

  private static String availableDomainsMessage(List<DomainDto> domains) {
    StringBuilder names = new StringBuilder();
    if (domains != null) {
      for (DomainDto domain : domains) {
        if (domain == null || domain.name() == null || domain.name().isBlank()) {
          continue;
        }
        if (!names.isEmpty()) {
          names.append(", ");
        }
        names.append(domain.name());
      }
    }
    if (names.isEmpty()) {
      return "No engine domains are available.";
    }
    return "Available domains: " + names + ". Name one to deploy.";
  }

  private String pollDeploymentStatus(String chainId, String domain) {
    String status = STATUS_PROCESSING;
    for (int attempt = 0; attempt < pollAttempts; attempt++) {
      if (attempt > 0) {
        awaitPollDelay();
      }
      List<DeploymentDto> listed = catalogRestClient.listDeployments(chainId);
      DeploymentDto onDomain =
          listed.stream().filter(item -> isDomain(item, domain)).findFirst().orElse(null);
      status = catalogStatus(onDomain);
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

  private static boolean isDomain(DeploymentDto deployment, String domain) {
    return deployment.domain() != null && domain.equalsIgnoreCase(deployment.domain());
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

  private Multi<ChatEvent> knownOrRethrow(
      Throwable error, CatalogOperation operation, String conversationId, String chainId) {
    Optional<KnownFailure> known = knownFailureMapper.tryMap(error, operation);
    if (known.isEmpty()) {
      if (error instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new RuntimeException(error);
    }
    KnownFailure failure = known.get();
    LOG.warnf(
        error,
        "DEPLOY_CHAIN %s failed conversationId=%s chainId=%s",
        operation,
        conversationId,
        chainId);
    if (chainId != null && !chainId.isBlank()) {
      pinnedFailureStore.put(
          new PinnedFailure(
              conversationId, chainId, failure.safeText(), failure.diagnosticDetail()));
    }
    return Multi.createFrom().item(ChatEvent.token(failure.safeText()));
  }
}
