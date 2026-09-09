package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainLoggingPropertiesDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainLoggingPropertiesSetDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CreateDeploymentRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CurrentSnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DomainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentRuntimeDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.EnvironmentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.RuntimeStateDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SystemDto;
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
  private PendingRedeployStore pendingRedeployStore;
  private DeployChainScenario scenario;

  @BeforeEach
  void setUp() {
    chainContextExtractor = mock(ChainContextExtractor.class);
    catalogRestClient = mock(CatalogRestClient.class);
    when(catalogRestClient.listDomains()).thenReturn(List.of(domain("default")));
    when(catalogRestClient.getLoggingProperties(any()))
        .thenReturn(new ChainLoggingPropertiesSetDto(null, null, null));
    pinnedFailureStore = new PinnedFailureStore();
    pendingRedeployStore = new PendingRedeployStore();
    scenario =
        new DeployChainScenario(
            chainContextExtractor,
            catalogRestClient,
            pendingRedeployStore,
            new KnownFailureMapper(),
            pinnedFailureStore,
            0L,
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

    ChatEvent.Token token = tokenAfterSessionLogging("deploy this chain");

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

    ChatEvent.Token token = tokenAfterSessionLogging("deploy this chain");

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

    ChatEvent.Token token = tokenAfterSessionLogging("deploy the chain");

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

    ChatEvent.Token token = tokenAfterSessionLogging("deploy this chain");

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

    String text = replyTextAfterSessionLogging("deploy this chain");

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
  void openGraphWithoutExistingDeploymentAsksSessionLoggingWithoutCreate() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    List<ChatEvent> events = eventsFrom("deploy this chain");

    verify(catalogRestClient, never()).searchFolderItems(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).updateLoggingProperties(any(), any());
    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(ChatEvent.SESSION_LOGGING_ARTIFACT, decision.artifactType());
    assertEquals(ChatEvent.SESSION_LOGGING_ACTIONS, decision.actions());
    assertTrue(decision.question().contains("Current: OFF"), decision.question());
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
    ChatEvent.Token token = tokenAfterSessionLogging(deployRequest(card.artifactHash()));

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
  void existingFailedDeploymentReportsFailureNotHealthyRedeploy() {
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
                    new RuntimeStateDto("FAILED", "HTTP trigger context path already bound"))));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of(failed));

    List<ChatEvent> events = eventsFrom("deploy this chain");

    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    ChatEvent.Decision decision = (ChatEvent.Decision) events.get(1);
    assertTrue(token.text().contains("FAILED"));
    assertFalse(token.text().toLowerCase().contains("already deployed"));
    assertFalse(decision.question().toLowerCase().contains("already deployed"));
    assertFalse(decision.question().toLowerCase().contains("live deployment"));
    assertEquals(
        List.of(
            ChatEvent.PROPOSE_DEPLOYMENT_FIX_ACTION,
            ChatEvent.DISMISS_DEPLOYMENT_FAILURE_ACTION),
        decision.actions());
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
    ChatEvent.Token token = tokenAfterSessionLogging(redeployRequest(card.artifactHash()));

    InOrder order = inOrder(catalogRestClient);
    order.verify(catalogRestClient).updateLoggingProperties(eq(CHAIN_ID), any());
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
    String text = replyTextAfterSessionLogging(redeployRequest(card.artifactHash()));

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

    ChatEvent.Token token = tokenAfterSessionLogging("deploy V2");

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

    ChatEvent.Token token = tokenAfterSessionLogging("deploy this chain to prod");

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
    ChatEvent.Token token = tokenAfterSessionLogging("prod");

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
    ChatEvent.Token token = tokenAfterSessionLogging("prod");

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
    ChatEvent.Token token = tokenAfterSessionLogging(redeployRequest(card.artifactHash()));

    InOrder order = inOrder(catalogRestClient);
    order.verify(catalogRestClient).updateLoggingProperties(eq(CHAIN_ID), any());
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

    ChatEvent.Token token = tokenAfterSessionLogging("deploy it");

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

    ChatEvent.Decision logging = onlyDecision(eventsFrom("deploy it"));
    List<ChatEvent> events =
        eventsFrom(sessionLoggingRequest(logging.artifactHash(), ChatEvent.SESSION_LOGGING_INFO_ACTION));

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

  @ParameterizedTest
  @CsvSource({
    "session-logging-off, OFF",
    "session-logging-error, ERROR",
    "session-logging-info, INFO",
    "session-logging-debug, DEBUG"
  })
  void sessionLoggingActionPostsMatchingLevelThenDeploys(String action, String level) {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.getLoggingProperties(CHAIN_ID))
        .thenReturn(
            new ChainLoggingPropertiesSetDto(
                null,
                null,
                new ChainLoggingPropertiesDto(
                    "OFF", "WARN", List.of("BODY", "HEADERS"), true, false)));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    ChatEvent.Decision card = onlyDecision(eventsFrom("deploy this chain"));
    ChatEvent.Token token = tokenFrom(sessionLoggingRequest(card.artifactHash(), action));

    ArgumentCaptor<ChainLoggingPropertiesDto> posted =
        ArgumentCaptor.forClass(ChainLoggingPropertiesDto.class);
    verify(catalogRestClient).updateLoggingProperties(eq(CHAIN_ID), posted.capture());
    assertEquals(level, posted.getValue().sessionsLoggingLevel());
    assertEquals("WARN", posted.getValue().logLoggingLevel());
    assertEquals(List.of("BODY", "HEADERS"), posted.getValue().logPayload());
    assertTrue(posted.getValue().dptEventsEnabled());
    assertFalse(posted.getValue().maskingEnabled());
    verify(catalogRestClient)
        .createDeployment(CHAIN_ID, new CreateDeploymentRequest("default", SNAPSHOT_ID));
    assertTrue(token.text().contains("DEPLOYED"));
  }

  @Test
  void staleSessionLoggingHashDoesNotPostOrDeploy() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    onlyDecision(eventsFrom("deploy this chain"));
    String text =
        replyTextFrom(sessionLoggingRequest("other-op", ChatEvent.SESSION_LOGGING_INFO_ACTION));

    verify(catalogRestClient, never()).updateLoggingProperties(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("no longer on offer"), text);
  }

  @Test
  void sessionLoggingPostTimeoutDoesNotDeploy() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());
    doThrow(new TimeoutException("CatalogRestClient#updateLoggingProperties timed out"))
        .when(catalogRestClient)
        .updateLoggingProperties(any(), any());

    ChatEvent.Decision card = onlyDecision(eventsFrom("deploy this chain"));
    String text =
        replyTextFrom(sessionLoggingRequest(card.artifactHash(), ChatEvent.SESSION_LOGGING_INFO_ACTION));

    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertEquals(KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, text);
  }

  @Test
  void sessionLoggingGetTimeoutStillShowsCardWithUnknownCurrent() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());
    when(catalogRestClient.getLoggingProperties(CHAIN_ID))
        .thenThrow(new TimeoutException("CatalogRestClient#getLoggingProperties timed out"));

    ChatEvent.Decision decision = onlyDecision(eventsFrom("deploy this chain"));

    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).updateLoggingProperties(any(), any());
    assertEquals(ChatEvent.SESSION_LOGGING_ACTIONS, decision.actions());
    assertTrue(decision.question().contains("Current: unknown"), decision.question());
  }

  @Test
  void processingClassifierMissOffersMaasKafkaTopicsCardNotRefresh() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier orders-in from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    List<ChatEvent> events = eventsAfterSessionLogging("deploy it");

    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    ChatEvent.Decision decision = (ChatEvent.Decision) events.get(1);
    assertTrue(token.text().contains("PROCESSING"), token.text());
    assertEquals(ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT, decision.artifactType());
    assertEquals(
        List.of(
            ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION,
            ChatEvent.DISMISS_MAAS_KAFKA_TOPICS_ACTION),
        decision.actions());
    assertFalse(decision.actions().contains(ChatEvent.REFRESH_DEPLOYMENT_ACTION));
    assertTrue(decision.question().contains("`orders-in` in `qip-dev`"), decision.question());
    assertTrue(
        pendingRedeployStore.find(CONVERSATION_ID).orElseThrow().waitingForMaasTopics());
  }

  @Test
  void processingPhysicalTopicMissOffersMaasKafkaTopicsCard() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING",
                    "Kafka topics (orders-in) not found, check if this topics exists in kafka")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT, decision.artifactType());
    assertFalse(decision.actions().contains(ChatEvent.REFRESH_DEPLOYMENT_ACTION));
  }

  @Test
  void failedClassifierMissOffersTopicCardNotProposeAFix() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "FAILED", "Failed to get classifier orders-in from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT, decision.artifactType());
    assertFalse(decision.actions().contains(ChatEvent.PROPOSE_DEPLOYMENT_FIX_ACTION));
  }

  @Test
  void processingEmptyErrorKeepsRefreshCard() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "PROCESSING")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    verify(catalogRestClient, never()).listElements(any());
    assertEquals(
        List.of(ChatEvent.REFRESH_DEPLOYMENT_ACTION), decision.actions());
    assertEquals(ChatEvent.DEPLOYMENT_FAILURE_ARTIFACT, decision.artifactType());
  }

  @Test
  void deployedDoesNotOfferTopicCard() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    List<ChatEvent> events = eventsAfterSessionLogging("deploy it");

    verify(catalogRestClient, never()).listElements(any());
    assertEquals(1, events.size());
    assertTrue(((ChatEvent.Token) events.get(0)).text().contains("DEPLOYED"));
    assertTrue(pendingRedeployStore.find(CONVERSATION_ID).isEmpty());
  }

  @Test
  void splitPhysicalTopicFragmentsAcrossPodsKeepRefreshNotTopicCard() {
    stubOpenChain();
    DeploymentDto split =
        new DeploymentDto(
            "dep-1",
            CHAIN_ID,
            SNAPSHOT_ID,
            "V1",
            "default",
            new DeploymentRuntimeDto(
                Map.of(
                    "engine-0",
                    new RuntimeStateDto("PROCESSING", "Kafka topics (orders-in"),
                    "engine-1",
                    new RuntimeStateDto("PROCESSING", ") not found"))));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(split));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(List.of(ChatEvent.REFRESH_DEPLOYMENT_ACTION), decision.actions());
    assertEquals(ChatEvent.DEPLOYMENT_FAILURE_ARTIFACT, decision.artifactType());
    assertFalse(decision.actions().contains(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION));
  }

  @Test
  void mixedPodErrorsStillOfferTopicCardWhenKafkaMissIsPresent() {
    stubOpenChain();
    DeploymentDto mixed =
        new DeploymentDto(
            "dep-1",
            CHAIN_ID,
            SNAPSHOT_ID,
            "V1",
            "default",
            new DeploymentRuntimeDto(
                Map.of(
                    "engine-0",
                    new RuntimeStateDto(
                        "PROCESSING", "Failed to get classifier orders-in from MaaS"),
                    "engine-1",
                    new RuntimeStateDto("PROCESSING", "HTTP trigger context path already bound"))));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(mixed));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT, decision.artifactType());
  }

  @Test
  void mixedPodPhysicalTopicMissStillOffersTopicCard() {
    stubOpenChain();
    DeploymentDto mixed =
        new DeploymentDto(
            "dep-1",
            CHAIN_ID,
            SNAPSHOT_ID,
            "V1",
            "default",
            new DeploymentRuntimeDto(
                Map.of(
                    "engine-0",
                    new RuntimeStateDto(
                        "PROCESSING",
                        "Kafka topics (orders-in) not found, check if this topics exists in kafka"),
                    "engine-1",
                    new RuntimeStateDto("PROCESSING", "HTTP trigger context path already bound"))));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(mixed));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT, decision.artifactType());
  }

  @Test
  void manualKafkaIsSkippedSoProcessingKeepsRefresh() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier orders-in from MaaS")));
    CatalogElementResponseDto manual = maasKafkaTrigger("orders-in", "qip-dev");
    manual.properties.put("connectionSourceType", "manual");
    when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(List.of(manual));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(
        List.of(ChatEvent.REFRESH_DEPLOYMENT_ACTION), decision.actions());
    assertEquals(ChatEvent.DEPLOYMENT_FAILURE_ARTIFACT, decision.artifactType());
  }

  @Test
  void namedClassifierRestrictsStandaloneOffer() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier orders-in from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(
            List.of(
                maasKafkaTrigger("orders-in", "qip-dev"),
                maasKafkaSender("orders-out", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertTrue(decision.question().contains("`orders-in` in `qip-dev`"), decision.question());
    assertFalse(decision.question().contains("orders-out"), decision.question());
  }

  @Test
  void asyncApiTriggerAndKafkaSenderOfferBothClassifiersOnOneCard() {
    stubMaasSystem("sys-wfms", "env-maas");
    ChatEvent.Decision decision =
        givenMaasTopicsCard(
            List.of(
                kafkaCatalogHop(
                    "el-async", "async-api-trigger", "sys-wfms", "wfms-start", "qip-dev"),
                maasKafkaSender("wfms-result", "qip-dev")),
            "Kafka topics (wfms-start) not found, check if this topics exists in kafka");

    assertTrue(decision.question().contains("`wfms-start` in `qip-dev`"), decision.question());
    assertTrue(decision.question().contains("`wfms-result` in `qip-dev`"), decision.question());
  }

  @Test
  void duplicateCatalogAndStandaloneClassifiersPostOnce() {
    stubMaasSystem("sys-wfms", "env-maas");
    ChatEvent.Decision card =
        givenMaasTopicsCard(
            List.of(
                kafkaCatalogHop(
                    "el-async", "async-api-trigger", "sys-wfms", "orders-in", "qip-dev"),
                maasKafkaTrigger("orders-in", "qip-dev")),
            "Kafka topics (orders-in) not found, check if this topics exists in kafka");
    clearInvocations(catalogRestClient);
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    eventsFrom(
        decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    verify(catalogRestClient).createMaasKafkaTopic("qip-dev", "orders-in");
  }

  @Test
  void manualCatalogEnvWithLeftoverClassifierIsSkipped() {
    stubManualSystem("sys-wfms", "env-manual");
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier leftover from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(
            List.of(
                kafkaCatalogHop(
                    "el-async", "async-api-trigger", "sys-wfms", "leftover", "qip-dev")));
    stubOpenChain();

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(List.of(ChatEvent.REFRESH_DEPLOYMENT_ACTION), decision.actions());
    assertEquals(ChatEvent.DEPLOYMENT_FAILURE_ARTIFACT, decision.artifactType());
  }

  @Test
  void namedClassifierRestrictsCatalogOffer() {
    stubMaasSystem("sys-wfms", "env-maas");
    ChatEvent.Decision named =
        givenMaasTopicsCard(
            List.of(
                kafkaCatalogHop(
                    "el-async", "async-api-trigger", "sys-wfms", "wfms-start", "qip-dev"),
                kafkaCatalogHop(
                    "el-call", "service-call", "sys-wfms", "wfms-result", "qip-dev")),
            "Failed to get classifier wfms-start from MaaS");

    assertTrue(named.question().contains("`wfms-start` in `qip-dev`"), named.question());
    assertFalse(named.question().contains("wfms-result"), named.question());
  }

  @Test
  void physicalTopicMissOffersAllCollectedCatalogClassifiers() {
    stubMaasSystem("sys-wfms", "env-maas");
    ChatEvent.Decision physical =
        givenMaasTopicsCard(
            List.of(
                kafkaCatalogHop(
                    "el-async", "async-api-trigger", "sys-wfms", "wfms-start", "qip-dev"),
                kafkaCatalogHop(
                    "el-call", "service-call", "sys-wfms", "wfms-result", "qip-dev")),
            "Kafka topics (physical-name) not found, check if this topics exists in kafka");

    assertTrue(physical.question().contains("`wfms-start` in `qip-dev`"), physical.question());
    assertTrue(physical.question().contains("`wfms-result` in `qip-dev`"), physical.question());
  }

  @Test
  void mixedPodNamedClassifierRestrictionWinsOverPhysicalMiss() {
    stubMaasSystem("sys-wfms", "env-maas");
    stubOpenChain();
    DeploymentDto mixed =
        new DeploymentDto(
            "dep-1",
            CHAIN_ID,
            SNAPSHOT_ID,
            "V1",
            "default",
            new DeploymentRuntimeDto(
                Map.of(
                    "engine-0",
                    new RuntimeStateDto(
                        "PROCESSING", "Failed to get classifier wfms-start from MaaS"),
                    "engine-1",
                    new RuntimeStateDto(
                        "PROCESSING",
                        "Kafka topics (physical-name) not found, check if this topics exists in kafka"))));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(mixed));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(
            List.of(
                kafkaCatalogHop(
                    "el-async", "async-api-trigger", "sys-wfms", "wfms-start", "qip-dev"),
                kafkaCatalogHop(
                    "el-call", "service-call", "sys-wfms", "wfms-result", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertTrue(decision.question().contains("`wfms-start` in `qip-dev`"), decision.question());
    assertFalse(decision.question().contains("wfms-result"), decision.question());
  }

  @Test
  void dismissMaasKafkaTopicsLeavesChainUnchanged() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier orders-in from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision card = onlyDecision(eventsAfterSessionLogging("deploy it"));
    clearInvocations(catalogRestClient);
    ChatEvent.Token token =
        tokenFrom(
            decisionRequest(
                ChatEvent.DISMISS_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).createMaasKafkaTopic(any(), any());
    verify(catalogRestClient, never()).listElements(any());
    assertTrue(token.text().contains("left the chain unchanged"), token.text());
    assertTrue(pendingRedeployStore.find(CONVERSATION_ID).isEmpty());
  }

  @Test
  void staleMaasKafkaTopicsHashDoesNotClearPending() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier orders-in from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    onlyDecision(eventsAfterSessionLogging("deploy it"));
    clearInvocations(catalogRestClient);
    String text =
        replyTextFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, "other-op"));

    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).createMaasKafkaTopic(any(), any());
    verify(catalogRestClient, never()).listElements(any());
    assertTrue(text.contains("no longer on offer"), text);
    assertTrue(
        pendingRedeployStore.find(CONVERSATION_ID).orElseThrow().waitingForMaasTopics());
  }

  @Test
  void createMaasKafkaTopicsPostsEachPairThenListDeployments() {
    ChatEvent.Decision card =
        givenMaasTopicsCard(
            List.of(
                maasKafkaTrigger("orders-in", "qip-dev"),
                maasKafkaSender("orders-out", "qip-dev")),
            "Kafka topics (orders-in) not found, check if this topics exists in kafka");
    clearInvocations(catalogRestClient);
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    eventsFrom(
        decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    InOrder order = inOrder(catalogRestClient);
    order.verify(catalogRestClient).createMaasKafkaTopic("qip-dev", "orders-in");
    order.verify(catalogRestClient).createMaasKafkaTopic("qip-dev", "orders-out");
    order.verify(catalogRestClient).listDeployments(CHAIN_ID);
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).listElements(any());
  }

  @Test
  void createMaasKafkaTopicsThenDeployedIsSuccessTokenWithoutCards() {
    ChatEvent.Decision card = givenMaasTopicsCard();
    clearInvocations(catalogRestClient);
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events =
        eventsFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    assertEquals(2, events.size(), () -> "expected created + DEPLOYED tokens, got " + events);
    assertTrue(
        ((ChatEvent.Token) events.get(0)).text().contains("Created Kafka MaaS topic"),
        ((ChatEvent.Token) events.get(0)).text());
    assertTrue(
        ((ChatEvent.Token) events.get(0)).text().contains("`orders-in` in `qip-dev`"),
        ((ChatEvent.Token) events.get(0)).text());
    ChatEvent.Token deployed = (ChatEvent.Token) events.get(1);
    assertTrue(deployed.text().contains("DEPLOYED"), deployed.text());
    assertTrue(deployed.text().contains(SNAPSHOT_ID), deployed.text());
    assertFalse(events.stream().anyMatch(ChatEvent.Decision.class::isInstance));
    assertTrue(pendingRedeployStore.find(CONVERSATION_ID).isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"PROCESSING", "FAILED"})
  void createMaasKafkaTopicsThenNotDeployedOffersRedeployOnly(String status) {
    ChatEvent.Decision card = givenMaasTopicsCard();
    clearInvocations(catalogRestClient);
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(
            List.of(
                deploymentWithError(
                    status, "Failed to get classifier orders-in from MaaS")));

    List<ChatEvent> events =
        eventsFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    ChatEvent.Decision decision = onlyDecision(events);
    assertEquals(ChatEvent.REDEPLOY_ARTIFACT, decision.artifactType());
    assertEquals(
        List.of(ChatEvent.REDEPLOY_ACTION, ChatEvent.CANCEL_REDEPLOY_ACTION),
        decision.actions());
    assertFalse(decision.actions().contains(ChatEvent.DEPLOY_ACTION));
    assertFalse(decision.actions().contains(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION));
    verify(catalogRestClient, never()).listElements(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertTrue(
        pendingRedeployStore.find(CONVERSATION_ID).orElseThrow().existingDeploymentId()
            != null);
  }

  @Test
  void createMaasKafkaTopicsThenMissingDeploymentIsTokenWithoutUndeploy() {
    ChatEvent.Decision card = givenMaasTopicsCard();
    clearInvocations(catalogRestClient);
    when(catalogRestClient.listDeployments(CHAIN_ID)).thenReturn(List.of());

    List<ChatEvent> events =
        eventsFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    assertTrue(events.stream().noneMatch(ChatEvent.Decision.class::isInstance), () -> "" + events);
    String text =
        events.stream()
            .filter(ChatEvent.Token.class::isInstance)
            .map(ChatEvent.Token.class::cast)
            .map(ChatEvent.Token::text)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    assertTrue(text.contains("no longer present"), text);
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).listElements(any());
    assertTrue(pendingRedeployStore.find(CONVERSATION_ID).isEmpty());
  }

  @Test
  void createMaasKafkaTopicsThenRedeployStillAsksSessionLoggingBeforeDeploy() {
    ChatEvent.Decision card = givenMaasTopicsCard();
    clearInvocations(catalogRestClient);
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "PROCESSING")));

    ChatEvent.Decision redeploy =
        onlyDecision(
            eventsFrom(
                decisionRequest(
                    ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash())));
    ChatEvent.Decision logging = onlyDecision(eventsFrom(redeployRequest(redeploy.artifactHash())));

    assertEquals(ChatEvent.SESSION_LOGGING_ARTIFACT, logging.artifactType());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
  }

  @Test
  void blankNamespaceDoesNotPostMaasKafkaTopic() {
    ChatEvent.Decision card =
        givenMaasTopicsCard(
            List.of(maasKafkaTrigger("orders-in", "")),
            "Failed to get classifier orders-in from MaaS");
    clearInvocations(catalogRestClient);

    String text =
        replyTextFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    verify(catalogRestClient, never()).createMaasKafkaTopic(any(), any());
    verify(catalogRestClient, never()).listDeployments(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("Namespace is missing"), text);
    assertTrue(text.contains("`orders-in`"), text);
  }

  @Test
  void blankElementNamespaceFallsBackToConfiguredNamespace() {
    scenario =
        new DeployChainScenario(
            chainContextExtractor,
            catalogRestClient,
            pendingRedeployStore,
            new KnownFailureMapper(),
            pinnedFailureStore,
            0L,
            0L,
            "qip-from-env");
    ChatEvent.Decision card =
        givenMaasTopicsCard(
            List.of(maasKafkaTrigger("orders-in", "")),
            "Failed to get classifier orders-in from MaaS");
    clearInvocations(catalogRestClient);
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));

    eventsFrom(
        decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    verify(catalogRestClient).createMaasKafkaTopic("qip-from-env", "orders-in");
  }

  @ParameterizedTest
  @MethodSource("maasCreateFailures")
  void maasCreateFailureStaysTokenAndCompletesSse(Throwable error, String expectedText) {
    ChatEvent.Decision card = givenMaasTopicsCard();
    clearInvocations(catalogRestClient);
    doThrow(error).when(catalogRestClient).createMaasKafkaTopic(any(), any());

    List<ChatEvent> events =
        eventsFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    ChatEvent.Token token =
        events.stream()
            .filter(ChatEvent.Token.class::isInstance)
            .map(ChatEvent.Token.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected Token, got " + events));
    assertEquals(expectedText, token.text());
    ChatEvent.Decision remainder =
        events.stream()
            .filter(ChatEvent.Decision.class::isInstance)
            .map(ChatEvent.Decision.class::cast)
            .filter(item -> ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT.equals(item.artifactType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected remainder card, got " + events));
    assertFalse(card.artifactHash().equals(remainder.artifactHash()), remainder.artifactHash());
    verify(catalogRestClient, never()).listDeployments(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertFalse(events.stream().anyMatch(ChatEvent.Error.class::isInstance));
    assertEquals(
        remainder.artifactHash(),
        pendingRedeployStore.find(CONVERSATION_ID).orElseThrow().operationId());
  }

  @Test
  void firstMaasCreateFailureStillPostsRemainingPairAndOffersNewCard() {
    ChatEvent.Decision card =
        givenMaasTopicsCard(
            List.of(
                maasKafkaTrigger("orders-in", "qip-dev"),
                maasKafkaSender("orders-out", "qip-dev")),
            "Kafka topics (orders-in) not found, check if this topics exists in kafka");
    clearInvocations(catalogRestClient);
    doThrow(new TimeoutException("CatalogRestClient#createMaasKafkaTopic timed out"))
        .when(catalogRestClient)
        .createMaasKafkaTopic("qip-dev", "orders-in");

    List<ChatEvent> events =
        eventsFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    verify(catalogRestClient).createMaasKafkaTopic("qip-dev", "orders-in");
    verify(catalogRestClient).createMaasKafkaTopic("qip-dev", "orders-out");
    verify(catalogRestClient, never()).listDeployments(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    ChatEvent.Decision remainder =
        events.stream()
            .filter(ChatEvent.Decision.class::isInstance)
            .map(ChatEvent.Decision.class::cast)
            .filter(item -> ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT.equals(item.artifactType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected remainder card, got " + events));
    assertFalse(card.artifactHash().equals(remainder.artifactHash()), remainder.artifactHash());
    assertTrue(remainder.question().contains("`orders-in` in `qip-dev`"), remainder.question());
    assertFalse(remainder.question().contains("orders-out"), remainder.question());
    assertEquals(
        remainder.artifactHash(),
        pendingRedeployStore.find(CONVERSATION_ID).orElseThrow().operationId());
    String text =
        events.stream()
            .filter(ChatEvent.Token.class::isInstance)
            .map(ChatEvent.Token.class::cast)
            .map(ChatEvent.Token::text)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    assertTrue(text.contains(KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE), text);
    assertTrue(text.contains("Created Kafka MaaS topic"), text);
    assertTrue(text.contains("`orders-out` in `qip-dev`"), text);
  }

  @ParameterizedTest
  @MethodSource("tenantTrueValues")
  void tenantEnabledClassifierIsSkippedAndMentioned(Object tenantValue) {
    CatalogElementResponseDto tenant = maasKafkaTrigger("orders-tenant", "qip-dev");
    tenant.properties.put("maasClassifierTenantEnabled", tenantValue);
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING",
                    "Kafka topics (orders-in) not found, check if this topics exists in kafka")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(tenant, maasKafkaSender("orders-in", "qip-dev")));

    List<ChatEvent> events = eventsAfterSessionLogging("deploy it");

    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    ChatEvent.Decision decision = (ChatEvent.Decision) events.get(1);
    assertTrue(token.text().contains("I skipped `orders-tenant`"), token.text());
    assertTrue(token.text().contains("tenant is enabled"), token.text());
    assertEquals(ChatEvent.MAAS_KAFKA_TOPICS_ARTIFACT, decision.artifactType());
    assertTrue(decision.question().contains("`orders-in` in `qip-dev`"), decision.question());
    assertFalse(decision.question().contains("orders-tenant"), decision.question());
    clearInvocations(catalogRestClient);
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));
    eventsFrom(
        decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, decision.artifactHash()));
    verify(catalogRestClient).createMaasKafkaTopic("qip-dev", "orders-in");
    verify(catalogRestClient, never()).createMaasKafkaTopic(eq("qip-dev"), eq("orders-tenant"));
  }

  @Test
  void tenantOnlyKafkaMissKeepsRefreshCard() {
    CatalogElementResponseDto tenant = maasKafkaTrigger("orders-tenant", "qip-dev");
    tenant.properties.put("maasClassifierTenantEnabled", true);
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier orders-tenant from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(List.of(tenant));

    List<ChatEvent> events = eventsAfterSessionLogging("deploy it");
    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    ChatEvent.Decision decision = onlyDecision(events);

    assertTrue(token.text().contains("I skipped `orders-tenant`"), token.text());
    assertTrue(token.text().contains("tenant is enabled"), token.text());
    assertEquals(List.of(ChatEvent.REFRESH_DEPLOYMENT_ACTION), decision.actions());
    assertEquals(ChatEvent.DEPLOYMENT_FAILURE_ARTIFACT, decision.artifactType());
  }

  @Test
  void tenantOnlyFailedKafkaMissKeepsProposeAFix() {
    CatalogElementResponseDto tenant = maasKafkaTrigger("orders-tenant", "qip-dev");
    tenant.properties.put("maasClassifierTenantEnabled", "true");
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "FAILED", "Failed to get classifier orders-tenant from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(List.of(tenant));

    List<ChatEvent> events = eventsAfterSessionLogging("deploy it");
    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    ChatEvent.Decision decision = onlyDecision(events);

    assertTrue(token.text().contains("I skipped `orders-tenant`"), token.text());
    assertTrue(token.text().contains("tenant is enabled"), token.text());
    assertTrue(decision.actions().contains(ChatEvent.PROPOSE_DEPLOYMENT_FIX_ACTION));
    assertFalse(decision.actions().contains(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION));
  }

  @Test
  void unresolvedPlaceholderDoesNotPostAndFallsBackWhenNothingCreatable() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING", "Failed to get classifier #{ordersTopic} from MaaS")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("#{ordersTopic}", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertEquals(List.of(ChatEvent.REFRESH_DEPLOYMENT_ACTION), decision.actions());
    verify(catalogRestClient, never()).createMaasKafkaTopic(any(), any());
  }

  @Test
  void unresolvedPlaceholderIsSkippedWhenOtherPairsRemain() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING",
                    "Kafka topics (orders-in) not found, check if this topics exists in kafka")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(
            List.of(
                maasKafkaTrigger("#{ordersTopic}", "qip-dev"),
                maasKafkaSender("orders-out", "qip-dev")));

    List<ChatEvent> events = eventsAfterSessionLogging("deploy it");
    ChatEvent.Token token = (ChatEvent.Token) events.get(0);
    ChatEvent.Decision decision = (ChatEvent.Decision) events.get(1);
    assertTrue(token.text().contains("I skipped `#{ordersTopic}`"), token.text());
    assertTrue(token.text().contains("unresolved `#{...}` placeholder"), token.text());
    assertTrue(decision.question().contains("`orders-out` in `qip-dev`"), decision.question());
    assertFalse(decision.question().contains("#{ordersTopic}"), decision.question());
    clearInvocations(catalogRestClient);
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deploymentOnDefault(SNAPSHOT_ID, "DEPLOYED")));
    eventsFrom(
        decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, decision.artifactHash()));
    verify(catalogRestClient).createMaasKafkaTopic("qip-dev", "orders-out");
    verify(catalogRestClient, never()).createMaasKafkaTopic(eq("qip-dev"), eq("#{ordersTopic}"));
  }

  @Test
  void brokerUnavailableErrorKeepsRefreshNotTopicCard() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "PROCESSING",
                    "Kafka predeploy check is failed. Connection configuration is invalid, "
                        + "topics not found or broker is unavailable")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    verify(catalogRestClient, never()).listElements(any());
    assertEquals(List.of(ChatEvent.REFRESH_DEPLOYMENT_ACTION), decision.actions());
    assertFalse(decision.actions().contains(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION));
  }

  @Test
  void brokerUnavailableFailedKeepsProposeAFixNotTopicCard() {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(
            List.of(
                deploymentWithError(
                    "FAILED",
                    "Kafka predeploy check is failed. Connection configuration is invalid, "
                        + "topics not found or broker is unavailable")));
    when(catalogRestClient.listElements(CHAIN_ID))
        .thenReturn(List.of(maasKafkaTrigger("orders-in", "qip-dev")));

    ChatEvent.Decision decision = onlyDecision(eventsAfterSessionLogging("deploy it"));

    assertTrue(decision.actions().contains(ChatEvent.PROPOSE_DEPLOYMENT_FIX_ACTION));
    assertFalse(decision.actions().contains(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION));
  }

  @Test
  void createAfterConversationTurnResetDoesNotPost() {
    ChatEvent.Decision card = givenMaasTopicsCard();
    pendingRedeployStore.clear(CONVERSATION_ID);
    clearInvocations(catalogRestClient);

    String text =
        replyTextFrom(
            decisionRequest(ChatEvent.CREATE_MAAS_KAFKA_TOPICS_ACTION, card.artifactHash()));

    verify(catalogRestClient, never()).createMaasKafkaTopic(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    assertTrue(text.contains("no longer on offer"), text);
  }

  @ParameterizedTest
  @MethodSource("topicWaitMutationActions")
  void deployRedeployUndeployAgainstTopicWaitDoesNotPostOrUndeploy(String action) {
    ChatEvent.Decision card = givenMaasTopicsCard();
    clearInvocations(catalogRestClient);

    String text = replyTextFrom(decisionRequest(action, card.artifactHash()));

    verify(catalogRestClient, never()).createMaasKafkaTopic(any(), any());
    verify(catalogRestClient, never()).deleteDeployment(any(), any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(text.contains("no longer on offer"), text);
    assertTrue(
        pendingRedeployStore.find(CONVERSATION_ID).orElseThrow().waitingForMaasTopics());
    assertEquals(
        card.artifactHash(),
        pendingRedeployStore.find(CONVERSATION_ID).orElseThrow().operationId());
  }

  private static Stream<String> topicWaitMutationActions() {
    return Stream.of(
        ChatEvent.DEPLOY_ACTION, ChatEvent.REDEPLOY_ACTION, ChatEvent.UNDEPLOY_ACTION);
  }

  private static Stream<Object> tenantTrueValues() {
    return Stream.of(true, "true", "TRUE");
  }

  private static Stream<Arguments> maasCreateFailures() {
    return Stream.of(
        Arguments.of(
            new TimeoutException("CatalogRestClient#createMaasKafkaTopic timed out"),
            KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE),
        Arguments.of(catalog400(""), "Couldn't create this Kafka MaaS topic."),
        Arguments.of(
            new WebApplicationException(Response.status(429).build()),
            "Couldn't create Kafka MaaS topic `orders-in` in `qip-dev`."),
        Arguments.of(
            new WebApplicationException(Response.status(500).build()),
            "Couldn't create Kafka MaaS topic `orders-in` in `qip-dev`."),
        Arguments.of(
            new ProcessingException(new ConnectException("Connection refused")),
            "Couldn't create Kafka MaaS topic `orders-in` in `qip-dev`."),
        Arguments.of(
            new LinkageError("maas"),
            "Couldn't create Kafka MaaS topic `orders-in` in `qip-dev`."));
  }

  private List<ChatEvent> eventsAfterSessionLogging(String message) {
    ChatEvent.Decision card = onlyDecision(eventsFrom(message));
    return eventsFrom(
        sessionLoggingRequest(card.artifactHash(), ChatEvent.SESSION_LOGGING_INFO_ACTION));
  }

  private ChatEvent.Decision givenMaasTopicsCard() {
    return givenMaasTopicsCard(
        List.of(maasKafkaTrigger("orders-in", "qip-dev")),
        "Failed to get classifier orders-in from MaaS");
  }

  private ChatEvent.Decision givenMaasTopicsCard(
      List<CatalogElementResponseDto> elements, String error) {
    stubOpenChain();
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of())
        .thenReturn(List.of(deploymentWithError("PROCESSING", error)));
    when(catalogRestClient.listElements(CHAIN_ID)).thenReturn(elements);
    return onlyDecision(eventsAfterSessionLogging("deploy it"));
  }

  private void stubOpenChain() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.getChain(CHAIN_ID))
        .thenReturn(
            new ChainDto(
                CHAIN_ID, "demo", "Demo", new CurrentSnapshotDto(SNAPSHOT_ID, "V1"), false));
  }

  private static DeploymentDto deploymentWithError(String status, String error) {
    return new DeploymentDto(
        "dep-1",
        CHAIN_ID,
        SNAPSHOT_ID,
        "V1",
        "default",
        new DeploymentRuntimeDto(Map.of("engine-0", new RuntimeStateDto(status, error))));
  }

  private static CatalogElementResponseDto maasKafkaTrigger(String classifier, String namespace) {
    return kafkaElement("el-trigger", "kafka-trigger-2", classifier, namespace, "maas");
  }

  private static CatalogElementResponseDto maasKafkaSender(String classifier, String namespace) {
    return kafkaElement("el-sender", "kafka-sender-2", classifier, namespace, "maas");
  }

  private static CatalogElementResponseDto kafkaElement(
      String id, String type, String classifier, String namespace, String sourceType) {
    CatalogElementResponseDto element = new CatalogElementResponseDto();
    element.id = id;
    element.type = type;
    element.properties =
        new java.util.LinkedHashMap<>(
            Map.of(
                "connectionSourceType", sourceType,
                "topicsClassifierName", classifier,
                "maasClassifierNamespace", namespace));
    return element;
  }

  private static CatalogElementResponseDto kafkaCatalogHop(
      String id, String type, String systemId, String classifier, String namespace) {
    CatalogElementResponseDto element = new CatalogElementResponseDto();
    element.id = id;
    element.type = type;
    java.util.LinkedHashMap<String, Object> async = new java.util.LinkedHashMap<>();
    async.put("maas.classifier.name", classifier);
    if (namespace != null && !namespace.isBlank()) {
      async.put("maas.classifier.namespace", namespace);
    }
    element.properties =
        new java.util.LinkedHashMap<>(
            Map.of(
                "integrationSystemId",
                systemId,
                "integrationOperationProtocolType",
                "kafka",
                "integrationOperationAsyncProperties",
                async));
    return element;
  }

  private void stubMaasSystem(String systemId, String envId) {
    when(catalogRestClient.getSystem(systemId))
        .thenReturn(new SystemDto(systemId, "Kafka", "INTERNAL", "kafka", envId));
    when(catalogRestClient.getEnvironments(systemId))
        .thenReturn(List.of(new EnvironmentDto(envId, "maas", null, "MAAS_BY_CLASSIFIER")));
  }

  private void stubManualSystem(String systemId, String envId) {
    when(catalogRestClient.getSystem(systemId))
        .thenReturn(new SystemDto(systemId, "Kafka", "INTERNAL", "kafka", envId));
    when(catalogRestClient.getEnvironments(systemId))
        .thenReturn(
            List.of(new EnvironmentDto(envId, "manual", "localhost:9092", "MANUAL")));
  }

  private ChatEvent.Token tokenAfterSessionLogging(String message) {
    return tokenAfterSessionLogging(chatRequest(message));
  }

  private ChatEvent.Token tokenAfterSessionLogging(ChatRequest request) {
    ChatEvent.Decision card = onlyDecision(eventsFrom(request));
    assertEquals(ChatEvent.SESSION_LOGGING_ARTIFACT, card.artifactType());
    assertEquals(ChatEvent.SESSION_LOGGING_ACTIONS, card.actions());
    return tokenFrom(sessionLoggingRequest(card.artifactHash(), ChatEvent.SESSION_LOGGING_INFO_ACTION));
  }

  private String replyTextAfterSessionLogging(String message) {
    return replyTextAfterSessionLogging(chatRequest(message));
  }

  private String replyTextAfterSessionLogging(ChatRequest request) {
    ChatEvent.Decision card = onlyDecision(eventsFrom(request));
    return replyTextFrom(
        sessionLoggingRequest(card.artifactHash(), ChatEvent.SESSION_LOGGING_INFO_ACTION));
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

  private static ChatRequest sessionLoggingRequest(String artifactHash, String action) {
    return decisionRequest(action, artifactHash);
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
