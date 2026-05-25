package org.qubership.integration.platform.ai.integration.apihub;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiHubOutboundLoggingFilterSessionPreviewTest {

  @Test
  void mcpSessionIdPreviewShortValueFullyLogged() {
    ClientRequestContext ctx = Mockito.mock(ClientRequestContext.class);
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.add("Mcp-Session-Id", "abc");
    Mockito.when(ctx.getStringHeaders()).thenReturn(headers);
    String s = ApiHubOutboundLoggingFilter.mcpSessionIdPreviewForLog(ctx);
    assertEquals(", Mcp-Session-Id=abc", s);
  }

  @Test
  void mcpSessionIdPreviewLongValueTruncated() {
    ClientRequestContext ctx = Mockito.mock(ClientRequestContext.class);
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.add("Mcp-Session-Id", "0123456789abcdef");
    Mockito.when(ctx.getStringHeaders()).thenReturn(headers);
    String s = ApiHubOutboundLoggingFilter.mcpSessionIdPreviewForLog(ctx);
    assertTrue(s.startsWith(", Mcp-Session-Id=01234567"));
    assertTrue(s.endsWith("…"));
  }

  @Test
  void mcpSessionIdPreviewMissingHeaderEmpty() {
    ClientRequestContext ctx = Mockito.mock(ClientRequestContext.class);
    Mockito.when(ctx.getStringHeaders()).thenReturn(null);
    assertEquals("", ApiHubOutboundLoggingFilter.mcpSessionIdPreviewForLog(ctx));
  }
}
