package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonParser;
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
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;

/** Builds capture tools for the explicit gather supplier, not for default CDI tool lookup. */
@ApplicationScoped
public class RequirementCaptureToolProvider {

  public record ArgumentBinding(String toolCallId, String rawArguments, JsonNode effectiveArguments,
      Map<String, String> relocations) {}

  private static final ThreadLocal<ArgumentBinding> ARGUMENT_BINDING = new ThreadLocal<>();

  static ArgumentBinding argumentBinding() {
    return ARGUMENT_BINDING.get();
  }

  private final RequirementCaptureTools tools;
  private final ObjectMapper mapper;
  private final org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;

  @Inject
  public RequirementCaptureToolProvider(RequirementCaptureTools tools, ObjectMapper mapper,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this.conversations = conversations;
    this.tools = tools;
    this.mapper = mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  }

  public RequirementCaptureToolProvider(RequirementCaptureTools tools, ObjectMapper mapper) {
    this(tools, mapper, null);
  }

  public ToolProviderResult provideTools(ToolProviderRequest request) {
    JsonObjectSchema capture = RequirementCaptureSchemas.parameters("draft", DraftInput.class);
    JsonObjectSchema update = RequirementCaptureSchemas.parameters("changes", DraftUpdate.class);
    JsonObjectSchema read = RequirementCaptureSchemas.emptyParameters();
    JsonObjectSchema finish =
        RequirementCaptureSchemas.parameters("directive", RequirementDiscoveryDirective.class);
    String chatId = request == null || request.chatMemoryId() == null ? null : request.chatMemoryId().toString();
    var authority = conversations == null || chatId == null ? null : conversations.archive().read(chatId);
    var expected = request == null ? null : request.invocationParameters();
    var result = ToolProviderResult.builder()
        .add(spec("captureRequirementDraft", DraftInput.class, capture),
            (call, memoryId) -> inConversation(memoryId, authority, expected, () -> execute(
                call, "captureRequirementDraft", capture,
                "draft", DraftInput.class, tools::captureRequirementDraft)))
        .add(spec("updateRequirementDraft", DraftUpdate.class, update),
            (call, memoryId) -> inConversation(memoryId, authority, expected, () -> execute(
                call, "updateRequirementDraft", update,
                "changes", DraftUpdate.class, tools::updateRequirementDraft)))
        .add(spec("readRequirementDraft", null, read),
            (call, memoryId) -> inConversation(memoryId, authority, expected, () -> executeRead(call.arguments(), read)))
        .add(spec("finishRequirementDiscoveryTurn", RequirementDiscoveryDirective.class, finish),
            (call, memoryId) -> inConversation(memoryId, authority, expected, () -> execute(
                call, "finishRequirementDiscoveryTurn", finish,
                "directive", RequirementDiscoveryDirective.class,
                tools::finishRequirementDiscoveryTurn)))
        .add(spec("readRequirementSource", RequirementCaptureTools.SourceRead.class,
                RequirementCaptureSchemas.parameters("source", RequirementCaptureTools.SourceRead.class)),
            (call, memoryId) -> inConversation(memoryId, authority, expected, () -> execute(call,
                "readRequirementSource", RequirementCaptureSchemas.parameters("source", RequirementCaptureTools.SourceRead.class),
                "source", RequirementCaptureTools.SourceRead.class, tools::readRequirementSource)));
    if (request != null) {
      var factory = Arc.container().instance(io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutorFactory.class).get();
      for (Class<?> type : List.of(SelectApiHubCandidateTool.class,
          org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools.class,
          org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools.class,
          CatalogFirstApiHubDiscoveryTool.class,
          org.qubership.integration.platform.ai.productpipeline.knowledge.RequirementDiscoveryKnowledgeTool.class)) {
        for (var method : io.quarkiverse.langchain4j.runtime.ToolsRecorder.getMetadata().get(type.getName())) {
          var executor = factory.create(new io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor.Context(
              Arc.container().instance(type).get(), method.invokerClassName(), method.methodName(),
              method.argumentMapperClassName(), method.executionModel(), method.returnBehavior(), false, method));
          result.add(method.toolSpecification(), (call, memoryId) -> inConversation(memoryId, authority, expected,
              () -> executor.executeWithContext(call, request.invocationContext()).resultText()));
        }
      }
    }
    return result.build();
  }

  private String inConversation(Object memoryId,
      org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.Document authority,
      dev.langchain4j.invocation.InvocationParameters parameters, Supplier<String> action) {
    String id = memoryId == null ? ToolSession.resolveConversationId() : memoryId.toString();
    try (ToolSession.Handle ignored = ToolSession.open(id)) {
      Supplier<String> guarded = parameters != null && parameters.get(org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.CONTEXT_KEY) != null
          ? () -> org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.withBinding(
              id, parameters, org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Mode.DISCOVERY,
              binding -> action.get()) : action;
      if (conversations != null && authority != null) {
        return conversations.archive().withAuthority(id, authority.generation(),
            org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(authority), guarded);
      }
      return guarded.get();
    }
  }

  private <T> String execute(
      dev.langchain4j.agent.tool.ToolExecutionRequest call, String toolName, JsonObjectSchema parameters,
      String field, Class<T> type, Function<T, String> action) {
    String arguments = call.arguments();
    ArgumentBinding previous = ARGUMENT_BINDING.get();
    ARGUMENT_BINDING.remove();
    try {
      var nesting = org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments
          .restoreNesting(parse(arguments), parameters);
      JsonNode input = nesting.arguments();
      if (!nesting.relocations().isEmpty()) {
        ARGUMENT_BINDING.set(new ArgumentBinding(call.id(), arguments, input.deepCopy(), nesting.relocations()));
      }
      Issue issue = check(input, parameters, "");
      if (issue != null) {
        return tools.rejectArguments(toolName, arguments, issue);
      }
      return action.apply(mapper.treeToValue(input.get(field), type));
    } catch (IOException error) {
      return tools.rejectArguments(toolName, arguments, new Issue(
          StructuredCaptureArguments.jsonErrorCode(error), "/", null,
          StructuredCaptureArguments.syntaxMessage("Send one valid JSON argument object.", error)));
    } finally {
      if (previous == null) ARGUMENT_BINDING.remove();
      else ARGUMENT_BINDING.set(previous);
    }
  }

  private String executeRead(String arguments, JsonObjectSchema parameters) {
    try {
      Issue issue = check(parse(arguments), parameters, "");
      return issue == null ? tools.readRequirementDraft()
          : tools.rejectArguments("readRequirementDraft", arguments, issue);
    } catch (IOException error) {
      return tools.rejectArguments("readRequirementDraft", arguments, new Issue(
          StructuredCaptureArguments.jsonErrorCode(error), "/", null,
          StructuredCaptureArguments.syntaxMessage("Send an empty JSON object.", error)));
    }
  }

  private JsonNode parse(String arguments) throws IOException {
    JsonNode input = StructuredCaptureArguments.parseJson(mapper, arguments, "outer");
    if (input != null && input.isTextual()) {
      input = StructuredCaptureArguments.parseJson(mapper, input.textValue(), "decoded rootString argument /");
    }
    return input;
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
    @Inject RequirementCaptureToolProvider provider;

    @Override
    public ToolProvider get() {
      return provider::provideTools;
    }
  }
}
