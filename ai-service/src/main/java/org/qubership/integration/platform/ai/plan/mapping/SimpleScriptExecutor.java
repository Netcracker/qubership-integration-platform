package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/** In-memory executor for the Groovy subset emitted by {@link ScriptConfigurationPhase}. */
public final class SimpleScriptExecutor {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern SOURCE_SEGMENT = Pattern.compile("\\['([^']+)'\\]");
  private static final Pattern ASSIGNMENT =
      Pattern.compile("target\\['([^']+)'\\]\\s*=\\s*(.+)");

  private SimpleScriptExecutor() {}

  public static String apply(ChainPlanGraph graph, String jsonBody) {
    try {
      JsonNode input = JSON.readTree(jsonBody == null ? "{}" : jsonBody);
      ObjectNode output = JSON.createObjectNode();
      if (graph != null) {
        for (ChainPlanNode node : graph.nodes()) {
          if (!MappingExecutionSite.isScript(node) || !MappingExecutionSite.isConfigured(node)) {
            continue;
          }
          applyScript(input, output, MappingExecutionSite.scriptBody(node));
        }
      }
      return JSON.writeValueAsString(output);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot apply generated script mapping to the payload", e);
    }
  }

  static String applyNode(ChainPlanNode node, String jsonBody) {
    try {
      JsonNode input = JSON.readTree(jsonBody == null ? "{}" : jsonBody);
      ObjectNode output = JSON.createObjectNode();
      applyScript(input, output, MappingExecutionSite.scriptBody(node));
      return JSON.writeValueAsString(output);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot apply generated script mapping to the payload", e);
    }
  }

  private static void applyScript(JsonNode input, ObjectNode output, String script) {
    if (script == null || script.isBlank()) {
      return;
    }
    for (String line : script.split("\\R")) {
      String trimmed = line.trim();
      Matcher matcher = ASSIGNMENT.matcher(trimmed);
      if (!matcher.matches()) {
        continue;
      }
      String targetPath = matcher.group(1);
      String rhs = matcher.group(2).trim();
      setPath(output, targetPath, evaluate(input, rhs));
    }
  }

  private static String evaluate(JsonNode input, String rhs) {
    if ((rhs.startsWith("'") && rhs.endsWith("'") && rhs.length() >= 2)
        || (rhs.startsWith("\"") && rhs.endsWith("\"") && rhs.length() >= 2)) {
      return rhs.substring(1, rhs.length() - 1);
    }
    String sourcePath = sourcePath(rhs);
    JsonNode source = readPath(input, sourcePath);
    String value =
        source == null || source.isMissingNode() || source.isNull() ? "" : source.asText();
    if (rhs.contains("toUpperCase")) {
      return value.toUpperCase(Locale.ROOT);
    }
    if (rhs.contains("toLowerCase")) {
      return value.toLowerCase(Locale.ROOT);
    }
    if (rhs.contains("trim()")) {
      return value.trim();
    }
    return value;
  }

  private static String sourcePath(String rhs) {
    StringBuilder path = new StringBuilder();
    Matcher matcher = SOURCE_SEGMENT.matcher(rhs);
    while (matcher.find()) {
      if (!path.isEmpty()) {
        path.append('.');
      }
      path.append(matcher.group(1));
    }
    return path.toString();
  }

  private static JsonNode readPath(JsonNode root, String path) {
    JsonNode current = root;
    for (String segment : path.split("\\.")) {
      if (segment.isEmpty()) {
        continue;
      }
      if (current == null || current.isMissingNode()) {
        return current;
      }
      current = current.path(segment);
    }
    return current;
  }

  private static void setPath(ObjectNode root, String path, String value) {
    root.put(path, value);
  }
}
