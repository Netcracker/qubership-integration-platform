package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/** In-memory mapper-2 executor for declarative copy and constant actions. */
public final class SimpleMapper2Executor {

  private static final ObjectMapper JSON = new ObjectMapper();

  private SimpleMapper2Executor() {}

  public static String apply(ChainPlanGraph graph, String jsonBody) {
    try {
      JsonNode input = JSON.readTree(jsonBody == null ? "{}" : jsonBody);
      ObjectNode output = JSON.createObjectNode();
      if (graph != null) {
        for (ChainPlanNode node : graph.nodes()) {
          if (!MappingExecutionSite.isConfigured(node)) {
            continue;
          }
          applyActions(input, output, MappingExecutionSite.mappingDescription(node));
        }
      }
      return JSON.writeValueAsString(output);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot apply mapper-2 mapping to the payload", e);
    }
  }

  private static void applyActions(JsonNode input, ObjectNode output, String mappingDescription) {
    try {
      JsonNode actions = JSON.readTree(mappingDescription);
      if (!actions.isArray()) {
        return;
      }
      for (JsonNode action : actions) {
        String targetPath = text(action, "targetPath");
        if (targetPath.isEmpty()) {
          continue;
        }
        if ("constant".equals(text(action, "kind"))) {
          setPath(output, targetPath, action.path("value").asText(""));
          continue;
        }
        JsonNode source = readPath(input, text(action, "sourcePath"));
        if (source != null && !source.isMissingNode()) {
          setPath(output, targetPath, source.asText());
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read mapper-2 mappingDescription", e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : value.asText("");
  }

  private static JsonNode readPath(JsonNode root, String path) {
    JsonNode current = root;
    for (String segment : segments(path)) {
      if (current == null || current.isMissingNode()) {
        return current;
      }
      current = current.path(segment);
    }
    return current;
  }

  private static void setPath(ObjectNode root, String path, String value) {
    String[] parts = segments(path);
    ObjectNode current = root;
    for (int i = 0; i < parts.length - 1; i++) {
      JsonNode child = current.get(parts[i]);
      if (child == null || !child.isObject()) {
        ObjectNode next = JSON.createObjectNode();
        current.set(parts[i], next);
        current = next;
      } else {
        current = (ObjectNode) child;
      }
    }
    current.put(parts[parts.length - 1], value);
  }

  private static String[] segments(String path) {
    String trimmed = path == null ? "" : path.trim();
    if (trimmed.startsWith("$.")) {
      trimmed = trimmed.substring(2);
    } else if (trimmed.startsWith("$")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.startsWith(".")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.isEmpty()) {
      return new String[0];
    }
    return trimmed.split("\\.");
  }
}
