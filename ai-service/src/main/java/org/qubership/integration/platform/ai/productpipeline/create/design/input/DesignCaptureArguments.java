package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import java.io.IOException;
import java.util.Map;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureTool.CaptureIssue;

/** Checks the design call before Quarkus maps its arguments to a Java record. */
public final class DesignCaptureArguments {

  public record Result(ToolExecutionRequest request, CaptureIssue issue) {}

  private DesignCaptureArguments() {}

  public static Result inspect(
      ToolExecutionRequest request, ToolMethodCreateInfo method, ObjectMapper mapper) {
    ObjectMapper strict = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    try {
      JsonNode input = parse(strict, request.arguments());
      if (!input.isObject()) {
        return invalid("INVALID_TYPE", "/", "Send one JSON argument object.");
      }
      String parameter = method.toolSpecification().parameters().properties().keySet()
          .iterator().next();
      if (!input.has(parameter)) {
        return invalid("MISSING_FIELD", "/" + parameter, "Include this field.");
      }
      JsonNode capture = input.get(parameter);
      if (capture.isTextual()) {
        capture = parse(strict, capture.textValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).set(parameter, capture);
      }
      CaptureIssue issue = unknownField(
          input, method.toolSpecification().parameters(),
          method.toolSpecification().parameters().definitions(), "");
      if (issue != null) {
        return new Result(null, issue);
      }
      if (!capture.isObject()) {
        return invalid("INVALID_TYPE", "/" + parameter, "This field must be an object.");
      }
      return new Result(request.toBuilder().arguments(strict.writeValueAsString(input)).build(), null);
    } catch (IOException error) {
      String code = error instanceof JsonParseException
          && error.getMessage() != null && error.getMessage().contains("Duplicate field")
          ? "DUPLICATE_JSON_KEY" : "INVALID_JSON";
      return invalid(code, "/", "Send one valid JSON argument object.");
    }
  }

  private static JsonNode parse(ObjectMapper mapper, String arguments) throws IOException {
    if (arguments == null || arguments.isBlank()) {
      throw new IOException("Empty JSON arguments");
    }
    try (JsonParser parser = mapper.getFactory().createParser(arguments)) {
      JsonNode input = mapper.readTree(parser);
      if (parser.nextToken() != null) {
        throw new IOException("Trailing JSON content");
      }
      return input;
    }
  }

  private static CaptureIssue unknownField(
      JsonNode value, JsonSchemaElement schema, Map<String, JsonSchemaElement> definitions,
      String path) {
    if (schema instanceof JsonReferenceSchema reference) {
      return unknownField(value, definitions.get(reference.reference()), definitions, path);
    }
    if (schema instanceof JsonAnyOfSchema anyOf) {
      CaptureIssue matchingShapeIssue = null;
      for (JsonSchemaElement alternative : anyOf.anyOf()) {
        CaptureIssue issue = unknownField(value, alternative, definitions, path);
        if (issue == null) {
          return null;
        }
        if (!"INVALID_TYPE".equals(issue.code())) {
          matchingShapeIssue = issue;
        }
      }
      return matchingShapeIssue != null ? matchingShapeIssue : invalidType(path);
    }
    if (schema instanceof JsonNullSchema) {
      return value.isNull() ? null : invalidType(path);
    }
    if (schema instanceof JsonObjectSchema object) {
      if (!value.isObject()) {
        return invalidType(path);
      }
      for (var fields = value.fields(); fields.hasNext();) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonSchemaElement child = object.properties().get(field.getKey());
        String childPath = path + "/" + field.getKey();
        if (child == null) {
          return new CaptureIssue("UNEXPECTED_FIELD", childPath, "Remove this unsupported field.");
        }
        CaptureIssue nested = unknownField(field.getValue(), child, definitions, childPath);
        if (nested != null) {
          return nested;
        }
      }
    } else if (schema instanceof JsonArraySchema array) {
      if (!value.isArray()) {
        return invalidType(path);
      }
      for (int i = 0; i < value.size(); i++) {
        CaptureIssue nested = unknownField(value.get(i), array.items(), definitions, path + "/" + i);
        if (nested != null) {
          return nested;
        }
      }
    } else if (schema instanceof JsonEnumSchema enumeration) {
      return value.isTextual() && enumeration.enumValues().contains(value.textValue())
          ? null : invalidType(path);
    } else if (schema instanceof JsonStringSchema) {
      return value.isTextual() ? null : invalidType(path);
    } else if (schema instanceof JsonIntegerSchema) {
      return value.isIntegralNumber() && value.canConvertToInt() ? null : invalidType(path);
    } else if (schema instanceof JsonBooleanSchema) {
      return value.isBoolean() ? null : invalidType(path);
    }
    return null;
  }

  private static Result invalid(String code, String path, String message) {
    return new Result(null, new CaptureIssue(code, path, message));
  }

  private static CaptureIssue invalidType(String path) {
    return new CaptureIssue("INVALID_TYPE", path, "Use the type declared by this field's schema.");
  }
}
