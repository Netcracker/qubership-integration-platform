package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import io.vertx.core.http.HttpClientRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.core.MediaType;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jboss.resteasy.reactive.client.impl.ClientRequestContextImpl;
import org.jboss.resteasy.reactive.client.impl.RestClientRequestContext;
import org.jboss.resteasy.reactive.common.jaxrs.ResponseImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.capture.TransientFailures;

class ResponsesDeadlineCustomizerTest {

  @Test
  void rateLimitFilterPreservesOnlyTimingHeadersAndTheOriginalBodyWithoutADeeperCause() throws Exception {
    var request = mock(ClientRequestContext.class);
    var response = mock(ClientResponseContext.class);
    String body = "  {\"error\":\"temporary capacity Δ\"}\n";
    when(response.getStatus()).thenReturn(429);
    when(response.getHeaderString("Retry-After")).thenReturn("Wed, 21 Oct 2037 07:28:00 GMT");
    when(response.getHeaderString("X-RateLimit-Reset")).thenReturn("2139722880000");
    when(response.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
    when(response.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

    var failure = assertThrows(RateLimitHttpException.class,
        () -> ResponsesDeadlineCustomizer.rateLimitResponseFilter().filter(request, response));

    assertEquals(429, failure.statusCode());
    assertEquals(body, failure.getMessage());
    assertNull(failure.getCause(), "SDK root-cause mapping must retain this header-bearing HTTP exception");
    assertEquals(Map.of("Retry-After", "Wed, 21 Oct 2037 07:28:00 GMT", "X-RateLimit-Reset", "2139722880000"),
        failure.headers());
    assertThrows(UnsupportedOperationException.class, () -> failure.headers().put("Authorization", "ignored"));
    verify(response, never()).getHeaderString("Authorization");
    verifyNoInteractions(request);
  }

  @Test
  void rateLimitFilterPreservesTheDeclaredBodyEncodingAndMissingTimingHeaders() throws Exception {
    var response = mock(ClientResponseContext.class);
    String body = "Erreur de capacité: café\r\n";
    when(response.getStatus()).thenReturn(429);
    when(response.getMediaType()).thenReturn(MediaType.valueOf("text/plain;charset=ISO-8859-1"));
    when(response.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)));

    var failure = assertThrows(RateLimitHttpException.class,
        () -> ResponsesDeadlineCustomizer.rateLimitResponseFilter().filter(mock(ClientRequestContext.class), response));

    assertEquals(body, failure.getMessage());
    assertTrue(failure.headers().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 400, 503})
  void rateLimitFilterLeavesEveryOtherResponseUntouched(int status) throws Exception {
    var request = mock(ClientRequestContext.class);
    var response = mock(ClientResponseContext.class);
    when(response.getStatus()).thenReturn(status);

    ResponsesDeadlineCustomizer.rateLimitResponseFilter().filter(request, response);

    verify(response, never()).getEntityStream();
    verify(response, never()).getHeaderString(anyString());
    verify(response, never()).setEntityStream(any());
    verifyNoInteractions(request);
  }

  @Test
  void providerSelectionPreservesThePayloadAndOnlyAppliesToOpenRouterResponses() throws Exception {
    var mapper = new ObjectMapper();
    var request = mock(ClientRequestContext.class);
    String body = """
        {"model":"moonshotai/kimi-k2.6","input":[{"role":"user","content":"line 1\\nline 2"}],
         "tools":[],"temperature":0.2,"stream":true}
        """;
    when(request.getEntity()).thenReturn(body);
    when(request.getUri()).thenReturn(URI.create("https://openrouter.ai/api/v1/responses"));
    ResponsesDeadlineCustomizer.providerFilter("streamlake/fp8", mapper).filter(request);
    var entity = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(request).setEntity(entity.capture());
    var routed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree((String) entity.getValue());
    assertEquals("streamlake/fp8", routed.at("/provider/only/0").textValue());
    assertFalse(routed.at("/provider/allow_fallbacks").booleanValue());
    routed.remove("provider");
    assertEquals(mapper.readTree(body), routed);

    for (String uri : java.util.List.of("https://api.openai.com/v1/responses",
        "https://openrouter.ai/api/v1/embeddings")) {
      clearInvocations(request);
      when(request.getUri()).thenReturn(URI.create(uri));
      ResponsesDeadlineCustomizer.providerFilter("streamlake/fp8", mapper).filter(request);
      verify(request, never()).setEntity(any());
    }
  }

  @Test
  void deadlineClosesTheRequestAndStaysARecoverableTransportFailure() throws Exception {
    var request = mock(ClientRequestContextImpl.class);
    var context = mock(RestClientRequestContext.class);
    var connection = mock(HttpClientRequest.class);
    var result = new CompletableFuture<ResponseImpl>();
    when(request.getRestClientRequestContext()).thenReturn(context);
    when(context.getResult()).thenReturn(result);
    when(context.getHttpClientRequest()).thenReturn(connection);
    ResponsesDeadlineCustomizer.deadlineFilter(Duration.ofMillis(10)).filter(request);
    var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
    assertTrue(TransientFailures.isTransient(failure));
    verify(context, timeout(2000)).setUserCanceled();
    verify(connection, timeout(2000)).reset();

    // A completed response cancels its timer without closing a successful request.
    var complete = new CompletableFuture<ResponseImpl>();
    complete.complete(mock(ResponseImpl.class));
    clearInvocations(context, connection);
    when(context.getResult()).thenReturn(complete);
    ResponsesDeadlineCustomizer.deadlineFilter(Duration.ofMillis(10)).filter(request);
    verify(context, never()).setUserCanceled();
    verify(connection, never()).reset();
  }
}
