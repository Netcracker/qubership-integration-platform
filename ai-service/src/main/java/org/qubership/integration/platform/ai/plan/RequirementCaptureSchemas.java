package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

/** Builds the provider schema from the capture records and their nullability markers. */
public final class RequirementCaptureSchemas {
  static final java.util.Set<String> COMPILER_ELEMENT_TYPES =
      new org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository()
          .require(org.qubership.integration.platform.ai.compiler.contract.CompilerContract.V1).elements().keySet().stream()
          .filter(type -> !ChainElementFamilies.isProjectedRegionShell(type))
          .filter(type -> !(ChainElementFamilies.isTrigger(type) || ChainElementFamilies.isSender(type))
              || ChainElementFamilies.bindingMode(type) != ChainElementFamilies.BindingMode.UNSUPPORTED_IN_CREATE)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private RequirementCaptureSchemas() {}

  static JsonObjectSchema parameters(String key, Class<?> inputType) {
    return JsonObjectSchema.builder()
        .addProperty(key, schema(inputType, key))
        .required(key)
        .additionalProperties(false)
        .build();
  }

  static JsonObjectSchema emptyParameters() {
    return JsonObjectSchema.builder().required(List.of()).additionalProperties(false).build();
  }


  /** Adds declared required fields to Quarkus schemas without regenerating their field types. */
  public static ToolSpecification specification(ToolSpecification original) {
    Class<?> tool = switch (original.name()) {
      case "captureChainSemanticRevision" ->
          org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureTool.class;
      case "captureDesignPlan" ->
          org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanCaptureTool.class;
      case "captureGraphPatch" -> org.qubership.integration.platform.ai.compiler.CompilerGraphPatchTool.class;
      case "captureNamingManifest" -> org.qubership.integration.platform.ai.compiler.NamingManifestCaptureTool.class;
      case "captureHttpTriggers" -> org.qubership.integration.platform.ai.compiler.HttpTriggerCaptureTool.class;
      case "captureConfiguredTriggerSet" ->
          org.qubership.integration.platform.ai.compiler.ConfiguredTriggerSetCaptureTool.class;
      case "repairScriptBodies" -> org.qubership.integration.platform.ai.compiler.ScriptBodyRepairTool.class;
      default -> null;
    };
    if (tool == null) {
      return original;
    }
    var method = java.util.Arrays.stream(tool.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(original.name()))
        .findFirst().orElseThrow();
    var root = original.parameters();
    String parameter = root.properties().keySet().iterator().next();
    var capture = declaredFields(root.properties().get(parameter), method.getGenericParameterTypes()[0]);
    return original.toBuilder().parameters(root.toBuilder().addProperty(parameter, capture)
        .required(parameter).additionalProperties(false).build()).build();
  }

