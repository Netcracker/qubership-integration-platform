package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;

/** Exposes the capture DTOs with closed, required, and explicitly nullable schemas. */
@ApplicationScoped
public class RequirementCaptureToolProvider implements ToolProvider {

  private final RequirementCaptureTools tools;
  private final ObjectMapper mapper;

  @Inject
  public RequirementCaptureToolProvider(RequirementCaptureTools tools, ObjectMapper mapper) {
    this.tools = tools;
    this.mapper = mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  }

  @Override
  public ToolProviderResult provideTools(ToolProviderRequest request) {
    JsonObjectSchema capture = RequirementCaptureSchemas.parameters("draft", DraftInput.class);
    JsonObjectSchema update = RequirementCaptureSchemas.parameters("changes", DraftUpdate.class);
    JsonObjectSchema read = RequirementCaptureSchemas.emptyParameters();
    JsonObjectSchema finish =
        RequirementCaptureSchemas.parameters("directive", RequirementDiscoveryDirective.class);
    return ToolProviderResult.builder()
        .add(spec("captureRequirementDraft", DraftInput.class, capture),
            (call, memoryId) -> inConversation(memoryId, () -> execute(
                call.arguments(), "captureRequirementDraft", capture,
                "draft", DraftInput.class, tools::captureRequirementDraft)))
        .add(spec("updateRequirementDraft", DraftUpdate.class, update),
            (call, memoryId) -> inConversation(memoryId, () -> execute(
                call.arguments(), "updateRequirementDraft", update,
                "changes", DraftUpdate.class, tools::updateRequirementDraft)))
        .add(spec("readRequirementDraft", null, read),
            (call, memoryId) -> inConversation(memoryId, () -> executeRead(call.arguments(), read)))
        .add(spec("finishRequirementDiscoveryTurn", RequirementDiscoveryDirective.class, finish),
            (call, memoryId) -> inConversation(memoryId, () -> execute(
                call.arguments(), "finishRequirementDiscoveryTurn", finish,
                "directive", RequirementDiscoveryDirective.class,
                tools::finishRequirementDiscoveryTurn)))
        .build();
  }

  private static String inConversation(Object memoryId, Supplier<String> action) {
    String id = memoryId == null ? ToolSession.resolveConversationId() : memoryId.toString();
    try (ToolSession.Handle ignored = ToolSession.open(id)) {
      return action.get();
    }
  }

  private <T> String execute(
      String arguments, String toolName, JsonObjectSchema parameters,
      String field, Class<T> type, Function<T, String> action) {
    try {
      JsonNode input = parse(arguments);
      Issue issue = check(input, parameters, "");
      if (issue != null) {
        return tools.rejectArguments(toolName, issue);
      }
      return action.apply(mapper.treeToValue(input.get(field), type));
    } catch (JsonProcessingException error) {
      return tools.rejectArguments(toolName, new Issue(
          jsonErrorCode(error), "/", null, "Send one valid JSON argument object."));
    } catch (IOException error) {
      return tools.rejectArguments(toolName, new Issue(
          "INVALID_JSON", "/", null, "Send one valid JSON argument object."));
    }
  }

  private String executeRead(String arguments, JsonObjectSchema parameters) {
    try {
      Issue issue = check(parse(arguments), parameters, "");
      return issue == null ? tools.readRequirementDraft()
          : tools.rejectArguments("readRequirementDraft", issue);
    } catch (IOException error) {
      return tools.rejectArguments("readRequirementDraft", new Issue(
          jsonErrorCode(error), "/", null, "Send an empty JSON object."));
    }
  }

  private JsonNode parse(String arguments) throws IOException {
    JsonNode input = parseOnce(arguments);
    if (input != null && input.isTextual()) {
      input = parseOnce(input.textValue());
    }
    return input;
  }

  private JsonNode parseOnce(String input) throws IOException {
    if (input == null || input.isBlank()) {
      throw new IOException("Empty JSON arguments");
    }
    try (JsonParser parser = mapper.getFactory().createParser(input)) {
      JsonNode value = mapper.readTree(parser);
      if (parser.nextToken() != null) {
        throw new IOException("Trailing JSON content");
      }
      return value;
    }
  }

