package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable summaries of {@link ElementPatchValidator} JSON for logs, tool results, and
 * exceptions.
 */
public final class ElementPatchValidationMessages {

  private ElementPatchValidationMessages() {}

  /**
   * Compact summary for {@code IllegalArgumentException} messages and catalog tool errors (LLM +
   * user).
   */
  public static String summarizeFailure(String validationJson, ObjectMapper objectMapper) {
    if (validationJson == null || validationJson.isBlank()) {
      return "validation result is empty";
    }
    try {
      JsonNode root = objectMapper.readTree(validationJson);
      if (root.has("error")) {
        return root.get("error").asText();
      }
      if (root.path("valid").asBoolean(false)) {
        return "valid";
      }
      StringBuilder sb = new StringBuilder();
      appendErrors(sb, root.path("errors"));
      appendMissingRequired(sb, root.path("missingRequired"));
      String out = sb.toString().trim();
      return out.isEmpty() ? validationJson : out;
    } catch (Exception e) {
      return validationJson;
    }
  }

  private static void appendErrors(StringBuilder sb, JsonNode errors) {
    if (!errors.isArray() || errors.isEmpty()) {
      return;
    }
    List<String> parts = new ArrayList<>();
    for (JsonNode err : errors) {
      String path = err.path("path").asText("");
      String message = err.path("message").asText("");
      if (message.isBlank()) {
        continue;
      }
      String line = path.isBlank() ? message : path + ": " + message;
      JsonNode missingProps = err.get("missingProperties");
      if (missingProps != null && missingProps.isArray() && !missingProps.isEmpty()) {
        List<String> keys = new ArrayList<>();
        missingProps.forEach(
            n -> {
              if (n.isTextual()) {
                keys.add(n.asText());
              }
            });
        if (!keys.isEmpty()) {
          line = line + " missingProperties=[" + String.join(", ", keys) + "]";
        }
      }
      JsonNode hints = err.get("oneOfBranchHints");
      if (hints != null && hints.isArray() && !hints.isEmpty()) {
        List<String> hintLines = new ArrayList<>();
        hints.forEach(
            h -> {
              if (h.isTextual()) {
                hintLines.add(h.asText());
              }
            });
        if (!hintLines.isEmpty()) {
          line = line + " (" + String.join("; ", hintLines) + ")";
        }
      }
      parts.add(line);
    }
    if (!parts.isEmpty()) {
      sb.append(String.join("; ", parts));
    }
  }

  private static void appendMissingRequired(StringBuilder sb, JsonNode missingRequired) {
    if (!missingRequired.isArray() || missingRequired.isEmpty()) {
      return;
    }
    List<String> keys = new ArrayList<>();
    missingRequired.forEach(
        n -> {
          if (n.isTextual()) {
            keys.add(n.asText());
          }
        });
    if (keys.isEmpty()) {
      return;
    }
    if (!sb.isEmpty()) {
      sb.append("; ");
    }
    sb.append("missingRequired=[");
    sb.append(String.join(", ", keys));
    sb.append("]");
  }
}
