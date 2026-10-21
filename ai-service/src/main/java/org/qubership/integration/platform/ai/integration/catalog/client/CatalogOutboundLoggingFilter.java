package org.qubership.integration.platform.ai.integration.catalog.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.chat.activity.CatalogChangeTracker;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;

/**
 * Logs outbound catalog REST client calls (method, URL, status, and optionally response body).
 * Scoped via {@link CatalogRestClient}'s {@code @RegisterProvider} only — do not annotate as
 * {@code @Provider} or it would attach to every JAX-RS client in the application.
 *
 * <p>Also fans method+path into {@link ToolInvocationSink} (name/status only, no bodies) so Rocky
 * activity can show catalog publication HTTP steps when a chat turn sink is bound.
 */
public class CatalogOutboundLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

  private static final Logger LOG = Logger.getLogger(CatalogOutboundLoggingFilter.class);
  private static final String REQ_ID = CatalogOutboundLoggingFilter.class.getName() + ".reqId";
  private static final String ACTIVITY_LABEL =
      CatalogOutboundLoggingFilter.class.getName() + ".activityLabel";
  private static final String CFG_LOG_RESPONSE_BODY = "qip.ai.catalog.log-response-body";
  private static final int ERROR_BODY_LOG_MAX = 4096;
  private static final String CHANGE_TRACKER = CatalogOutboundLoggingFilter.class.getName() + ".changes";
  private static final ObjectMapper JSON = new ObjectMapper();

  static volatile Boolean logResponseBodyOverride;

  private static boolean logCatalogResponseBodyAtInfo() {
    if (logResponseBodyOverride != null) {
      return logResponseBodyOverride;
    }
    return ConfigProvider.getConfig()
        .getOptionalValue(CFG_LOG_RESPONSE_BODY, Boolean.class)
        .orElse(true);
  }

  @Override
  public void filter(ClientRequestContext requestContext) throws IOException {
    String id = UUID.randomUUID().toString().substring(0, 8);
    requestContext.setProperty(REQ_ID, id);
    String activityLabel = activityLabel(requestContext.getMethod(), requestContext.getUri());
    requestContext.setProperty(ACTIVITY_LABEL, activityLabel);
    ToolInvocationSink.catalogChanges().ifPresent(changes -> requestContext.setProperty(CHANGE_TRACKER, changes));
    ToolInvocationSink.onInvoke(activityLabel);
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
    Object labelProperty = requestContext.getProperty(ACTIVITY_LABEL);
    String activityLabel =
        labelProperty instanceof String s && !s.isBlank()
            ? s
            : activityLabel(requestContext.getMethod(), requestContext.getUri());
    int status = responseContext.getStatus();
    if (status >= 400) {
      ToolInvocationSink.onFailed(activityLabel);
    } else {
      ToolInvocationSink.onComplete(activityLabel);
    }
    LOG.infof(
        "Catalog outbound response [%s]: %s %s -> HTTP %d",
        id != null ? id : "?", requestContext.getMethod(), requestContext.getUri(), status);
    if (!responseContext.hasEntity()) {
      recordChange(requestContext, responseContext, null);
      return;
    }
    boolean logBodyAtInfo = logCatalogResponseBodyAtInfo();
    if (status < 400 && !logBodyAtInfo
        && !(requestContext.getProperty(CHANGE_TRACKER) != null
            && isChainCreation(requestContext.getMethod(), requestContext.getUri()))) {
      recordChange(requestContext, responseContext, null);
      return;
    }
    byte[] buf = responseContext.getEntityStream().readAllBytes();
    responseContext.setEntityStream(new ByteArrayInputStream(buf));
    recordChange(requestContext, responseContext, new String(buf, StandardCharsets.UTF_8));
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
    if (!logBodyAtInfo) return;
    LOG.infof(
        "Catalog outbound response body [%s]: %s",
        rid, AiTraceLog.previewOneLine(body, AiTraceLog.DEFAULT_CATALOG_RESPONSE_INFO_CHARS));
  }

  private static void recordChange(
      ClientRequestContext request, ClientResponseContext response, String body) {
    if (!(request.getProperty(CHANGE_TRACKER) instanceof CatalogChangeTracker tracker)) return;
    ChatEvent.CatalogChanged change = catalogChange(request.getMethod(), request.getUri(), response.getStatus(), body);
    if (change == null) return;
    if (change.chainIds() == null) tracker.chainChanged(null);
    else if (change.chainIds().isEmpty()) tracker.foldersChanged(false);
    else change.chainIds().forEach(tracker::chainChanged);
  }

  private static boolean isChainCreation(String method, URI uri) {
    if (!"POST".equals(method) || uri == null) return false;
    String path = uri.getPath();
    String[] parts = path.split("/");
    return "/v1/chains".equals(path)
        || (parts.length == 5 && parts[1].equals("v1") && parts[2].equals("chains")
            && !parts[3].isBlank() && List.of("copy", "duplicate").contains(parts[4]));
  }

  /** Classifies only the write routes in CatalogRestClient; POST reads never count as mutations. */
  static ChatEvent.CatalogChanged catalogChange(String method, URI uri, int status, String body) {
    if (uri == null || status < 200 || status >= 300 || status == 202
        || "GET".equals(method) || "HEAD".equals(method)) return null;
    List<String> path = java.util.Arrays.stream(uri.getPath().split("/"))
        .filter(part -> !part.isEmpty()).toList();
    if (isChainCreation(method, uri)) {
      String id = null;
      if (body != null && !body.isBlank()) {
        try {
          var value = JSON.readTree(body).get("id");
          if (value != null && value.isTextual() && !value.asText().isBlank()) id = value.asText();
        } catch (IOException e) {
          LOG.debug("Created chain ID could not be read; refreshing the active catalog view", e);
        }
      }
      return new ChatEvent.CatalogChanged(id == null ? null : List.of(id), true);
    }
    if (path.size() >= 2 && path.get(0).equals("v2") && path.get(1).equals("folders")) {
      if (path.size() == 2 && "POST".equals(method)) {
        return new ChatEvent.CatalogChanged(List.of(), true);
      }
      if (path.size() == 3 && ((List.of("PUT", "DELETE").contains(method))
          || ("POST".equals(method) && path.get(2).equals("move")))) {
        return new ChatEvent.CatalogChanged(null, true);
      }
      return null;
    }
    if (path.size() >= 3 && path.get(0).equals("v1") && path.get(1).equals("chains")) {
      boolean writes = path.size() == 3 && List.of("PUT", "DELETE").contains(method);
      if (path.size() == 4) {
        String resource = path.get(3);
        writes |= "POST".equals(method) && List.of("move", "elements", "dependencies").contains(resource);
        writes |= "DELETE".equals(method) && List.of("elements", "dependencies").contains(resource);
      }
      if (path.size() == 5) {
        writes |= "elements".equals(path.get(3)) && "PATCH".equals(method);
        writes |= "POST".equals(method) && (("elements".equals(path.get(3)) && "transfer".equals(path.get(4)))
            || ("properties".equals(path.get(3)) && "logging".equals(path.get(4))));
      }
      return writes ? new ChatEvent.CatalogChanged(List.of(path.get(2)), true) : null;
    }
    if (path.size() >= 5 && path.get(0).equals("v1") && path.get(1).equals("catalog")
        && path.get(2).equals("chains")) {
      boolean writes = path.size() == 5 && "POST".equals(method)
          && List.of("snapshots", "deployments").contains(path.get(4));
      writes |= path.size() == 6 && "DELETE".equals(method) && "deployments".equals(path.get(4));
      return writes ? new ChatEvent.CatalogChanged(List.of(path.get(3)), true) : null;
    }
    return null;
  }

  /** Visible for tests — {@code POST /v1/chains} style label, no query or body. */
  static String activityLabel(String method, URI uri) {
    String verb = method == null || method.isBlank() ? "HTTP" : method.trim().toUpperCase();
    if (uri == null) {
      return verb;
    }
    String path = uri.getRawPath();
    if (path == null || path.isBlank()) {
      path = uri.getPath();
    }
    if (path == null || path.isBlank()) {
      return verb;
    }
    return verb + " " + path;
  }
}