  private static String jsonErrorCode(IOException error) {
    return error instanceof JsonParseException
        && error.getMessage() != null
        && error.getMessage().contains("Duplicate field")
        ? "DUPLICATE_JSON_KEY" : "INVALID_JSON";
  }

  private static Issue check(JsonNode value, JsonSchemaElement schema, String path) {
    if (schema instanceof JsonAnyOfSchema anyOf) {
      for (JsonSchemaElement alternative : anyOf.anyOf()) {
        if (check(value, alternative, path) == null) {
          return null;
        }
      }
      return issue("INVALID_TYPE", path, "Use a value allowed by this field.");
    }
    if (schema instanceof JsonNullSchema) {
      return value != null && value.isNull() ? null
          : issue("INVALID_TYPE", path, "This field must be null.");
    }
    if (schema instanceof JsonObjectSchema object) {
      if (value == null || !value.isObject()) {
        return issue("INVALID_TYPE", path, "This field must be an object.");
      }
      for (String required : object.required()) {
        if (!value.has(required)) {
          return issue("MISSING_FIELD", path + "/" + required,
              "Include this field; use null only when its schema allows null.");
        }
      }
      for (var fields = value.fields(); fields.hasNext();) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonSchemaElement child = object.properties().get(field.getKey());
        if (child == null) {
          return issue("UNEXPECTED_FIELD", path + "/" + field.getKey(),
              "Remove this unsupported field.");
        }
        Issue nested = check(field.getValue(), child, path + "/" + field.getKey());
        if (nested != null) {
          return nested;
        }
      }
      return null;
    }
    if (schema instanceof JsonArraySchema array) {
      if (value == null || !value.isArray()) {
        return issue("INVALID_TYPE", path, "This field must be an array.");
      }
      for (int i = 0; i < value.size(); i++) {
        Issue nested = check(value.get(i), array.items(), path + "/" + i);
        if (nested != null) {
          return nested;
        }
      }
      return null;
    }
    if (schema instanceof JsonEnumSchema enumeration) {
      return value != null && value.isTextual()
          && enumeration.enumValues().contains(value.textValue()) ? null
          : issue("INVALID_ENUM", path, "Use one of the values in this field's schema.");
    }
    if (schema instanceof JsonStringSchema) {
      return value != null && value.isTextual() ? null
          : issue("INVALID_TYPE", path, "This field must be a string.");
    }
    if (schema instanceof JsonIntegerSchema) {
      return value != null && value.isIntegralNumber() && value.canConvertToInt() ? null
          : issue("INVALID_TYPE", path, "This field must be an integer.");
    }
    if (schema instanceof JsonBooleanSchema) {
      return value != null && value.isBoolean() ? null
          : issue("INVALID_TYPE", path, "This field must be true or false.");
    }
    throw new IllegalStateException("Unsupported capture schema: " + schema);
  }

  private static Issue issue(String code, String path, String message) {
    return new Issue(code, path.isEmpty() ? "/" : path, null, message);
  }

  private static ToolSpecification spec(
      String name, Class<?> parameterType, JsonObjectSchema parameters) {
    try {
      Tool annotation = (parameterType == null
          ? RequirementCaptureTools.class.getMethod(name)
          : RequirementCaptureTools.class.getMethod(name, parameterType))
          .getAnnotation(Tool.class);
      return ToolSpecification.builder()
          .name(name)
          .description(String.join(" ", annotation.value()))
          .parameters(parameters)
          .build();
    } catch (NoSuchMethodException error) {
      throw new IllegalStateException("Requirement tool definition is missing: " + name, error);
    }
  }

  /** Resolves the CDI provider for the gather agent only. */
  @ApplicationScoped
  public static final class ProviderSupplier implements java.util.function.Supplier<ToolProvider> {
    @Override
    public ToolProvider get() {
      return Arc.container().instance(RequirementCaptureToolProvider.class).get();
    }
  }
}
