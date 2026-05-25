package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Structured JSON envelope for LangChain4j catalog {@code @Tool} return values. */
public final class CatalogToolResult {

  public static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";
  public static final String CODE_MUTATION_NOT_ALLOWED = "MUTATION_NOT_ALLOWED";
  public static final String CODE_CATALOG_HTTP_ERROR = "CATALOG_HTTP_ERROR";
  public static final String CODE_CATALOG_SCHEMA_VALIDATION_ERROR = "CATALOG_SCHEMA_VALIDATION_ERROR";
  public static final String CODE_TOOL_EXECUTION_ERROR = "TOOL_EXECUTION_ERROR";
  public static final String CODE_PLAN_VALIDATION_ERROR = "PLAN_VALIDATION_ERROR";

  private CatalogToolResult() {}

  /** Validation or guard failure details before wrapping in {@link CatalogToolSupport}. */
  public record ErrorSpec(String code, String message, String hint) {}

  public static String success(ObjectMapper mapper, String tool, Object data) {
    return successMessage(mapper, tool, null, data);
  }

  public static String successMessage(
      ObjectMapper mapper, String tool, String message, Object data) {
    try {
      ObjectNode root = mapper.createObjectNode();
      root.put("ok", true);
      root.put("tool", tool);
      if (message != null && !message.isBlank()) {
        root.put("message", message);
      }
      root.set("data", mapper.valueToTree(data));
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      return fallbackSuccess(tool, message, String.valueOf(data));
    }
  }

  public static String error(ObjectMapper mapper, String tool, String code, String message) {
    return error(mapper, tool, code, message, null);
  }

  public static String error(
      ObjectMapper mapper, String tool, String code, String message, String hint) {
    try {
      ObjectNode root = mapper.createObjectNode();
      root.put("ok", false);
      root.put("tool", tool);
      ObjectNode err = mapper.createObjectNode();
      err.put("code", code);
      err.put("message", message);
      if (hint != null && !hint.isBlank()) {
        err.put("hint", hint);
      }
      root.set("error", err);
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      return fallbackError(tool, code, message, hint);
    }
  }

  public static boolean isError(ObjectMapper mapper, String out) {
    if (out == null || out.isBlank()) {
      return false;
    }
    try {
      JsonNode root = mapper.readTree(out);
      return root != null && root.isObject() && root.path("ok").asBoolean(false) == false
          && root.has("ok");
    } catch (Exception ignored) {
      return false;
    }
  }

  public static boolean isSuccess(ObjectMapper mapper, String out) {
    if (out == null || out.isBlank()) {
      return false;
    }
    try {
      JsonNode root = mapper.readTree(out);
      return root != null && root.isObject() && root.path("ok").asBoolean();
    } catch (Exception ignored) {
      return false;
    }
  }

  public static JsonNode dataOrNull(ObjectMapper mapper, String out) {
    if (out == null || out.isBlank()) {
      return null;
    }
    try {
      JsonNode root = mapper.readTree(out);
      if (root == null || !root.isObject() || !root.path("ok").asBoolean()) {
        return null;
      }
      return root.get("data");
    } catch (Exception ignored) {
      return null;
    }
  }

  public static String messageOrNull(ObjectMapper mapper, String out) {
    if (out == null || out.isBlank()) {
      return null;
    }
    try {
      JsonNode root = mapper.readTree(out);
      if (root == null || !root.isObject() || !root.path("ok").asBoolean()) {
        return null;
      }
      JsonNode msg = root.get("message");
      return msg != null && msg.isTextual() ? msg.asText() : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  public static String errorMessageOrNull(ObjectMapper mapper, String out) {
    if (out == null || out.isBlank()) {
      return null;
    }
    try {
      JsonNode root = mapper.readTree(out);
      if (root == null || !root.isObject() || root.path("ok").asBoolean()) {
        return null;
      }
      JsonNode err = root.get("error");
      if (err == null || !err.isObject()) {
        return null;
      }
      JsonNode msg = err.get("message");
      return msg != null && msg.isTextual() ? msg.asText() : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  public static String errorHintOrNull(ObjectMapper mapper, String out) {
    if (out == null || out.isBlank()) {
      return null;
    }
    try {
      JsonNode root = mapper.readTree(out);
      if (root == null || !root.isObject() || root.path("ok").asBoolean()) {
        return null;
      }
      JsonNode err = root.get("error");
      if (err == null || !err.isObject()) {
        return null;
      }
      JsonNode hint = err.get("hint");
      return hint != null && hint.isTextual() ? hint.asText() : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  /** Payload JSON for diary/logging: unwraps {@code data} when present, else returns {@code out}. */
  public static String unwrapDataJson(ObjectMapper mapper, String out) {
    JsonNode data = dataOrNull(mapper, out);
    if (data == null || data.isMissingNode() || data.isNull()) {
      return out;
    }
    try {
      return mapper.writeValueAsString(data);
    } catch (Exception e) {
      return out;
    }
  }

  private static String fallbackSuccess(String tool, String message, String dataText) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("ok", true);
    root.put("tool", tool);
    if (message != null && !message.isBlank()) {
      root.put("message", message);
    }
    root.put("data", dataText);
    return root.toString();
  }

  private static String fallbackError(String tool, String code, String message, String hint) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("code", code);
    err.put("message", message);
    if (hint != null && !hint.isBlank()) {
      err.put("hint", hint);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("ok", false);
    root.put("tool", tool);
    root.put("error", err);
    return root.toString();
  }
}
