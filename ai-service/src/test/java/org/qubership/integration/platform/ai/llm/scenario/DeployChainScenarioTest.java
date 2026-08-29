package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.ChainDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CreateDeploymentRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.CurrentSnapshotDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentRuntimeDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.RuntimeStateDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.model.ScenarioType;

class DeployChainScenarioTest {

  private static final String CONVERSATION_ID = "conv-deploy-chain";
  private static final String CHAIN_ID = "chain-1";
  private static final String SNAPSHOT_ID = "11111111-1111-1111-1111-111111111111";
  private static final String NEW_SNAPSHOT_ID = "22222222-2222-2222-2222-222222222222";

  private ChainContextExtractor chainContextExtractor;
  private CatalogRestClient catalogRestClient;
  private DeployChainScenario scenario;

  @BeforeEach
  void setUp() {
    chainContextExtractor = mock(ChainContextExtractor.class);
    catalogRestClient = mock(CatalogRestClient.class);
    scenario =
        new DeployChainScenario(
            chainContextExtractor, catalogRestClient, new ObjectMapper(), 3, 0L);
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
  void nonSnapshotTurnDoesNotCreateSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));

    ChatEvent.Token token = tokenFrom("undeploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).listSnapshots(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
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
  void existingDefaultDeploymentDoesNotRedeployOrEmitDecision() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));
    when(catalogRestClient.listDeployments(CHAIN_ID))
        .thenReturn(List.of(deployment("Default", SNAPSHOT_ID, "DEPLOYED")));

    List<ChatEvent> events = eventsFrom("deploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).createDeployment(any(), any());
    assertTrue(events.stream().noneMatch(ChatEvent.Decision.class::isInstance));
    String text = tokenText(events);
    assertTrue(text.toLowerCase().contains("already"));
    assertTrue(text.contains("default"));
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

  private ChatEvent.Token tokenFrom(String message) {
    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest(message), CONVERSATION_ID, ScenarioType.DEPLOY_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(1));
    sub.awaitCompletion();
    return (ChatEvent.Token) sub.getItems().get(0);
  }

  private String replyTextFrom(String message) {
    ChatEvent event = eventsFrom(message).get(0);
    if (event instanceof ChatEvent.Token token) {
      return token.text();
    }
    if (event instanceof ChatEvent.Error error) {
      return error.message();
    }
    throw new AssertionError("expected token or error, got " + event);
  }

  private List<ChatEvent> eventsFrom(String message) {
    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest(message), CONVERSATION_ID, ScenarioType.DEPLOY_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));
    sub.awaitCompletion();
    return sub.getItems();
  }

  private static String tokenText(List<ChatEvent> events) {
    return events.stream()
        .filter(ChatEvent.Token.class::isInstance)
        .map(event -> ((ChatEvent.Token) event).text())
        .findFirst()
        .orElse("");
  }

  private static ChatRequest chatRequest(String text) {
    ChatRequest request = new ChatRequest();
    request.setMessage(text);
    return request;
  }

  private static DeploymentDto deploymentOnDefault(String snapshotId, String status) {
    return deployment("default", snapshotId, status);
  }

  private static DeploymentDto deployment(String domain, String snapshotId, String status) {
    return new DeploymentDto(
        "dep-1",
        CHAIN_ID,
        snapshotId,
        "V1",
        domain,
        new DeploymentRuntimeDto(Map.of("engine-0", new RuntimeStateDto(status, null))));
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
