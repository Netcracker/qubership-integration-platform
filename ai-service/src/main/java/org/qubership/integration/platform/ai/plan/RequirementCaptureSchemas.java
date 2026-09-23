package org.qubership.integration.platform.ai.plan;

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
final class RequirementCaptureSchemas {

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

  private static JsonSchemaElement schema(Type type, String name) {
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() == List.class) {
      return JsonArraySchema.builder()
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
        required.add(component.getName());
      }
      return object.required(required).build();
    }
    if ("capabilityKey".equals(name)) {
      LinkedHashSet<String> keys = new LinkedHashSet<>(ChainElementFamilies.TRIGGERS);
      keys.addAll(ChainElementFamilies.SENDERS);
      keys.addAll(ChainElementFamilies.FILE_TRANSFER);
      keys.add("chain-call-2");
      return JsonEnumSchema.builder().enumValues(List.copyOf(keys)).build();
    }
    if ("directive".equals(name)) {
      return JsonEnumSchema.builder().enumValues("STAY", "CONTINUE").build();
    }
    if (clazz.isEnum()) {
      return JsonEnumSchema.builder().enumValues(
          java.util.Arrays.stream(clazz.getEnumConstants())
              .map(value -> ((Enum<?>) value).name()).toList()).build();
    }
    if (clazz == String.class) {
      return new JsonStringSchema();
    }
    if (clazz == Integer.class) {
      return new JsonIntegerSchema();
    }
    if (clazz == Boolean.class) {
      return new JsonBooleanSchema();
    }
    throw new IllegalArgumentException("Unsupported capture field type: " + type);
  }
}
