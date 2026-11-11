package org.qubership.integration.platform.ai.integration.catalog.util;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;

/**
 * JAX-RS / MicroProfile rest-client helpers for catalog HTTP failures (shared by catalog
 * {@code @Tool} beans and services).
 */
public final class CatalogRestSupport {

  private static final int MAX_RESPONSE_BODY_SNIPPET_CHARS = 4096;

  private CatalogRestSupport() {}

  public static WebApplicationException findWebApplicationException(Throwable e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof WebApplicationException wae) {
        return wae;
      }
      t = t.getCause();
    }
    return null;
  }

  public static String readResponseBodySnippet(Response response) {
    if (response == null || !response.hasEntity()) {
      return null;
    }
    try {
      byte[] bytes = response.readEntity(byte[].class);
      if (bytes == null || bytes.length == 0) {
        return null;
      }
      String raw = new String(bytes, StandardCharsets.UTF_8);
      return raw.length() <= MAX_RESPONSE_BODY_SNIPPET_CHARS
          ? raw
          : raw.substring(0, MAX_RESPONSE_BODY_SNIPPET_CHARS) + "…";
    } catch (IllegalStateException alreadyRead) {
      return null;
    } catch (Exception ex) {
      return "(could not read response body: " + ex.getMessage() + ")";
    }
  }

  /**
   * Human-readable detail for tool/service error lines (without {@code "Error in <tool>:"} prefix).
   */
  public static String describeExceptionForToolResult(Throwable e) {
    WebApplicationException wae = findWebApplicationException(e);
    if (wae != null && wae.getResponse() != null) {
      int status = wae.getResponse().getStatus();
      String bodySnippet = readResponseBodySnippet(wae.getResponse());
      if (bodySnippet != null && !bodySnippet.isBlank()) {
        return "HTTP " + status + " — " + bodySnippet;
      }
      return "HTTP " + status;
    }
    String msg = e.getMessage();
    return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
  }
}
