package org.qubership.integration.platform.ai.chat;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/**
 * Browser SSE smoke: create-chain@2 style turn keeps meta/token/done frames and supports one
 * approval turn without A2A Task DTOs.
 */
@QuarkusTest
class BrowserCreateChainRegressionIT {

  @InjectMock ScenarioRouter router;

  @InjectMock ProductPipelineRunStore runStore;

  @InjectMock CreateChainApplicationFacade facade;

  private final AtomicInteger turns = new AtomicInteger();

  @BeforeEach
  void stubCreateChainBrowserTurns() {
    turns.set(0);
    when(runStore.loadByConversation(anyString())).thenReturn(Optional.empty());
    when(router.route(any(ChatRequest.class), anyString()))
        .thenAnswer(
            invocation -> {
              int turn = turns.incrementAndGet();
              if (turn == 1) {
                return Multi.createFrom()
                    .items(
                        ChatEvent.token("Drafting create-chain@2 design. Reply Agree to approve."),
                        ChatEvent.decision(
                            new CreateChainPendingAction.Approve(
                                "implementation-plan", "sha256:abc", 3L, "Approve the design?"),
                            3L,
                            ""));
              }
              return Multi.createFrom()
                  .item(ChatEvent.token("Approved. Continuing create-chain@2."));
            });
  }

  @Test
  void browserCreateChainKeepsMetaDoneAndSupportsApproval() {
    String createBody =
        """
        {"message":"Create a greetings chain","scenarioHint":"CREATE_CHAIN_PLAN"}
        """;

    String createResponse =
        given()
            .contentType(ContentType.JSON)
            .body(createBody)
            .when()
            .post("/api/v1/chat")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    assertTrue(createResponse.contains("event: meta"), createResponse);
    assertTrue(createResponse.contains("conversationId"), createResponse);
    assertTrue(createResponse.contains("event: token"), createResponse);
    assertTrue(createResponse.contains("create-chain@2"), createResponse);
    assertTrue(createResponse.contains("event: decision"), createResponse);
    assertTrue(createResponse.contains("sha256:abc"), createResponse);
    assertTrue(createResponse.contains("event: done"), createResponse);

    String conversationId = extractConversationId(createResponse);

    String approveBody =
        """
        {"conversationId":"%s","message":"Agree","scenarioHint":"CREATE_CHAIN_PLAN"}
        """
            .formatted(conversationId);

    String approveResponse =
        given()
            .contentType(ContentType.JSON)
            .body(approveBody)
            .when()
            .post("/api/v1/chat")
            .then()
            .statusCode(200)
            .body(containsString("event: meta"))
            .body(containsString("event: token"))
            .body(containsString("Approved"))
            .body(containsString("event: done"))
            .extract()
            .asString();

    assertTrue(approveResponse.contains(conversationId), approveResponse);
    assertTrue(!approveResponse.contains("TASK_STATE_"), approveResponse);
    assertTrue(!approveResponse.contains("a2a_tasks"), approveResponse);
  }

  @Test
  void decisionCommandBypassesTheRouterAndStreamsTheNextGate() {
    when(facade.validateApprove(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(Optional.empty());
    when(facade.streamApproveOnly(any(ApproveCreateChainArtifactCommand.class)))
        .thenReturn(
            Multi.createFrom()
                .items(
                    new CreateChainEvent.Message("Plan approved. Drafting the chain."),
                    new CreateChainEvent.Waiting(
                        new CreateChainPendingAction.Approve(
                            "implementation-plan", "sha256:next", 4L, "Create the chain?"))));

    String body =
        """
        {"conversationId":"conv-decision","message":"",
         "decision":{"action":"approve","artifactType":"implementation-plan",
                     "artifactHash":"sha256:abc","revision":3,"comment":"looks good"}}
        """;

    String response =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post("/api/v1/chat")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    assertTrue(response.contains("Plan approved."), response);
    assertTrue(response.contains("event: decision"), response);
    assertTrue(response.contains("sha256:next"), response);
    // The router answers every turn it sees with this line, so its absence proves the bypass.
    assertTrue(!response.contains("Continuing create-chain@2"), response);
  }

  private static String extractConversationId(String sse) {
    int key = sse.indexOf("\"conversationId\":\"");
    assertTrue(key >= 0, sse);
    int start = key + "\"conversationId\":\"".length();
    int end = sse.indexOf('"', start);
    return sse.substring(start, end);
  }
}
