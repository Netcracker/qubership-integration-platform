package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

class CatalogResponseExceptionMapperTest {

  private static final String CONVERSATION = "catalog-auth-mapper-conversation";

  private final CatalogResponseExceptionMapper mapper = new CatalogResponseExceptionMapper();

  @AfterEach
  void clearRecordedFailures() {
    CatalogAuthenticationFailures.clear(CONVERSATION);
    CatalogAccessDeniedFailures.clear(CONVERSATION);
    ToolSession.clear();
  }

  @Test
  void aRecordedRejectionIsFoundWhetherOrNotTheLookupIsPadded() {
    CatalogAuthenticationFailures.record("  " + CONVERSATION + "  ", 401, "unauthorized");

    assertTrue(CatalogAuthenticationFailures.recordedFor(CONVERSATION).isPresent());
    assertTrue(CatalogAuthenticationFailures.recordedFor("  " + CONVERSATION).isPresent());
  }

  @Test
  void mapsOnlyNonRateLimitedClientResponsesToTheAbortType() {
    assertInstanceOf(CatalogNonRetryableResponseException.class, map(400));
    assertInstanceOf(CatalogNonRetryableResponseException.class, map(404));
    assertInstanceOf(WebApplicationException.class, map(429));
    assertTrue(!(map(429) instanceof CatalogNonRetryableResponseException));
    assertTrue(!(map(503) instanceof CatalogNonRetryableResponseException));
  }

  @Test
  void catalog401WithTurnBearerIsMarkedAndRememberedForTheConversation() {
    try (ToolSession.Handle ignored = ToolSession.open(CONVERSATION)) {
      CatalogNonRetryableResponseException error = mapCatalogRefusal(401, "unauthorized");

      assertTrue(error.turnBearer());
      assertEquals(401, error.status());
      assertTrue(CatalogAuthenticationFailures.isTurnBearerUnauthorized(error));
      CatalogAuthenticationFailures.RecordedRejection rejection =
          CatalogAuthenticationFailures.recordedFor(CONVERSATION).orElseThrow();
      assertEquals(401, rejection.status());
      assertTrue(rejection.technicalDetails().contains("401"));
      assertTrue(rejection.technicalDetails().contains("unauthorized"));
    }
  }

  @Test
  void catalog401WithoutTurnBearerStaysUnmarkedAndUnremembered() {
    CatalogNonRetryableResponseException error = mapCatalogRefusal(401, "unauthorized");

    assertFalse(error.turnBearer());
    assertFalse(CatalogAuthenticationFailures.isTurnBearerUnauthorized(error));
    assertTrue(CatalogAuthenticationFailures.recordedFor(CONVERSATION).isEmpty());
  }

  @Test
  void catalog403Or404WithTurnBearerIsNotAnAuthenticationFailure() {
    try (ToolSession.Handle ignored = ToolSession.open(CONVERSATION)) {
      CatalogNonRetryableResponseException forbidden = mapCatalogRefusal(403, "forbidden");
      CatalogNonRetryableResponseException missing = mapCatalogRefusal(404, "missing");

      assertFalse(CatalogAuthenticationFailures.isTurnBearerUnauthorized(forbidden));
      assertFalse(CatalogAuthenticationFailures.isTurnBearerUnauthorized(missing));
      assertTrue(CatalogAuthenticationFailures.recordedFor(CONVERSATION).isEmpty());
      assertTrue(CatalogAccessDeniedFailures.isTurnBearerAccessDenied(forbidden));
      assertFalse(CatalogAccessDeniedFailures.isTurnBearerAccessDenied(missing));
      CatalogAccessDeniedFailures.RecordedRejection rejection =
          CatalogAccessDeniedFailures.recordedFor(CONVERSATION).orElseThrow();
      assertEquals(403, rejection.status());
      assertTrue(rejection.technicalDetails().contains("403"));
      assertTrue(rejection.technicalDetails().contains("forbidden"));
    }
  }

  @Test
  void catalog401DoesNotAskTheClientForAnotherToken() {
    List<ChatEvent> events = new ArrayList<>();
    try (ToolSession.Handle ignored = ToolSession.open(CONVERSATION)) {
      UserTokenStore.put(CONVERSATION, jwt(Instant.now().getEpochSecond() + 600, "alice"));
      ToolInvocationSink.bind(events::add, null, CONVERSATION);
      try {
        CatalogNonRetryableResponseException error = mapCatalogRefusal(401, "unauthorized");

        assertTrue(error.turnBearer());
        assertTrue(
            events.isEmpty(),
            () -> "a 401 must not open the renewal wait or ask for another token: " + events);
      } finally {
        ToolInvocationSink.unbind(CONVERSATION);
        UserTokenStore.current().ifPresent(token -> UserTokenStore.remove(CONVERSATION, token));
      }
    }
  }

  @Test
  void bothClientsRegisterTheSharedMapperAndRetryTransientResponses() {
    assertClientFaultTolerance(CatalogRestClient.class);
    assertClientFaultTolerance(CatalogImportRestClient.class);
  }

  private RuntimeException map(int status) {
    Response response = Response.status(status).build();
    try {
      return mapper.toThrowable(response);
    } finally {
      response.close();
    }
  }

  private CatalogNonRetryableResponseException mapCatalogRefusal(int status, String body) {
    Response response =
        Response.status(status)
            .entity(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .build();
    RuntimeException mapped = mapper.toThrowable(response);
    response.close();
    return assertInstanceOf(CatalogNonRetryableResponseException.class, mapped);
  }

  private static String jwt(long exp, String subject) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String payload = "{\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}";
    return b64.encodeToString("{}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }

  private static void assertClientFaultTolerance(Class<?> clientType) {
    assertTrue(
        Arrays.stream(clientType.getAnnotationsByType(RegisterProvider.class))
            .anyMatch(provider -> provider.value().equals(CatalogResponseExceptionMapper.class)));
    Retry retry = clientType.getAnnotation(Retry.class);
    assertEquals(2, retry.maxRetries());
    assertTrue(Arrays.asList(retry.retryOn()).contains(WebApplicationException.class));
    assertTrue(Arrays.asList(retry.retryOn()).contains(TimeoutException.class));
    assertEquals(CatalogNonRetryableResponseException.class, retry.abortOn()[0]);
  }
}
