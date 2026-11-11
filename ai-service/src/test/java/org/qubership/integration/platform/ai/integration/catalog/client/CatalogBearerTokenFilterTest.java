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
    UserTokenStore.current().ifPresent(t -> UserTokenStore.remove("conv-1", t));
    UserTokenStore.remove("conv-1", "user");
    UserTokenStore.remove("conv-1", "new");
    UserTokenStore.remove("conv-1", "old");
    ToolSession.clear();
  }

  @Test
  void userTokenOfBoundConversationWinsOverServiceToken() {
    long exp = java.time.Instant.now().getEpochSecond() + 600;
    String user = jwt(exp, "alice");
    ToolSession.bind("conv-1");
    UserTokenStore.put("conv-1", user);
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
      UserTokenStore.put("conv-1", user);
      MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

      new CatalogBearerTokenFilter("secret").filter(request(headers));

      assertEquals("Bearer " + user, headers.getFirst(HttpHeaders.AUTHORIZATION));
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @Test
  void expiredUserTokenIsNotSentWhenNobodyRenews() {
    long exp = java.time.Instant.now().getEpochSecond() - 60;
    String expired =
        java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + exp + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + ".sig";
    ToolSession.bind("conv-1");
    UserTokenStore.put("conv-1", expired);
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    assertThrows(
        NotAuthorizedException.class,
        () -> new CatalogBearerTokenFilter("secret").filter(request(headers)));
    assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void renewedTokenIsUsedByLaterCalls() {
    long exp = java.time.Instant.now().getEpochSecond() + 600;
    String old = jwt(exp, "alice");
    String renewed = jwt(exp + 60, "alice");
    ToolSession.bind("conv-1");
    UserTokenStore.put("conv-1", old);
    assertTrue(UserTokenStore.renew("conv-1", renewed));
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    new CatalogBearerTokenFilter("secret").filter(request(headers));

    assertEquals("Bearer " + renewed, headers.getFirst(HttpHeaders.AUTHORIZATION));
  }

  @Test
  void filterDoesNotBlockWaitingForARenewal() {
    long exp = java.time.Instant.now().getEpochSecond() + 3;
    ToolSession.bind("conv-1");
    UserTokenStore.put("conv-1", jwt(exp, "alice"));
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    long started = System.nanoTime();

    assertThrows(
        NotAuthorizedException.class,
        () -> new CatalogBearerTokenFilter("secret").filter(request(headers)));

    assertTrue(System.nanoTime() - started < 2_000_000_000L);
  }

  @Test
  void nestedSkillCloseKeepsTurnTokenForCatalogCalls() {
    // design-execution: compiler skill ends, then ChainPlanGraphValidator hits /v1/library.
    // Nested ToolSession must restore the turn binding so the user bearer is still attached.
    long exp = java.time.Instant.now().getEpochSecond() + 600;
    String user = jwt(exp, "alice");
    ToolSession.bind("conv-1");
    UserTokenStore.put("conv-1", user);
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
