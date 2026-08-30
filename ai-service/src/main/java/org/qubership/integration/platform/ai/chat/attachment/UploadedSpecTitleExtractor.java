package org.qubership.integration.platform.ai.chat.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.regex.Pattern;

/**
 * Resolves human-readable names for uploaded OpenAPI specifications. Parses {@code info.title}
 * from JSON or YAML content and falls back to the filename when a title is unavailable.
 */
public final class UploadedSpecTitleExtractor {

  private static final Pattern SAFE_TITLE_CHARS = Pattern.compile("[^A-Za-z0-9 _-]");
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final ObjectMapper YAML_MAPPER = new YAMLMapper();

  private UploadedSpecTitleExtractor() {
    // Utility class.
  }

  /**
   * Returns a display name for the uploaded spec. When a sanitized OpenAPI {@code info.title} is
   * present it is returned; otherwise the filename or its base name is returned.
   */
  public static String resolveDisplayName(String filename, byte[] content) {
    String title = sanitizeTitle(extractTitle(content));
    if (title.isEmpty()) {
      return baseName(filename);
    }
    return title;
  }

  /**
   * Returns the spec name to use for catalog system/specification-group creation. Prefers the
   * sanitized OpenAPI {@code info.title}; falls back to the filename base name when unavailable.
   */
  public static String resolveSpecName(byte[] content, String filename) {
    String title = sanitizeTitle(extractTitle(content));
    return title.isEmpty() ? baseName(filename) : title;
  }

  private static String extractTitle(byte[] content) {
    if (content == null || content.length == 0) {
      return null;
    }
    try {
      JsonNode root = looksLikeJson(content) ? JSON_MAPPER.readTree(content) : YAML_MAPPER.readTree(content);
      JsonNode info = root.path("info");
      if (info.isMissingNode()) {
        return null;
      }
      JsonNode title = info.path("title");
      return title.isMissingNode() || title.isNull() ? null : title.asText();
    } catch (Exception e) {
      return null;
    }
  }

  private static String sanitizeTitle(String title) {
    if (title == null) {
      return "";
    }
    String cleaned = SAFE_TITLE_CHARS.matcher(title.trim()).replaceAll(" ");
    cleaned = WHITESPACE_RUN.matcher(cleaned).replaceAll(" ").trim();
    return cleaned;
  }

  private static String baseName(String filename) {
    int lastDot = filename.lastIndexOf('.');
    return lastDot > 0 ? filename.substring(0, lastDot) : filename;
  }

  private static boolean looksLikeJson(byte[] content) {
    for (byte b : content) {
      char c = (char) b;
      if (!Character.isWhitespace(c)) {
        return c == '{' || c == '[';
      }
    }
    return false;
  }
}
