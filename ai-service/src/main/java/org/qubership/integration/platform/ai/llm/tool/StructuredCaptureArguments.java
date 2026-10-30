package org.qubership.integration.platform.ai.llm.tool;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints;

/** Checks a structured capture call before Quarkus maps its arguments to a Java record. */
public final class StructuredCaptureArguments {

  public record Issue(String code, String path, String message) {}

  public record Result(ToolExecutionRequest request, Issue issue) {}

  public record Nesting(JsonNode arguments, Map<String, String> relocations) {}

  private StructuredCaptureArguments() {}

  /** Restores one argument-envelope level without inventing values or choosing between destinations. */
  public static Nesting restoreNesting(JsonNode input, JsonObjectSchema parameters) {
    if (input == null || !input.isObject() || Boolean.TRUE.equals(parameters.additionalProperties())) {
      return new Nesting(input, Map.of());
    }
    com.fasterxml.jackson.databind.node.ObjectNode restored = input.deepCopy();
    Map<String, String> relocations = new java.util.LinkedHashMap<>();
    input.fields().forEachRemaining(field -> {
      if (parameters.properties().containsKey(field.getKey())) {
        return;
      }
      var destinations = parameters.properties().entrySet().stream()
          .filter(entry -> entry.getValue() instanceof JsonObjectSchema object
              && object.properties().containsKey(field.getKey()))
          .map(Map.Entry::getKey).toList();
      if (destinations.size() != 1) {
        return;
      }
      String destination = destinations.getFirst();
      if (!(restored.get(destination) instanceof com.fasterxml.jackson.databind.node.ObjectNode target)
          || target.has(field.getKey())) {
        return;
      }
      target.set(field.getKey(), restored.remove(field.getKey()));
      String fieldPath = "/" + field.getKey().replace("~", "~0").replace("/", "~1");
      relocations.put(fieldPath, "/" + destination.replace("~", "~0").replace("/", "~1") + fieldPath);
    });
    return new Nesting(restored, Map.copyOf(relocations));
  }

