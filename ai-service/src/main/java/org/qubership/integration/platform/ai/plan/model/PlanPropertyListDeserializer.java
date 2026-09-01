package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deserializer for {@link ChainPlanNode#properties()} that accepts a few unambiguous shapes.
 *
 * <p>LLMs sometimes emit script bodies as a raw string array ({@code ["def x = 1"]}) instead of
 * {@code [{"key":"script","value":"def x = 1"}]}. That shape fails LangChain4j argument binding
 * with {@code ToolArgumentsException} before {@code captureGraphPatch} runs, so it is mapped here
 * to the property it can only mean.
 *
 * <p>Coercion stops where the property key would have to be guessed. A shape that names no key —
 * a keyless {@code {"value": "..."}}, for instance — reports an input mismatch rather than
 * defaulting to {@code script}, because a wrong key binds the text to the wrong element and
 * nothing downstream can tell. The mismatch surfaces as {@code ToolArgumentsException}, which the
 * capture-repair path already retries with feedback.
 */
public final class PlanPropertyListDeserializer extends JsonDeserializer<List<PlanProperty>> {

  static final String SCRIPT_PROPERTY_KEY = "script";

  @Override
  public List<PlanProperty> deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    JsonToken token = parser.currentToken();
    if (token == JsonToken.VALUE_NULL || token == null) {
      return null;
    }
    if (token == JsonToken.VALUE_STRING) {
      String body = parser.getValueAsString();
      if (body == null || body.isBlank()) {
        return List.of();
      }
      return List.of(new PlanProperty(SCRIPT_PROPERTY_KEY, body));
    }
    if (token != JsonToken.START_ARRAY) {
      return context.reportInputMismatch(
          List.class,
          "node.properties must be an array of {key,value} objects (or a script-body string);"
              + " got %s",
          token);
    }

    List<PlanProperty> properties = new ArrayList<>();
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      PlanProperty property = readElement(parser, context);
      if (property != null) {
        properties.add(property);
      }
    }
    return List.copyOf(properties);
  }

  private static PlanProperty readElement(JsonParser parser, DeserializationContext context)
      throws IOException {
    JsonToken token = parser.currentToken();
    if (token == JsonToken.VALUE_NULL) {
      return null;
    }
    if (token == JsonToken.VALUE_STRING) {
      String body = parser.getValueAsString();
      if (body == null || body.isBlank()) {
        return null;
      }
      return new PlanProperty(SCRIPT_PROPERTY_KEY, body);
    }
    if (token == JsonToken.START_OBJECT) {
      JsonNode node = parser.getCodec().readTree(parser);
      return fromObject(node, context);
    }
    return context.reportInputMismatch(
        PlanProperty.class,
        "node.properties entries must be {key,value} objects or script-body strings; got %s",
        token);
  }

  private static PlanProperty fromObject(JsonNode node, DeserializationContext context)
      throws IOException {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode keyNode = node.get("key");
    JsonNode valueNode = node.get("value");
    if (keyNode != null && !keyNode.isNull() && keyNode.isTextual()) {
      String key = keyNode.asText();
      if (key.isBlank()) {
        return context.reportInputMismatch(PlanProperty.class, "property.key must be non-blank");
      }
      String value = valueNode == null || valueNode.isNull() ? null : valueAsString(valueNode);
      return new PlanProperty(key, value);
    }
    // A keyless {"value": "..."} names no property, so the key can only be guessed. Reporting a
    // mismatch routes the call into the tool-argument retry instead of mislabeling it as a script.
    JsonNode scriptNode = node.get(SCRIPT_PROPERTY_KEY);
    if (scriptNode != null && scriptNode.isTextual() && !scriptNode.asText().isBlank()) {
      return new PlanProperty(SCRIPT_PROPERTY_KEY, scriptNode.asText());
    }
    return context.reportInputMismatch(
        PlanProperty.class,
        "property object must include a textual key with its value (or a script body under"
            + " \"script\")");
  }

  private static String valueAsString(JsonNode valueNode) {
    if (valueNode.isTextual() || valueNode.isNumber() || valueNode.isBoolean()) {
      return valueNode.asText();
    }
    return valueNode.toString();
  }
}