  private static JsonSchemaElement declaredFields(JsonSchemaElement schema, Type type) {
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() == List.class && schema instanceof JsonArraySchema array) {
      return JsonArraySchema.builder().description(array.description())
          .items(declaredFields(array.items(), parameterized.getActualTypeArguments()[0]))
          .build();
    }
    if (type == JsonNode.class) {
      return JsonRawSchema.from("{}");
    }
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() == java.util.Map.class) {
      return JsonObjectSchema.builder().additionalProperties(true).build();
    }
    if (!(type instanceof Class<?> record) || !record.isRecord()
        || !(schema instanceof JsonObjectSchema object)) {
      return schema;
    }
    var builder = object.toBuilder().additionalProperties(false);
    List<String> required = new ArrayList<>();
    for (RecordComponent component : record.getRecordComponents()) {
      JsonProperty declaration;
      try {
        declaration = record.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
      } catch (NoSuchFieldException error) {
        throw new IllegalStateException(error);
      }
      JsonSchemaElement child = declaredFields(
          object.properties().get(component.getName()), component.getGenericType());
      if (declaration != null && declaration.required()) {
        required.add(component.getName());
      } else if (!component.getType().isPrimitive()
          && !(child instanceof JsonRawSchema) && !(child instanceof JsonAnyOfSchema)) {
        child = JsonAnyOfSchema.builder().anyOf(child, new JsonNullSchema()).build();
      }
      builder.addProperty(component.getName(), child);
    }
    return builder.required(required).build();
  }

  private static JsonSchemaElement schema(Type type, String name) {
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() == List.class) {
      return JsonArraySchema.builder()
          .description("exactValues".equals(name)
              ? "Complete author-supplied runtime keys or scalar values that this owner's artifact must contain exactly,"
                  + " such as a complete response body, error code, JSON key, or marker. Preserve all characters, including"
                  + " whitespace or '=' in a complete value such as 'a=b'. Script values are checked as complete executable"
                  + " constants. Preserve an explicitly required literal component or prefix byte-for-byte even when"
                  + " the output adds a dynamic suffix; compose separators and runtime expressions outside that constant."
                  + " For an object property assignment or binding, list the exact key and any actual fixed scalar separately."
                  + " The whole key=value clause is a required constant only when the author explicitly requests that"
                  + " complete string as a literal."
                  + " Keep whole binding, computation, and composition descriptions in BEHAVIOR text or a FIELD_MAPPING"
                  + " on a real business transition, not as standalone constants. Source occurrence alone does not establish"
                  + " a runtime constant. Names belong in requestedName;"
                  + " do not list type or language display names."
              : null)
          .items(schema(parameterized.getActualTypeArguments()[0], name + "[]"))
          .build();
    }
    if (!(type instanceof Class<?> clazz)) {
      throw new IllegalArgumentException("Unsupported capture field type: " + type);
    }
    if (clazz.isRecord()) {
      JsonObjectSchema.Builder object = JsonObjectSchema.builder().additionalProperties(false);
      List<String> required = new ArrayList<>();
      for (RecordComponent component : clazz.getRecordComponents()) {
        JsonSchemaElement field = schema(component.getGenericType(), component.getName());
        NullableCaptureValue nullable = component.getAnnotation(NullableCaptureValue.class);
        if (nullable != null) {
          field = JsonAnyOfSchema.builder().anyOf(field, new JsonNullSchema())
              .description(nullable.description().isBlank() ? null : nullable.description())
              .build();
        }
        object.addProperty(component.getName(), field);
        try {
          JsonProperty declaration = clazz.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
          if (declaration == null || declaration.required()) required.add(component.getName());
        } catch (NoSuchFieldException error) {
          throw new IllegalStateException(error);
        }
      }
      return object.required(required).build();
    }
    if ("elementType".equals(name)) {
      var keys = new java.util.TreeSet<>(COMPILER_ELEMENT_TYPES);
      keys.add("chain");
      return JsonEnumSchema.builder().enumValues(List.copyOf(keys)).build();
    }
    if ("capabilityKey".equals(name)) {
      LinkedHashSet<String> keys = new LinkedHashSet<>(ChainElementFamilies.TRIGGERS);
      keys.addAll(ChainElementFamilies.SENDERS);
      keys.addAll(ChainElementFamilies.FILE_TRANSFER);
      keys.add("chain-call-2");
      keys.retainAll(COMPILER_ELEMENT_TYPES);
      return JsonEnumSchema.builder().enumValues(List.copyOf(keys)).build();
    }
    if ("directive".equals(name)) {
      return JsonEnumSchema.builder().enumValues("STAY", "CONTINUE", "DISCUSS").build();
    }
    if (clazz.isEnum()) {
      return JsonEnumSchema.builder().enumValues(
          java.util.Arrays.stream(clazz.getEnumConstants())
              .map(value -> ((Enum<?>) value).name()).toList()).build();
    }
    if (clazz == String.class) {
      if ("sourceFactId".equals(name)) {
        return JsonStringSchema.builder().description(
            "Stable local fact ID matching " + RequirementCaptureEditor.localIdPattern()
                + " as a complete value. Field paths belong in their declared fields.")
            .build();
      }
      if ("targetPath".equals(name)) {
        return JsonStringSchema.builder().description(
            "Exactly one target field path. Capture a separate FIELD_MAPPING fact for each output field.")
            .build();
      }
      return new JsonStringSchema();
    }
    if (clazz == Integer.class || clazz == int.class) {
      return new JsonIntegerSchema();
    }
    if (clazz == Boolean.class) {
      return new JsonBooleanSchema();
    }
    throw new IllegalArgumentException("Unsupported capture field type: " + type);
  }
}
