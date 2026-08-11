package org.qubership.integration.platform.ai.chat;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.A2aSdkBootDisabledProfile;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/**
 * Browser create-chain@2 regression while A2A is disabled: original SSE format and one approval
 * remain available without A2A DTOs.
 */
@QuarkusTest
@TestProfile(A2aSdkBootDisabledProfile.class)
class BrowserCreateChainA2aDisabledRegressionIT {

  @InjectMock ScenarioRouter router;

  @InjectMock ProductPipelineRunStore runStore;

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
                        ChatEvent.hitl("approve-plan", "Agree to approve the design?"));
              }
              return Multi.createFrom()
                  .item(ChatEvent.token("Approved. Continuing create-chain@2."));
            });
  }

  @Test
  void browserCreateChainWorksWhenA2aDisabled() {
    String createResponse =
        given()
            .contentType(ContentType.JSON)
            .body("{\"message\":\"Create a greetings chain\",\"scenarioHint\":\"CREATE_CHAIN_PLAN\"}")
            .when()
            .post("/api/v1/chat")
            .then()
            .statusCode(200)
            .extract()
            .asString();

    assertTrue(createResponse.contains("event: meta"), createResponse);
    assertTrue(createResponse.contains("event: token"), createResponse);
    assertTrue(createResponse.contains("event: hitl"), createResponse);
    assertTrue(createResponse.contains("event: done"), createResponse);
    assertTrue(!createResponse.contains("TASK_STATE_"), createResponse);

    String conversationId = extractConversationId(createResponse);
    String approveResponse =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"conversationId\":\"%s\",\"message\":\"Agree\",\"scenarioHint\":\"CREATE_CHAIN_PLAN\"}"
                    .formatted(conversationId))
            .when()
            .post("/api/v1/chat")
            .then()
            .statusCode(200)
            .body(containsString("event: done"))
            .extract()
            .asString();

    assertTrue(approveResponse.contains(conversationId), approveResponse);
    assertTrue(!approveResponse.contains("TASK_STATE_"), approveResponse);
    assertTrue(!approveResponse.contains("a2a_tasks"), approveResponse);

    given().when().get("/q/health").then().statusCode(org.hamcrest.Matchers.not(404));
  }

  private static String extractConversationId(String sse) {
    int key = sse.indexOf("\"conversationId\":\"");
    assertTrue(key >= 0, sse);
    int start = key + "\"conversationId\":\"".length();
    int end = sse.indexOf('"', start);
    return sse.substring(start, end);
  }
}
