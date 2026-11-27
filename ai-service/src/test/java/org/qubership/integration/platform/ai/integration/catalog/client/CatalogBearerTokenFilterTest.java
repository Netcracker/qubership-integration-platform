package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

class CatalogBearerTokenFilterTest {

  @Test
  void addsServiceBearerOnlyWhenAuthIsOff() {
    System.setProperty("qip.ai.auth.required", "false");
    try {
      MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

      new CatalogBearerTokenFilter("secret").filter(request(headers));

      assertEquals("Bearer secret", headers.getFirst(HttpHeaders.AUTHORIZATION));
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @Test
  void omitsServiceHeaderWhenTokenBlankAndAuthIsOff() {
    System.setProperty("qip.ai.auth.required", "false");
    try {
      for (String token : new String[] {null, "", "  "}) {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        new CatalogBearerTokenFilter(token).filter(request(headers));

        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
      }
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @Test
  void authRequiredWithoutABoundConversationFailsClosed() {
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    assertThrows(
        NotAuthorizedException.class,
        () -> new CatalogBearerTokenFilter("secret").filter(request(headers)));
    assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void keepsAuthorizationSetByAnotherProvider() {
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer m2m");

    new CatalogBearerTokenFilter("secret").filter(request(headers));

    assertEquals("Bearer m2m", headers.getFirst(HttpHeaders.AUTHORIZATION));
  }

  @AfterEach
  void unbind() {
    UserTokenStore.clear("conv-1");
    ToolSession.clear();
  }

  @Test
  void userTokenOfBoundConversationWinsOverServiceToken() {
    long exp = java.time.Instant.now().getEpochSecond() + 600;
    String user = jwt(exp, "alice");
    ToolSession.bind("conv-1");
    UserTokenStore.acquire("conv-1", user);
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    new CatalogBearerTokenFilter("secret").filter(request(headers));

    assertEquals("Bearer " + user, headers.getFirst(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void boundConversationWithoutUserTokenDoesNotFallBackToServiceToken() {
    ToolSession.bind("conv-1");
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    assertThrows(
        NotAuthorizedException.class,
        () -> new CatalogBearerTokenFilter("secret").filter(request(headers)));
    assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void authNotRequiredFallsBackToServiceTokenWhenTurnHasNoUserToken() {
    System.setProperty("qip.ai.auth.required", "false");
    try {
      ToolSession.bind("conv-1");
      MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

      new CatalogBearerTokenFilter("secret").filter(request(headers));

      assertEquals("Bearer secret", headers.getFirst(HttpHeaders.AUTHORIZATION));
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @Test
  void authNotRequiredStillPrefersUserTokenWhenPresent() {
    System.setProperty("qip.ai.auth.required", "false");
    try {
      String user = jwt(java.time.Instant.now().getEpochSecond() + 600, "alice");
      ToolSession.bind("conv-1");
      UserTokenStore.acquire("conv-1", user);
      MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

      new CatalogBearerTokenFilter("secret").filter(request(headers));

      assertEquals("Bearer " + user, headers.getFirst(HttpHeaders.AUTHORIZATION));
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  void expiredUserTokenIsRecordedAs401AndNeverReplacedByTheServiceToken(String authRequired) {
    System.setProperty("qip.ai.auth.required", authRequired);
    try {
      ToolSession.bind("conv-1");
      UserTokenStore.acquire("conv-1", jwt(java.time.Instant.now().getEpochSecond() - 10, "alice"));
      MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

      assertThrows(
          NotAuthorizedException.class,
          () -> new CatalogBearerTokenFilter("secret").filter(request(headers)));

      assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
      assertEquals(401, UserTokenStore.rejection("conv-1"));
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @Test
  void absentConversationTokenWithAuthNotRequiredUsesServiceToken() {
    System.setProperty("qip.ai.auth.required", "false");
    try {
      MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

      new CatalogBearerTokenFilter("secret").filter(request(headers));

      assertEquals("Bearer secret", headers.getFirst(HttpHeaders.AUTHORIZATION));
      assertEquals(UserTokenStore.Status.ABSENT, UserTokenStore.currentStatus());
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @Test
  void liveUserTokenIsAttachedAndReadsLive() {
    String user = jwt(java.time.Instant.now().getEpochSecond() + 600, "alice");
    ToolSession.bind("conv-1");
    UserTokenStore.acquire("conv-1", user);
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    new CatalogBearerTokenFilter("secret").filter(request(headers));

    assertEquals("Bearer " + user, headers.getFirst(HttpHeaders.AUTHORIZATION));
    assertEquals(UserTokenStore.Status.LIVE, UserTokenStore.currentStatus());
    assertEquals(0, UserTokenStore.rejection("conv-1"));
  }




  @Test
  void nestedSkillCloseKeepsTurnTokenForCatalogCalls() {
    // design-execution: compiler skill ends, then ChainPlanGraphValidator hits /v1/library.
    // Nested ToolSession must restore the turn binding so the user bearer is still attached.
    long exp = java.time.Instant.now().getEpochSecond() + 600;
    String user = jwt(exp, "alice");
    ToolSession.bind("conv-1");
    UserTokenStore.acquire("conv-1", user);
    try (ToolSession.Handle ignored = ToolSession.open("conv-1")) {
      assertEquals("conv-1", ToolSession.resolveConversationId());
    }
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    new CatalogBearerTokenFilter("secret").filter(request(headers));

    assertEquals("Bearer " + user, headers.getFirst(HttpHeaders.AUTHORIZATION));
  }

  private static String jwt(long exp, String subject) {
    java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
    String payload = "{\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}";
    return b64.encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + ".sig";
  }

  private static ClientRequestContext request(MultivaluedMap<String, Object> headers) {
    ClientRequestContext context = mock(ClientRequestContext.class);
    when(context.getHeaders()).thenReturn(headers);
    return context;
  }
}