  public static Result inspect(
      ToolExecutionRequest request, ToolMethodCreateInfo method, ObjectMapper mapper) {
    ObjectMapper strict = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    try {
      JsonNode input = parseJson(strict, request.arguments(), "outer");
      if (!input.isObject()) {
        return invalid("INVALID_TYPE", "/", "Send one JSON argument object.");
      }
      JsonObjectSchema parameters = org.qubership.integration.platform.ai.plan.RequirementCaptureSchemas
          .specification(method.toolSpecification()).parameters();
      String parameter = parameters.properties().keySet()
          .iterator().next();
      if (!input.has(parameter)) {
        return invalid("MISSING_FIELD", "/" + parameter, "Include this field.");
      }
      JsonNode capture = input.get(parameter);
      if (capture.isTextual()) {
        capture = parseJson(strict, capture.textValue(), "decoded named argument /" + parameter.replace("~", "~0").replace("/", "~1"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).set(parameter, capture);
      }
      Issue issue = unknownField(
          input, parameters, parameters.definitions(), "");
      if (issue != null) {
        return new Result(null, issue);
      }
      if (!capture.isObject()) {
        return invalid("INVALID_TYPE", "/" + parameter, "This field must be an object.");
      }
      if ("captureRequirementBrief".equals(method.methodName())
          && method.toolSpecification().parameters().properties().containsKey("capture")) {
        for (String field : java.util.List.of("goal", "summary", "inputs", "assumptions", "citations", "sourceOmissions")) {
          if (!capture.hasNonNull(field)) {
            return invalid("MISSING_FIELD", "/capture/" + field, "Include this field.");
          }
        }
      }
      return new Result(request.toBuilder().arguments(strict.writeValueAsString(input)).build(), null);
    } catch (IOException error) {
      return invalid(jsonErrorCode(error), "/", syntaxMessage("Send one valid JSON argument object.", error));
    }
  }

  /** Parses one existing argument layer while retaining the native failure and its coordinate space. */
  public static JsonNode parseJson(ObjectMapper mapper, String arguments, String layer) throws IOException {
    try {
      if (arguments == null || arguments.isBlank()) {
        throw new IOException("Empty JSON arguments");
      }
      try (JsonParser parser = mapper.getFactory().createParser(arguments)) {
        JsonNode input = mapper.readTree(parser);
        if (parser.nextToken() != null) {
          throw new JsonParseException(parser, "Trailing JSON content", parser.currentTokenLocation());
        }
        return input;
      }
    } catch (IOException error) {
      String details = "layer=" + layer;
      if (error instanceof JsonProcessingException nativeError) {
        if (nativeError.getProcessor() instanceof JsonParser parser) {
          details += "; context=" + parser.getParsingContext().pathAsPointer();
        }
        var location = nativeError.getLocation();
        if (location != null) {
          details += "; line=" + location.getLineNr() + "; column=" + location.getColumnNr()
              + "; charOffset=" + location.getCharOffset() + "; byteOffset=" + location.getByteOffset();
        }
        details += "; reason=" + nativeError.getOriginalMessage();
      } else {
        details += "; location unavailable; reason=" + error.getMessage();
      }
      throw new IOException(ToolCallFingerprints.NATIVE_PARSER_DETAILS + details, error);
    }
  }

  /** Appends diagnostics only for a proven failure of the strict argument parser. */
  public static String syntaxMessage(String legacyMessage, IOException error) {
    return error.getMessage() != null && error.getMessage().startsWith(ToolCallFingerprints.NATIVE_PARSER_DETAILS)
        ? legacyMessage + error.getMessage() : legacyMessage;
  }

  public static String jsonErrorCode(IOException error) {
    Throwable nativeError = error.getCause() == null ? error : error.getCause();
    return nativeError instanceof JsonParseException && nativeError.getMessage() != null
        && nativeError.getMessage().contains("Duplicate field") ? "DUPLICATE_JSON_KEY" : "INVALID_JSON";
  }

  private static Issue unknownField(
      JsonNode value, JsonSchemaElement schema, Map<String, JsonSchemaElement> definitions,
      String path) {
    if (schema instanceof JsonReferenceSchema reference) {
      return unknownField(value, definitions.get(reference.reference()), definitions, path);
    }
    if (schema instanceof JsonAnyOfSchema anyOf) {
      Issue matchingShapeIssue = null;
      for (JsonSchemaElement alternative : anyOf.anyOf()) {
        Issue issue = unknownField(value, alternative, definitions, path);
        if (issue == null) {
          return null;
        }
        if (!"INVALID_TYPE".equals(issue.code()) || !path.equals(issue.path())) {
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
      for (String required : object.required()) {
        if (!value.has(required)) {
          return new Issue("MISSING_FIELD", path + "/" + required, "Include this field.");
        }
      }
      for (var fields = value.fields(); fields.hasNext();) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonSchemaElement child = object.properties().get(field.getKey());
        String childPath = path + "/" + field.getKey();
        if (child == null && Boolean.TRUE.equals(object.additionalProperties())) {
          continue;
        }
        if (child == null) {
          return new Issue("UNEXPECTED_FIELD", childPath, "Remove this unsupported field.");
        }
        Issue nested = unknownField(field.getValue(), child, definitions, childPath);
        if (nested != null) {
          return nested;
        }
      }
    } else if (schema instanceof JsonArraySchema array) {
      if (!value.isArray()) {
        return invalidType(path);
      }
      for (int i = 0; i < value.size(); i++) {
        Issue nested = unknownField(value.get(i), array.items(), definitions, path + "/" + i);
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
    return new Result(null, new Issue(code, path, message));
  }

  private static Issue invalidType(String path) {
    return new Issue("INVALID_TYPE", path, "Use the type declared by this field's schema.");
  }
}
