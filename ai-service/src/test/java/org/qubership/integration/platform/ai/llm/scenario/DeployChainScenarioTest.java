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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SnapshotDto;
import org.qubership.integration.platform.ai.model.ScenarioType;

class DeployChainScenarioTest {

  private static final String CONVERSATION_ID = "conv-deploy-chain";
  private static final String CHAIN_ID = "chain-1";
  private static final String SNAPSHOT_ID = "11111111-1111-1111-1111-111111111111";

  private ChainContextExtractor chainContextExtractor;
  private CatalogRestClient catalogRestClient;
  private DeployChainScenario scenario;

  @BeforeEach
  void setUp() {
    chainContextExtractor = mock(ChainContextExtractor.class);
    catalogRestClient = mock(CatalogRestClient.class);
    scenario =
        new DeployChainScenario(chainContextExtractor, catalogRestClient, new ObjectMapper());
  }

  @Test
  void missingChainContextDoesNotCreateSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());

    ChatEvent.Token token = tokenFrom("take a snapshot");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).listSnapshots(any());
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
    assertTrue(text.contains("Fields are not properly defined or require mandatory connection"));
    assertTrue(text.contains("HTTP Trigger"));
    assertTrue(text.contains("el-http-1"));
    assertFalse(text.contains(SNAPSHOT_ID));
  }

  @Test
  void nonSnapshotTurnDoesNotCreateSnapshot() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of(CHAIN_ID));

    ChatEvent.Token token = tokenFrom("deploy this chain");

    verify(catalogRestClient, never()).createSnapshot(any());
    verify(catalogRestClient, never()).listSnapshots(any());
    assertTrue(token.text().toLowerCase().contains("snapshot"));
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
    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest(message), CONVERSATION_ID, ScenarioType.DEPLOY_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(1));
    sub.awaitCompletion();
    ChatEvent event = sub.getItems().get(0);
    if (event instanceof ChatEvent.Token token) {
      return token.text();
    }
    if (event instanceof ChatEvent.Error error) {
      return error.message();
    }
    throw new AssertionError("expected token or error, got " + event);
  }

  private static ChatRequest chatRequest(String text) {
    ChatRequest request = new ChatRequest();
    request.setMessage(text);
    return request;
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
