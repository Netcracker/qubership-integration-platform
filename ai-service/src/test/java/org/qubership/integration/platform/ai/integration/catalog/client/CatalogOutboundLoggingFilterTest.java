package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

class CatalogOutboundLoggingFilterTest {
  @Test
  void onlyConfirmedWriteRoutesProduceCatalogChanges() {
    for (String route : List.of("/v1/chains/a", "/v1/chains/a/elements/x", "/v1/chains/a/dependencies",
        "/v1/chains/a/elements/transfer", "/v1/catalog/chains/a/snapshots")) {
      String method = route.endsWith("/x") ? "PATCH" : route.equals("/v1/chains/a") ? "PUT" : "POST";
      assertEquals(new ChatEvent.CatalogChanged(List.of("a"), true),
          CatalogOutboundLoggingFilter.catalogChange(method, URI.create(route), 200, null));
      org.junit.jupiter.api.Assertions.assertNull(CatalogOutboundLoggingFilter.catalogChange(method, URI.create(route), 202, null));
      org.junit.jupiter.api.Assertions.assertNull(CatalogOutboundLoggingFilter.catalogChange(method, URI.create(route), 500, null));
    }
    for (String route : List.of("/v2/folders/list", "/v1/folders/search", "/v1/folders/filter",
        "/v1/systems", "/v1/maas-actions/kafka")) {
      org.junit.jupiter.api.Assertions.assertNull(CatalogOutboundLoggingFilter.catalogChange("POST", URI.create(route), 200, null));
    }
    assertEquals(new ChatEvent.CatalogChanged(List.of("new"), true),
        CatalogOutboundLoggingFilter.catalogChange("POST", URI.create("/v1/chains/a/copy"), 201, "{\"id\":\"new\"}"));
    assertEquals(new ChatEvent.CatalogChanged(null, true),
        CatalogOutboundLoggingFilter.catalogChange("POST", URI.create("/v1/chains"), 201, "{\"unexpected\":true}"));
    assertEquals(new ChatEvent.CatalogChanged(List.of(), true),
        CatalogOutboundLoggingFilter.catalogChange("POST", URI.create("/v2/folders"), 201, null));
    assertEquals(new ChatEvent.CatalogChanged(null, true),
        CatalogOutboundLoggingFilter.catalogChange("DELETE", URI.create("/v2/folders/a"), 204, null));
  }

  @Test
  void creationTracksItsIdWithLoggingDisabledAndPreservesTheResponseStream() throws IOException {
    CatalogOutboundLoggingFilter.logResponseBodyOverride = false;
    List<ChatEvent> events = new ArrayList<>();
    ToolInvocationSink.bind(events::add);
    var request = mock(ClientRequestContext.class);
    var properties = new java.util.HashMap<String, Object>();
    org.mockito.Mockito.doAnswer(call -> { properties.put(call.getArgument(0), call.getArgument(1)); return null; })
        .when(request).setProperty(any(), any());
    when(request.getProperty(any())).thenAnswer(call -> properties.get(call.getArgument(0)));
    when(request.getMethod()).thenReturn("POST");
    when(request.getUri()).thenReturn(URI.create("/v1/chains"));
    var response = mock(ClientResponseContext.class);
    when(response.getStatus()).thenReturn(201);
    when(response.hasEntity()).thenReturn(true);
    when(response.getEntityStream()).thenReturn(new ByteArrayInputStream("{\"id\":\"new\"}".getBytes(StandardCharsets.UTF_8)));
    var filter = new CatalogOutboundLoggingFilter();
    filter.filter(request);
    filter.filter(request, response);
    var stream = org.mockito.ArgumentCaptor.forClass(InputStream.class);
    verify(response).setEntityStream(stream.capture());
    assertEquals("{\"id\":\"new\"}", new String(stream.getValue().readAllBytes(), StandardCharsets.UTF_8));
    ToolInvocationSink.flushCatalogChanges();
    assertEquals(new ChatEvent.CatalogChanged(List.of("new"), true), events.getLast());
  }

  @AfterEach
  void tearDown() {
    ToolInvocationSink.unbind();
    CatalogOutboundLoggingFilter.logResponseBodyOverride = null;
  }

  @Test
  void activityLabelUsesMethodAndPathOnly() {
    assertEquals(
        "POST /v1/chains",
        CatalogOutboundLoggingFilter.activityLabel(
            "post", URI.create("https://catalog.example/v1/chains?x=1")));
    assertEquals(
        "GET /v1/elements/abc",
        CatalogOutboundLoggingFilter.activityLabel(
            "GET", URI.create("http://localhost/v1/elements/abc")));
  }

  @Test
  void activityLabelFansIntoToolInvocationSinkWithoutBodies() {
    List<ChatEvent> out = new ArrayList<>();
    ToolInvocationSink.bind(out::add, "skill:materialization");
    try {
      String label = CatalogOutboundLoggingFilter.activityLabel("POST", URI.create("/v1/chains"));
      ToolInvocationSink.onInvoke(label);
      ToolInvocationSink.onComplete(label);
    } finally {
      ToolInvocationSink.unbind();
    }

    assertEquals(2, out.size());
    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("tool", running.kind());
    assertEquals("Creating the chain", running.label());
    assertEquals("skill:materialization", running.parentId());
    assertTrue(running.id().startsWith("tool:"));
  }

  @Test
  void successWithoutBodyLogDoesNotReadEntityStream() throws IOException {
    CatalogOutboundLoggingFilter.logResponseBodyOverride = false;
    CatalogOutboundLoggingFilter filter = new CatalogOutboundLoggingFilter();
    ClientRequestContext request = mockRequest();
    ClientResponseContext response = mock(ClientResponseContext.class);
    when(response.getStatus()).thenReturn(200);
    when(response.hasEntity()).thenReturn(true);

    filter.filter(request, response);

    verify(response, never()).getEntityStream();
  }

  @Test
  void errorResponseStillBuffersEntityStream() throws IOException {
    CatalogOutboundLoggingFilter.logResponseBodyOverride = false;
    CatalogOutboundLoggingFilter filter = new CatalogOutboundLoggingFilter();
    ClientRequestContext request = mockRequest();
    ClientResponseContext response = mock(ClientResponseContext.class);
    when(response.getStatus()).thenReturn(400);
    when(response.hasEntity()).thenReturn(true);
    when(response.getEntityStream())
        .thenReturn(new ByteArrayInputStream("{\"error\":\"no\"}".getBytes(StandardCharsets.UTF_8)));

    filter.filter(request, response);

    verify(response).getEntityStream();
    verify(response).setEntityStream(any(InputStream.class));
  }

  @Test
  void successWithBodyLogReadsEntityStream() throws IOException {
    CatalogOutboundLoggingFilter.logResponseBodyOverride = true;
    CatalogOutboundLoggingFilter filter = new CatalogOutboundLoggingFilter();
    ClientRequestContext request = mockRequest();
    ClientResponseContext response = mock(ClientResponseContext.class);
    when(response.getStatus()).thenReturn(200);
    when(response.hasEntity()).thenReturn(true);
    when(response.getEntityStream())
        .thenReturn(new ByteArrayInputStream("{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8)));

    filter.filter(request, response);

    verify(response).getEntityStream();
    verify(response).setEntityStream(any(InputStream.class));
  }

  private static ClientRequestContext mockRequest() {
    ClientRequestContext request = mock(ClientRequestContext.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getUri()).thenReturn(URI.create("http://catalog/v1/chains"));
    when(request.getProperty(any())).thenReturn("req1");
    return request;
  }
}
