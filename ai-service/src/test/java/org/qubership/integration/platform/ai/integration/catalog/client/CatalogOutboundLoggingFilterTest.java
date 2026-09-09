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
