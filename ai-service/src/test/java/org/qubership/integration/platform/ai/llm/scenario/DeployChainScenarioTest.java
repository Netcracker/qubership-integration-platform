package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CreateDeploymentRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CurrentSnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DomainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentRuntimeDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.RuntimeStateDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest;
import org.qubership.integration.platform.ai.model.ScenarioType;

class DeployChainScenarioTest {

  private static final String CONVERSATION_ID = "conv-deploy-chain";
  private static final String CHAIN_ID = "chain-1";
  private static final String CATALOG_CHAIN_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
  private static final String SNAPSHOT_ID = "11111111-1111-1111-1111-111111111111";
  private static final String NEW_SNAPSHOT_ID = "22222222-2222-2222-2222-222222222222";

  private ChainContextExtractor chainContextExtractor;
  private CatalogRestClient catalogRestClient;
  private PinnedFailureStore pinnedFailureStore;
  private DeployChainScenario scenario;

  @BeforeEach
  void setUp() {
    chainContextExtractor = mock(ChainContextExtractor.class);
    catalogRestClient = mock(CatalogRestClient.class);
    when(catalogRestClient.listDomains()).thenReturn(List.of(domain("default")));
    pinnedFailureStore = new PinnedFailureStore();
    scenario =
        new DeployChainScenario(
            chainContextExtractor,
            catalogRestClient,
            new PendingRedeployStore(),
            new KnownFailureMapper(),
            pinnedFailureStore,
            3,
            0L);
  }

