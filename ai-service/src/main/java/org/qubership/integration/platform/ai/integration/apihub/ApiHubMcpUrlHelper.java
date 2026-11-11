package org.qubership.integration.platform.ai.integration.apihub;

import java.net.URI;

/**
 * Normalizes API Hub MCP HTTP base URLs for JSON-RPC clients (trailing slash after {@code /mcp})
 * and splits a full URL into REST-client style origin + path.
 */
public final class ApiHubMcpUrlHelper {

  private ApiHubMcpUrlHelper() {}

  /**
   * Ensures the MCP base URL ends with {@code /mcp/} when it ends with the {@code /mcp} segment.
   */
  public static String normalizeTrailingSlash(String url) {
    if (url == null) {
      return null;
    }
    String trimmed = url.trim();
    if (trimmed.endsWith("/mcp") && !trimmed.endsWith("/mcp/")) {
      return trimmed + "/";
    }
    return trimmed;
  }

  public record OriginAndPath(String origin, String path) {}

  /**
   * Splits {@code scheme://host[:port]/path...} into origin (no path) and absolute path (starts
   * with {@code /}).
   */
  public static OriginAndPath splitOriginAndPath(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("url is blank");
    }
    URI u = URI.create(url.trim());
    String scheme = u.getScheme();
    String host = u.getHost();
    if (scheme == null || scheme.isBlank()) {
      throw new IllegalArgumentException("missing scheme");
    }
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("missing host");
    }
    int port = u.getPort();
    String origin = scheme + "://" + host + (port > 0 ? ":" + port : "");
    String path = u.getRawPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    String query = u.getRawQuery();
    if (query != null && !query.isEmpty()) {
      path = path + "?" + query;
    }
    return new OriginAndPath(origin, path);
  }
}
