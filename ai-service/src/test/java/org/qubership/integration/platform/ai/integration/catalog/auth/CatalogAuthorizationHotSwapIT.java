package org.qubership.integration.platform.ai.integration.catalog.auth;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;

@QuarkusTest
class CatalogAuthorizationHotSwapIT {

  private static final String CONVERSATION_ID = "conv-hot-swap";
  private static final String TOKEN_ONE = "Bearer token-one";
  private static final String TOKEN_TWO = "Bearer token-two";

  @AfterEach
  void clearState() {
    CatalogAuthorizationContext.clear(CONVERSATION_ID);
    ToolSession.clear();
  }

  @Test
  void putCatalogAuthUpdatesBoundConversation() {
    CatalogAuthorizationContext.bind(CONVERSATION_ID, TOKEN_ONE);

    given()
        .header("Authorization", TOKEN_TWO)
        .when()
        .put("/api/v1/chat/{conversationId}/catalog-auth", CONVERSATION_ID)
        .then()
        .statusCode(204);

    ToolSession.bind(CONVERSATION_ID);
    org.junit.jupiter.api.Assertions.assertEquals(
        TOKEN_TWO, CatalogAuthorizationContext.resolveAuthorization().orElseThrow());
  }

  @Test
  void putCatalogAuthReturns404WhenConversationNotBound() {
    given()
        .header("Authorization", TOKEN_TWO)
        .when()
        .put("/api/v1/chat/{conversationId}/catalog-auth", "unknown-conversation")
        .then()
        .statusCode(404);
  }

  @Test
  void putCatalogAuthLeavesTokenWhenAuthorizationHeaderMissing() {
    CatalogAuthorizationContext.bind(CONVERSATION_ID, TOKEN_ONE);

    given()
        .when()
        .put("/api/v1/chat/{conversationId}/catalog-auth", CONVERSATION_ID)
        .then()
        .statusCode(400);

    ToolSession.bind(CONVERSATION_ID);
    org.junit.jupiter.api.Assertions.assertEquals(
        TOKEN_ONE, CatalogAuthorizationContext.resolveAuthorization().orElseThrow());
  }
}
