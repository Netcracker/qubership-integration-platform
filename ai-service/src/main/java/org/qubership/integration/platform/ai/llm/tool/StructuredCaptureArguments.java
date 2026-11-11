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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints;

/** Checks a structured capture call before Quarkus maps its arguments to a Java record. */
public final class StructuredCaptureArguments {

  public record Issue(String code, String path, String message) {}

  public record Result(ToolExecutionRequest request, List<Issue> issues) {
    public Result {
      issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** The first issue, which callers that report one issue per call keep reading. */
    public Issue issue() {
      return issues.isEmpty() ? null : issues.getFirst();
    }
  }

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
      List<Issue> issues = new ArrayList<>();
      collectIssues(input, parameters, parameters.definitions(), "", issues);
      if (!issues.isEmpty()) {
        return new Result(null, issues);
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
      return new Result(request.toBuilder().arguments(strict.writeValueAsString(input)).build(), List.of());
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

  private static void collectIssues(
      JsonNode value, JsonSchemaElement schema, Map<String, JsonSchemaElement> definitions,
      String path, List<Issue> issues) {
    if (schema instanceof JsonReferenceSchema reference) {
      collectIssues(value, definitions.get(reference.reference()), definitions, path, issues);
    } else if (schema instanceof JsonAnyOfSchema anyOf) {
      List<Issue> matchingShapeIssues = null;
      for (JsonSchemaElement alternative : anyOf.anyOf()) {
        List<Issue> found = new ArrayList<>();
        collectIssues(value, alternative, definitions, path, found);
        if (found.isEmpty()) {
          return;
        }
        Issue first = found.getFirst();
        if (!"INVALID_TYPE".equals(first.code()) || !path.equals(first.path())) {
          matchingShapeIssues = found;
        }
      }
      issues.addAll(matchingShapeIssues != null ? matchingShapeIssues : List.of(invalidType(path)));
    } else if (schema instanceof JsonNullSchema) {
      addUnless(value.isNull(), path, issues);
    } else if (schema instanceof JsonObjectSchema object) {
      if (!value.isObject()) {
        issues.add(invalidType(path));
        return;
      }
      for (String required : object.required()) {
        if (!value.has(required) && !allowsNull(object.properties().get(required))) {
          issues.add(new Issue("MISSING_FIELD", path + "/" + required, "Include this field."));
        }
      }
      for (var fields = value.fields(); fields.hasNext();) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonSchemaElement child = object.properties().get(field.getKey());
        String childPath = path + "/" + field.getKey();
        if (child == null && !Boolean.TRUE.equals(object.additionalProperties())) {
          issues.add(new Issue("UNEXPECTED_FIELD", childPath, "Remove this unsupported field."));
        } else if (child != null) {
          collectIssues(field.getValue(), child, definitions, childPath, issues);
        }
      }
    } else if (schema instanceof JsonArraySchema array) {
      if (!value.isArray()) {
        issues.add(invalidType(path));
        return;
      }
      for (int i = 0; i < value.size(); i++) {
        collectIssues(value.get(i), array.items(), definitions, path + "/" + i, issues);
      }
    } else if (schema instanceof JsonEnumSchema enumeration) {
      addUnless(value.isTextual() && enumeration.enumValues().contains(value.textValue()), path, issues);
    } else if (schema instanceof JsonStringSchema) {
      addUnless(value.isTextual(), path, issues);
    } else if (schema instanceof JsonIntegerSchema) {
      addUnless(value.isIntegralNumber() && value.canConvertToInt(), path, issues);
    } else if (schema instanceof JsonBooleanSchema) {
      addUnless(value.isBoolean(), path, issues);
    }
  }

  public static boolean allowsNull(JsonSchemaElement schema) {
    return schema instanceof JsonNullSchema || schema instanceof JsonAnyOfSchema anyOf
        && anyOf.anyOf().stream().anyMatch(StructuredCaptureArguments::allowsNull);
  }

  private static void addUnless(boolean valid, String path, List<Issue> issues) {
    if (!valid) {
      issues.add(invalidType(path));
    }
  }

  private static Result invalid(String code, String path, String message) {
    return new Result(null, List.of(new Issue(code, path, message)));
  }

  private static Issue invalidType(String path) {
    return new Issue("INVALID_TYPE", path, "Use the type declared by this field's schema.");
  }
}
