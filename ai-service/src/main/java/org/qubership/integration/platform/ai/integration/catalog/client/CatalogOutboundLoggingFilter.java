package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Logs outbound catalog REST client calls (method, URL, status, and optionally response body).
 * Scoped via {@link CatalogRestClient}'s {@code @RegisterProvider} only — do not annotate as
 * {@code @Provider} or it would attach to every JAX-RS client in the application.
 */
public class CatalogOutboundLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

  private static final Logger LOG = Logger.getLogger(CatalogOutboundLoggingFilter.class);
  private static final String REQ_ID = CatalogOutboundLoggingFilter.class.getName() + ".reqId";
  private static final String CFG_LOG_RESPONSE_BODY = "qip.ai.catalog.log-response-body";
  private static final int ERROR_BODY_LOG_MAX = 4096;

  private static boolean logCatalogResponseBodyAtInfo() {
    return ConfigProvider.getConfig()
        .getOptionalValue(CFG_LOG_RESPONSE_BODY, Boolean.class)
        .orElse(true);
  }

  @Override
  public void filter(ClientRequestContext requestContext) throws IOException {
    String id = UUID.randomUUID().toString().substring(0, 8);
    requestContext.setProperty(REQ_ID, id);
    LOG.infof(
        "Catalog outbound request [%s]: %s %s",
        id, requestContext.getMethod(), requestContext.getUri());
    Object entity = requestContext.getEntity();
    if (entity != null && logCatalogResponseBodyAtInfo()) {
      LOG.infof(
          "Catalog outbound request body [%s]: %s",
          id,
          AiTraceLog.previewOneLine(
              entity.toString(), AiTraceLog.DEFAULT_CATALOG_RESPONSE_INFO_CHARS));
    } else if (entity != null && LOG.isDebugEnabled()) {
      LOG.debugf(
          "Catalog outbound request body [%s]: %s",
          id, AiTraceLog.preview(entity.toString(), AiTraceLog.DEFAULT_HTTP_BODY_DEBUG_CHARS));
    }
  }

  @Override
  public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext)
      throws IOException {
    Object id = requestContext.getProperty(REQ_ID);
    int status = responseContext.getStatus();
    LOG.infof(
        "Catalog outbound response [%s]: %s %s -> HTTP %d",
        id != null ? id : "?", requestContext.getMethod(), requestContext.getUri(), status);
    if (!responseContext.hasEntity()) {
      return;
    }
    byte[] buf = responseContext.getEntityStream().readAllBytes();
    responseContext.setEntityStream(new ByteArrayInputStream(buf));
    if (buf.length == 0) {
      return;
    }
    String body = new String(buf, StandardCharsets.UTF_8);
    String rid = id != null ? id.toString() : "?";
    if (status >= 400) {
      String snippet =
          body.length() > ERROR_BODY_LOG_MAX ? body.substring(0, ERROR_BODY_LOG_MAX) + "…" : body;
      LOG.warnf("Catalog outbound error body [%s]: %s", rid, snippet);
      return;
    }
    if (logCatalogResponseBodyAtInfo()) {
      LOG.infof(
          "Catalog outbound response body [%s]: %s",
          rid, AiTraceLog.previewOneLine(body, AiTraceLog.DEFAULT_CATALOG_RESPONSE_INFO_CHARS));
    } else if (LOG.isDebugEnabled()) {
      LOG.debugf(
          "Catalog outbound response body [%s]: %s",
          rid, AiTraceLog.preview(body, AiTraceLog.DEFAULT_HTTP_BODY_DEBUG_CHARS));
    }
  }
}