  @Test
  void missingChainContextDoesNotCreateSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());

    ChatEvent.Token token = tokenFrom("take a snapshot");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).listSnapshots(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(token.text().contains("No chain context found"));
  }

  @Test
  void takeASnapshotCreatesOnceAndReportsNameAndId() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenReturn(new SnapshotDto(SNAPSHOT_ID, "V1"));

    ChatEvent.Token token = tokenFrom("take a snapshot");

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    verify(catalogRestClient, never()).listSnapshots(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(token.text().contains("V1"));
    assertTrue(token.text().contains(SNAPSHOT_ID));
  }

  @Test
  void createASnapshotCreatesOnceAndReportsNameAndId() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenReturn(new SnapshotDto(SNAPSHOT_ID, "V1"));

    ChatEvent.Token token = tokenFrom("create a snapshot");

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    verify(catalogRestClient, never()).listSnapshots(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(token.text().contains("V1"));
    assertTrue(token.text().contains(SNAPSHOT_ID));
  }

  @Test
  void catalog400SurfacesReasonAndDoesNotListSnapshots() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    CatalogNonRetryableResponseException refused =
        catalog400(
            """
            {
              "errorMessage": "Fields are not properly defined or require mandatory connection",
              "details": {
                "chainId": "chain-1",
                "elementId": "el-http-1",
                "elementName": "HTTP Trigger"
              }
            }
            """);
    when(catalogRestClient.createSnapshot(CHAIN_ID)).thenThrow(refused);

    String text = replyTextFrom("take a snapshot");

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    verify(catalogRestClient, never()).listSnapshots(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("Fields are not properly defined or require mandatory connection"));
    assertTrue(text.contains("HTTP Trigger"));
    assertTrue(text.contains("el-http-1"));
    assertFalse(text.contains(SNAPSHOT_ID));
  }

  @Test
  void catalogTimeoutOnSnapshotEmitsSanitizedTokenNotError() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenThrow(
            new TimeoutException("CatalogRestClient$$CDIWrapper#createSnapshot timed out"));

    List<ChatEvent> events = eventsFrom("take a snapshot");

    assertEquals(1, events.size());
    assertTrue(events.get(0) instanceof ChatEvent.Token, () -> "expected Token, got " + events);
    assertEquals(
        KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, ((ChatEvent.Token) events.get(0)).text());
    assertFalse(events.stream().anyMatch(ChatEvent.Error.class::isInstance));
    assertFalse(((ChatEvent.Token) events.get(0)).text().contains("CDIWrapper"));
    assertEquals(
        KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE,
        pinnedFailureStore.find(CONVERSATION_ID, CHAIN_ID).orElseThrow().safeText());
  }

  @Test
  void catalogNpeDoesNotBecomeTokenOrError() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.createSnapshot(CHAIN_ID)).thenThrow(new NullPointerException("x"));

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest("take a snapshot"), CONVERSATION_ID, ScenarioType.DEPLOY_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));
    sub.awaitFailure();

    assertTrue(sub.getFailure() instanceof NullPointerException);
    assertFalse(sub.getItems().stream().anyMatch(ChatEvent.Token.class::isInstance));
    assertTrue(pinnedFailureStore.find(CONVERSATION_ID, CHAIN_ID).isEmpty());
  }

  @Test
  void unrelatedTurnDoesNotCreateSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));

    ChatEvent.Token token = tokenFrom("hello");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).listSnapshots(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertTrue(token.text().toLowerCase().contains("snapshot"));
  }

  @Test
  void deployReusesCurrentSnapshotAndReportsDeployed() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token token = tokenFrom("deploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", SNAPSHOT_ID));
    assertTrue(token.text().contains("V1"));
    assertTrue(token.text().contains(SNAPSHOT_ID));
    assertTrue(token.text().contains("default"));
    assertTrue(token.text().contains("DEPLOYED"));
    assertFalse(token.text().contains("WARNING"));
  }

  @Test
  void deployCreatesSnapshotWhenUnsavedChangesThenDeploysNewId() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), true));
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenReturn(new SnapshotDto(NEW_SNAPSHOT_ID, "V2"));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(NEW_SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token token = tokenFrom("deploy this chain");

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("V2"));
    assertTrue(token.text().contains(NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void deployCreatesSnapshotWhenCurrentSnapshotMissing() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(new ChainDto(CHAIN_ID, "demo", "Demo", null, false));
    when(catalogRestClient.listSnapshots(CHAIN_ID)).thenReturn(List.of());
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenReturn(new SnapshotDto(NEW_SNAPSHOT_ID, "V1"));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(NEW_SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token token = tokenFrom("deploy the chain");

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains(NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void deployReusesLatestListedSnapshotWhenCurrentPointerMissing() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(new ChainDto(CHAIN_ID, "demo", "Demo", null, false));
    when(catalogRestClient.listSnapshots(CHAIN_ID))
        .thenReturn(
            List.of(new SnapshotDto(SNAPSHOT_ID, "V1"), new SnapshotDto(NEW_SNAPSHOT_ID, "V2")));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(NEW_SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token token = tokenFrom("deploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("V2"));
    assertTrue(token.text().contains(NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void deploySnapshot400DoesNotCreateDeployment() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(new ChainDto(CHAIN_ID, "demo", "Demo", null, true));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenThrow(
            catalog400(
                """
                {
                  "errorMessage": "Fields are not properly defined or require mandatory connection",
                  "details": {
                    "chainId": "chain-1",
                    "elementId": "el-http-1",
                    "elementName": "HTTP Trigger"
                  }
                }
                """));

    String text = replyTextFrom("deploy this chain");

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("Fields are not properly defined or require mandatory connection"));
    assertTrue(text.contains("HTTP Trigger"));
    assertFalse(text.contains("DEPLOYED"));
  }

  @Test
  void uniqueNameWithoutOpenGraphEmitsOneDeployDecisionWithoutCreate() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest("Orders")))
        .thenReturn(List.of(chainFolderItem(CHAIN_ID, "Orders")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "Orders", "Orders chain", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    List<ChatEvent> events = eventsFrom("deploy the chain Orders");

    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).createSnapshot(any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.DEPLOY_ACTION, ChatEvent.CANCEL_DEPLOY_ACTION), decision.actions());
    assertTrue(decision.question().contains("Orders"), decision.question());
    assertTrue(decision.question().contains(CHAIN_ID), decision.question());
    assertTrue(decision.question().contains("default"), decision.question());
    assertFalse(decision.question().toLowerCase().contains("yes"));
  }

  @Test
  void uniqueIdWithoutOpenGraphEmitsOneDeployDecisionWithoutCreate() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());
    when(catalogRestClient.getChain(CATALOG_CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CATALOG_CHAIN_ID,
                "Orders",
                "Orders chain",
                new CurrentSnapshotDto(SNAPSHOT_ID, "V1"),
                false));
    when(catalogRestClient.listDeployments(CATALOG_CHAIN_ID)).thenReturn(List.of());

    List<ChatEvent> events = eventsFrom("deploy the chain " + CATALOG_CHAIN_ID);

    verify(catalogRestClient, never()).searchFolderItems(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.DEPLOY_ACTION, ChatEvent.CANCEL_DEPLOY_ACTION), decision.actions());
    assertTrue(decision.question().contains(CATALOG_CHAIN_ID), decision.question());
  }

  @Test
  void twoSearchHitsAskWhichChainAndDoNotDeploy() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest("Order")))
        .thenReturn(
            List.of(
                chainFolderItem("chain-a", "Orders"),
                chainFolderItem("chain-b", "Order-copy")));

    List<ChatEvent> events = eventsFrom("deploy the chain Order");

    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).getChain(any());
    assertEquals(0, events.stream().filter(ChatEvent.Decision.class::isInstance).count());
    String text = ((ChatEvent.Token) events.get(0)).text();
    assertTrue(text.toLowerCase().contains("which"), text);
    assertTrue(text.contains("Orders"), text);
    assertTrue(text.contains("chain-a"), text);
    assertTrue(text.contains("Order-copy"), text);
    assertTrue(text.contains("chain-b"), text);
  }

  @Test
  void openGraphWithoutExistingDeploymentDeploysImmediatelyWithoutCard() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("deploy this chain");

    verify(catalogRestClient, never()).searchFolderItems(any());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", SNAPSHOT_ID));
    assertEquals(0, events.stream().filter(ChatEvent.Decision.class::isInstance).count());
    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void answeringDeployCreatesDeploymentAndReportsStatus() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest("Orders")))
        .thenReturn(List.of(chainFolderItem(CHAIN_ID, "Orders")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "Orders", "Orders chain", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Decision card = onlyDecision(eventsFrom("deploy the chain Orders"));
    ChatEvent.Token token = tokenFrom(deployRequest(card.artifactHash()));

    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", SNAPSHOT_ID));
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertTrue(token.text().contains("DEPLOYED"));
    assertTrue(token.text().contains("default"));
    assertTrue(token.text().contains("V1"));
  }

  @Test
  void uniqueNameAlreadyOnDefaultEmitsRedeployNotDeploy() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());
    when(catalogRestClient.searchFolderItems(new CatalogChainSearchRequest("Orders")))
        .thenReturn(List.of(chainFolderItem(CHAIN_ID, "Orders")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "Orders", "Orders chain", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("deploy the chain Orders");

    verify(catalogRestClient, never()).createDeployment(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.REDEPLOY_ACTION, ChatEvent.CANCEL_REDEPLOY_ACTION), decision.actions());
    assertFalse(decision.actions().contains(ChatEvent.DEPLOY_ACTION));
  }

  @Test
  void existingDefaultDeploymentEmitsRedeployDecisionWithoutCatalogMutate() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deployment("Default", SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("deploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.REDEPLOY_ACTION, ChatEvent.CANCEL_REDEPLOY_ACTION), decision.actions());
    assertTrue(decision.question().contains("demo"), decision.question());
    assertTrue(decision.question().contains(CHAIN_ID), decision.question());
    assertTrue(decision.question().contains("default"), decision.question());
    assertTrue(decision.question().toLowerCase().contains("reuse"), decision.question());
    assertTrue(decision.question().contains("V1"), decision.question());
    assertFalse(decision.question().toLowerCase().contains("yes"));
  }

  @Test
  void openGraphWithExistingDeploymentStillEmitsDecisionWithoutMutating() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), true));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("deploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.REDEPLOY_ACTION, ChatEvent.CANCEL_REDEPLOY_ACTION), decision.actions());
    assertTrue(decision.question().toLowerCase().contains("new snapshot"), decision.question());
  }

  @Test
  void answeringRedeployDeletesThenCreatesAndReportsStatus() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Decision card = onlyDecision(eventsFrom("deploy this chain"));
    ChatEvent.Token token = tokenFrom(redeployRequest(card.artifactHash()));

    InOrder order = inOrder(catalogRestClient);
    order.verify(catalogRestClient).deleteDeployment(CHAIN_ID, "dep-1");
    order.verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", SNAPSHOT_ID));
    verify(catalogRestClient, never()).createSnapshot(any());
    assertTrue(token.text().contains("DEPLOYED"));
    assertTrue(token.text().contains("default"));
    assertTrue(token.text().contains("V1"));
  }

  @Test
  void answeringRedeployStopsOnSnapshot400WithoutDelete() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), true));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenThrow(
            catalog400(
                """
                {
                  "errorMessage": "Fields are not properly defined or require mandatory connection",
                  "details": {
                    "chainId": "chain-1",
                    "elementId": "el-http-1",
                    "elementName": "HTTP Trigger"
                  }
                }
                """));

    ChatEvent.Decision card = onlyDecision(eventsFrom("deploy this chain"));
    String text = replyTextFrom(redeployRequest(card.artifactHash()));

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("Fields are not properly defined or require mandatory connection"));
    assertFalse(text.contains("DEPLOYED"));
  }

  @Test
  void answeringCancelLeavesLiveDeploymentAndIgnoresStaleRedeploy() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Decision card = onlyDecision(eventsFrom("deploy this chain"));
    ChatEvent.Token cancelToken = tokenFrom(cancelRequest(card.artifactHash()));
    ChatEvent.Token stale = tokenFrom(redeployRequest(card.artifactHash()));

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertTrue(cancelToken.text().toLowerCase().contains("unchanged"), cancelToken.text());
    assertFalse(stale.text().contains("DEPLOYED"));
  }

  @Test
  void deployNamedSnapshotV2UsesListedIdWithoutCreateSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listSnapshots(CHAIN_ID))
        .thenReturn(
            List.of(new SnapshotDto(SNAPSHOT_ID, "V1"), new SnapshotDto(NEW_SNAPSHOT_ID, "V2")));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(NEW_SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token token = tokenFrom("deploy V2");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("V2"));
    assertTrue(token.text().contains(NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void unknownNamedSnapshotDoesNotCreateDeployment() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listSnapshots(CHAIN_ID))
        .thenReturn(
            List.of(new SnapshotDto(SNAPSHOT_ID, "V1"), new SnapshotDto(NEW_SNAPSHOT_ID, "V2")));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    String text = replyTextFrom("deploy V9");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("V9"), text);
    assertFalse(text.contains("DEPLOYED"));
  }

  @Test
  void explicitDomainProdPostsToProd() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDomains())
        .thenReturn(List.of(domain("default"), domain("prod")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deployment("prod", SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token token = tokenFrom("deploy this chain to prod");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("prod", SNAPSHOT_ID));
    assertTrue(token.text().contains("prod"));
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void missingDefaultDomainListsNamesAndDoesNotDeploy() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDomains())
        .thenReturn(List.of(domain("prod"), domain("staging")));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    String text = replyTextFrom("deploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("prod"), text);
    assertTrue(text.contains("staging"), text);
    assertFalse(text.contains("DEPLOYED"));
  }

  @Test
  void whichDomainWithDeployListsNamesAndDoesNotEmitRedeploy() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDomains())
        .thenReturn(List.of(domain("default"), domain("prod")));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("which domain should I deploy to");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertEquals(0, events.stream().filter(ChatEvent.Decision.class::isInstance).count());
    String text = ((ChatEvent.Token) events.get(0)).text();
    assertTrue(text.contains("default"), text);
    assertTrue(text.contains("prod"), text);
  }

  @Test
  void followUpProdAfterMissingDefaultDeploysToProdWithoutDeployWord() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID))
        .thenReturn(Optional.empty());
    when(catalogRestClient.listDomains())
        .thenReturn(List.of(domain("prod"), domain("staging")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deployment("prod", SNAPSHOT_ID, "DEPLOYED")));

    String listed = replyTextFrom("deploy this chain");
    ChatEvent.Token token = tokenFrom("prod");

    assertTrue(listed.contains("prod"), listed);
    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("prod", SNAPSHOT_ID));
    assertTrue(token.text().contains("prod"));
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void followUpProdAfterWhichDomainKeepsNamedSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID))
        .thenReturn(Optional.empty());
    when(catalogRestClient.listDomains())
        .thenReturn(List.of(domain("default"), domain("prod")));
    when(catalogRestClient.listSnapshots(CHAIN_ID))
        .thenReturn(
            List.of(new SnapshotDto(SNAPSHOT_ID, "V1"), new SnapshotDto(NEW_SNAPSHOT_ID, "V2")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deployment("prod", NEW_SNAPSHOT_ID, "DEPLOYED")));

    eventsFrom("which domain should I deploy V2 to");
    ChatEvent.Token token = tokenFrom("prod");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("prod", NEW_SNAPSHOT_ID));
    assertTrue(token.text().contains("V2"));
    assertTrue(token.text().contains(NEW_SNAPSHOT_ID));
  }

  @Test
  void unknownDomainAfterWaitListsAgainWithoutCreateDeployment() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID))
        .thenReturn(Optional.empty());
    when(catalogRestClient.listDomains())
        .thenReturn(List.of(domain("prod"), domain("staging")));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    eventsFrom("deploy this chain");
    String text = replyTextFrom("qa");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("prod"), text);
    assertTrue(text.contains("staging"), text);
    assertFalse(text.contains("DEPLOYED"));
  }

  @Test
  void namedSnapshotWithLiveDefaultStillEmitsRedeployWithoutMutate() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listSnapshots(CHAIN_ID))
        .thenReturn(
            List.of(new SnapshotDto(SNAPSHOT_ID, "V1"), new SnapshotDto(NEW_SNAPSHOT_ID, "V2")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("deploy V2");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.REDEPLOY_ACTION, ChatEvent.CANCEL_REDEPLOY_ACTION), decision.actions());
    assertTrue(decision.question().contains("V2"), decision.question());
  }

  @Test
  void answeringRedeployAfterNamedSnapshotUsesNamedId() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listSnapshots(CHAIN_ID))
        .thenReturn(
            List.of(new SnapshotDto(SNAPSHOT_ID, "V1"), new SnapshotDto(NEW_SNAPSHOT_ID, "V2")));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")))
        .thenReturn(List.of(deploymentOnDefault(NEW_SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Decision card = onlyDecision(eventsFrom("deploy V2"));
    ChatEvent.Token token = tokenFrom(redeployRequest(card.artifactHash()));

    InOrder order = inOrder(catalogRestClient);
    order.verify(catalogRestClient).deleteDeployment(CHAIN_ID, "dep-1");
    order.verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", NEW_SNAPSHOT_ID));
    verify(catalogRestClient, never()).createSnapshot(any());
    assertTrue(token.text().contains("V2"));
    assertTrue(token.text().contains(NEW_SNAPSHOT_ID));
  }

  @Test
  void namedSnapshotWithoutOpenGraphEmitsDeployCardWithoutCreate() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());
    when(catalogRestClient.getChain(CATALOG_CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CATALOG_CHAIN_ID,
                "Orders",
                "Orders chain",
                new CurrentSnapshotDto(SNAPSHOT_ID, "V1"),
                false));
    when(catalogRestClient.listSnapshots(CATALOG_CHAIN_ID))
        .thenReturn(
            List.of(new SnapshotDto(SNAPSHOT_ID, "V1"), new SnapshotDto(NEW_SNAPSHOT_ID, "V2")));
    when(catalogRestClient.listDeployments(CATALOG_CHAIN_ID)).thenReturn(List.of());

    List<ChatEvent> events = eventsFrom("deploy V2 on the chain " + CATALOG_CHAIN_ID);

    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).createSnapshot(any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.DEPLOY_ACTION, ChatEvent.CANCEL_DEPLOY_ACTION), decision.actions());
    assertTrue(decision.question().contains(CATALOG_CHAIN_ID), decision.question());
  }

  @Test
  void statusWithTwoDeployedPodsReportsDomainAndDeployed() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(twoPodDeployment("default", SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token token = tokenFrom("deployment status");

    verify(catalogRestClient).listDeployments(CHAIN_ID);
    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertTrue(token.text().contains("DEPLOYED"), token.text());
    assertTrue(token.text().contains("default"), token.text());
  }

  @Test
  void statusWithNoDeploymentsReportsNotDeployed() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    ChatEvent.Token token = tokenFrom("is it deployed");

    verify(catalogRestClient).listDeployments(CHAIN_ID);
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertTrue(token.text().toLowerCase().contains("not deployed"), token.text());
    assertFalse(token.text().contains("DEPLOYED"));
  }

  @Test
  void statusCallsListDeploymentsOnEveryAsk() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Token first = tokenFrom("where is this chain deployed");
    ChatEvent.Token second = tokenFrom("deployment status");

    verify(catalogRestClient, org.mockito.Mockito.times(2)).listDeployments(CHAIN_ID);
    assertTrue(first.text().toLowerCase().contains("not deployed"), first.text());
    assertTrue(second.text().contains("DEPLOYED"), second.text());
    assertTrue(second.text().contains("default"), second.text());
  }

  @Test
  void undeployIntentEmitsDecisionWithoutDelete() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("undeploy this chain");

    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.UNDEPLOY_ACTION, ChatEvent.CANCEL_UNDEPLOY_ACTION), decision.actions());
    assertFalse(decision.actions().contains(ChatEvent.REDEPLOY_ACTION));
    assertFalse(decision.actions().contains(ChatEvent.DEPLOY_ACTION));
    assertTrue(decision.question().contains("demo"), decision.question());
    assertTrue(decision.question().contains(CHAIN_ID), decision.question());
    assertTrue(decision.question().contains("default"), decision.question());
  }

  @Test
  void answeringUndeployDeletesAndReports() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Decision card = onlyDecision(eventsFrom("undeploy this chain"));
    ChatEvent.Token token = tokenFrom(undeployRequest(card.artifactHash()));

    verify(catalogRestClient).deleteDeployment(CHAIN_ID, "dep-1");
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(token.text().toLowerCase().contains("undeploy"), token.text());
    assertTrue(token.text().contains("default"), token.text());
  }

  @Test
  void answeringCancelUndeployLeavesDeployment() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Decision card = onlyDecision(eventsFrom("undeploy this chain"));
    ChatEvent.Token token = tokenFrom(cancelUndeployRequest(card.artifactHash()));

    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(token.text().toLowerCase().contains("place") || token.text().toLowerCase().contains("unchanged"), token.text());
  }

  @Test
  void undeployWithTwoDomainsAsksWhichWithoutDelete() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(
            List.of(
                deployment("dep-default", "default", SNAPSHOT_ID, "DEPLOYED"),
                deployment("dep-prod", "prod", SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("undeploy this chain");

    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertEquals(0, events.stream().filter(ChatEvent.Decision.class::isInstance).count());
    String text = ((ChatEvent.Token) events.get(0)).text();
    assertTrue(text.toLowerCase().contains("which"), text);
    assertTrue(text.contains("default"), text);
    assertTrue(text.contains("prod"), text);
  }

  @Test
  void followUpDomainAfterAmbiguousUndeployEmitsDecisionWithoutDelete() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID))
        .thenReturn(Optional.empty());
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(
            List.of(
                deployment("dep-default", "default", SNAPSHOT_ID, "DEPLOYED"),
                deployment("dep-prod", "prod", SNAPSHOT_ID, "DEPLOYED")));

    eventsFrom("undeploy this chain");
    List<ChatEvent> events = eventsFrom("prod");

    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(
        List.of(ChatEvent.UNDEPLOY_ACTION, ChatEvent.CANCEL_UNDEPLOY_ACTION), decision.actions());
    assertTrue(decision.question().contains("prod"), decision.question());
    assertFalse(decision.question().contains("default"), decision.question());
  }

  @Test
  void snapshotAfterStatusStillCreatesSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());
    when(catalogRestClient.createSnapshot(CHAIN_ID))
        .thenReturn(new SnapshotDto(SNAPSHOT_ID, "V1"));

    tokenFrom("is it deployed");
    ChatEvent.Token token = tokenFrom("take a snapshot");

    verify(catalogRestClient).createSnapshot(CHAIN_ID);
    assertTrue(token.text().contains("V1"));
    assertTrue(token.text().contains(SNAPSHOT_ID));
  }

  @Test
  void deployPollTimeoutReportsProcessing() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "PROCESSING")))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "PROCESSING")))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "PROCESSING")));

    ChatEvent.Token token = tokenFrom("deploy it");

    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", SNAPSHOT_ID));
    assertTrue(token.text().contains("PROCESSING"));
    assertTrue(token.text().contains("default"));
    assertTrue(token.text().contains("V1"));
  }

  @Test
  void failedDeploymentIsReportedAsFailureWithoutLeakingRuntimeError() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    DeploymentDto failed =
        new DeploymentDto(
            "dep-failed",
            CHAIN_ID,
            SNAPSHOT_ID,
            "V1",
            "default",
            new DeploymentRuntimeDto(
                Map.of(
                    "engine-0",
                    new RuntimeStateDto(
                        "FAILED", "HTTP trigger context path already bound on host 10.0.0.7"))));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(failed));

    List<ChatEvent> events = eventsFrom("deploy it");

    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    ChatEvent.Decision decision = (ChatEvent.Decision) events.get(1);
    assertFalse(token.text().startsWith("Deployed"));
    assertTrue(token.text().contains("FAILED"));
    assertFalse(token.text().contains("10.0.0.7"));
    assertEquals(
        List.of(
            ChatEvent.PROPOSE_DEPLOYMENT_FIX_ACTION,
            ChatEvent.DISMISS_DEPLOYMENT_FAILURE_ACTION),
        decision.actions());
    var pin = pinnedFailureStore.find(CONVERSATION_ID, CHAIN_ID).orElseThrow();
    assertEquals(token.text(), pin.safeText());
    assertTrue(pin.diagnosticDetail().contains("10.0.0.7"));
  }

  private ChatEvent.Token tokenFrom(String message) {
    return tokenFrom(chatRequest(message));
  }

  private ChatEvent.Token tokenFrom(ChatRequest request) {
    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(request, CONVERSATION_ID, ScenarioType.DEPLOY_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    sub.awaitCompletion();
    return (ChatEvent.Token) sub.getItems().get(0);
  }

  private String replyTextFrom(String message) {
    return replyTextFrom(chatRequest(message));
  }

  private String replyTextFrom(ChatRequest request) {
    ChatEvent event = eventsFrom(request).get(0);
    if (event instanceof ChatEvent.Token token) {
      return token.text();
    }
    throw new AssertionError("expected Token, got " + event);
  }

  private List<ChatEvent> eventsFrom(String message) {
    return eventsFrom(chatRequest(message));
  }

  private List<ChatEvent> eventsFrom(ChatRequest request) {
    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(request, CONVERSATION_ID, ScenarioType.DEPLOY_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));
    sub.awaitCompletion();
    return sub.getItems();
  }

  private static ChatEvent.Decision onlyDecision(List<ChatEvent> events) {
    List<ChatEvent.Decision> decisions =
        events.stream()
            .filter(ChatEvent.Decision.class::isInstance)
            .map(ChatEvent.Decision.class::cast)
            .toList();
    assertEquals(1, decisions.size(), () -> "expected one Decision, got " + events);
    return decisions.get(0);
  }

  private static ChatRequest deployRequest(String artifactHash) {
    return decisionRequest(ChatEvent.DEPLOY_ACTION, artifactHash);
  }

  private static ChatRequest redeployRequest(String artifactHash) {
    return decisionRequest(ChatEvent.REDEPLOY_ACTION, artifactHash);
  }

  private static ChatRequest cancelRequest(String artifactHash) {
    return decisionRequest(ChatEvent.CANCEL_REDEPLOY_ACTION, artifactHash);
  }

  private static ChatRequest undeployRequest(String artifactHash) {
    return decisionRequest(ChatEvent.UNDEPLOY_ACTION, artifactHash);
  }

  private static ChatRequest cancelUndeployRequest(String artifactHash) {
    return decisionRequest(ChatEvent.CANCEL_UNDEPLOY_ACTION, artifactHash);
  }

  private static ChatRequest decisionRequest(String action, String artifactHash) {
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(action);
    command.setArtifactHash(artifactHash);
    ChatRequest request = new ChatRequest();
    request.setDecision(command);
    return request;
  }

  private static ChatRequest chatRequest(String text) {
    ChatRequest request = new ChatRequest();
    request.setMessage(text);
    return request;
  }

  private static DeploymentDto deploymentOnDefault(String snapshotId, String status) {
    return deployment("dep-1", "default", snapshotId, status);
  }

  private static DeploymentDto deployment(String domain, String snapshotId, String status) {
    return deployment("dep-1", domain, snapshotId, status);
  }

  private static DeploymentDto deployment(
      String id, String domain, String snapshotId, String status) {
    return new DeploymentDto(
        id,
        CHAIN_ID,
        snapshotId,
        "V1",
        domain,
        new DeploymentRuntimeDto(Map.of("engine-0", new RuntimeStateDto(status, null))));
  }

  private static DeploymentDto twoPodDeployment(String domain, String snapshotId, String status) {
    return new DeploymentDto(
        "dep-1",
        CHAIN_ID,
        snapshotId,
        "V1",
        domain,
        new DeploymentRuntimeDto(
            Map.of(
                "engine-0", new RuntimeStateDto(status, null),
                "engine-1", new RuntimeStateDto(status, null))));
  }

  private static CatalogRestClient.FolderItemDto chainFolderItem(String chainId, String name) {
    return new CatalogRestClient.FolderItemDto(chainId, name, name, "CHAIN", List.of());
  }

  private static DomainDto domain(String name) {
    return new DomainDto(name, "CLASSIC");
  }

  private static CatalogNonRetryableResponseException catalog400(String json) {
    Response response =
        Response.status(400)
            .type("application/json")
            .entity(json.getBytes(StandardCharsets.UTF_8))
            .build();
    return new CatalogNonRetryableResponseException(response);
  }
}
