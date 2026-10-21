package org.qubership.integration.platform.ai.integration.apihub;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Logs outbound APIHUB MCP REST calls (method, URL, status, truncated {@code Mcp-Session-Id} for
 * correlation). Does not log api-key or request bodies. Registered only on {@link ApiHubMcpClient}
 * via {@code @RegisterProvider}.
 */
public class ApiHubOutboundLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

  private static final Logger LOG = Logger.getLogger(ApiHubOutboundLoggingFilter.class);
  private static final String REQ_ID = ApiHubOutboundLoggingFilter.class.getName() + ".reqId";
  private static final int ERROR_BODY_LOG_MAX = 4096;
  private static final int MCP_SESSION_LOG_PREFIX = 8;

  @Override
  public void filter(ClientRequestContext requestContext) throws IOException {
    String id = UUID.randomUUID().toString().substring(0, 8);
    requestContext.setProperty(REQ_ID, id);
    LOG.infof(
        "APIHUB outbound request [%s]: %s %s%s",
        id,
        requestContext.getMethod(),
        requestContext.getUri(),
        mcpSessionIdPreviewForLog(requestContext));
  }

  @Override
  public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext)
      throws IOException {
    Object id = requestContext.getProperty(REQ_ID);
    int status = responseContext.getStatus();
    LOG.infof(
        "APIHUB outbound response [%s]: %s %s -> HTTP %d",
        id != null ? id : "?", requestContext.getMethod(), requestContext.getUri(), status);
    if (status >= 400 && responseContext.hasEntity()) {
      byte[] buf = responseContext.getEntityStream().readAllBytes();
      responseContext.setEntityStream(new ByteArrayInputStream(buf));
      if (buf.length > 0) {
        String snippet = new String(buf, StandardCharsets.UTF_8);
        if (snippet.length() > ERROR_BODY_LOG_MAX) {
          snippet = snippet.substring(0, ERROR_BODY_LOG_MAX) + "…";
        }
        LOG.warnf("APIHUB outbound error body [%s]: %s", id != null ? id : "?", snippet);
      }
    }
  }

  /**
   * Short prefix of {@code Mcp-Session-Id} for log correlation; avoids logging secrets (api-key is
   * never read here).
   */
  static String mcpSessionIdPreviewForLog(ClientRequestContext requestContext) {
    var headers = requestContext.getStringHeaders();
    if (headers == null) {
      return "";
    }
    List<String> vals = headers.get("Mcp-Session-Id");
    if (vals == null || vals.isEmpty()) {
      return "";
    }
    String s = vals.get(0);
    if (s == null || s.isBlank()) {
      return "";
    }
    String trimmed = s.trim();
    if (trimmed.length() <= MCP_SESSION_LOG_PREFIX) {
      return ", Mcp-Session-Id=" + trimmed;
    }
    return ", Mcp-Session-Id=" + trimmed.substring(0, MCP_SESSION_LOG_PREFIX) + "…";
  }
}
