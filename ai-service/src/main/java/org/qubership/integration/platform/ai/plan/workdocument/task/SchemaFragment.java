package org.qubership.integration.platform.ai.plan.workdocument.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One schema slice the server may show to a task, with its catalog provenance. */
public record SchemaFragment(
    String id, String stepId, String portName, String contentHash, String reference, String body) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** True when the selected schema contains this field path, including nested properties. */
  public boolean containsPath(String path) {
    if (path == null || path.isBlank() || "$".equals(path) || body == null || body.isBlank()) {
      return false;
    }
    JsonNode properties;
    try {
      properties = JSON.readTree(body).path("properties");
    } catch (Exception failure) {
      return false;
    }
    String rest = path.startsWith("$.") ? path.substring(2) : path;
    JsonNode current = properties;
    int start = 0;
    while (start <= rest.length()) {
      int dot = rest.indexOf('.', start);
      String name = dot < 0 ? rest.substring(start) : rest.substring(start, dot);
      if (name.isBlank() || !current.has(name)) {
        return false;
      }
      if (dot < 0) {
        return true;
      }
      JsonNode child = current.path(name);
      JsonNode nested = child.path("properties");
      if (!nested.isObject()) {
        nested = child.path("items").path("properties");
      }
      current = nested;
      start = dot + 1;
    }
    return false;
  }

  public JsonNode property(String path) {
    if (!containsPath(path)) {
      return null;
    }
    try {
      JsonNode current = JSON.readTree(body).path("properties");
      String rest = path.startsWith("$.") ? path.substring(2) : path;
      int start = 0;
      while (start <= rest.length()) {
        int dot = rest.indexOf('.', start);
        String name = dot < 0 ? rest.substring(start) : rest.substring(start, dot);
        JsonNode child = current.path(name);
        if (dot < 0) {
          return child;
        }
        JsonNode nested = child.path("properties");
        if (!nested.isObject()) {
          nested = child.path("items").path("properties");
        }
        current = nested;
        start = dot + 1;
      }
    } catch (Exception failure) {
      return null;
    }
    return null;
  }
}
