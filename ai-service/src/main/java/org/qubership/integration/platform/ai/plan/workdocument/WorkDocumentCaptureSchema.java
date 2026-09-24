package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Provider schema for {@link WorkTaskCapture}. Server-owned names are not properties. */
public final class WorkDocumentCaptureSchema {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SERVER_OWNED =
      Set.of(
          "id",
          "documentId",
          "approval",
          "approvalReference",
          "progress",
          "schemaVersion",
          "binding",
          "catalogId",
          "protocol",
          "method",
          "path",
          "contentHash",
          "revision",
          "revisionId");

  private WorkDocumentCaptureSchema() {}

  public static JsonObjectSchema captureSchema() {
    return (JsonObjectSchema) schema(WorkTaskCapture.class);
  }

  public static WorkTaskCapture parse(String json) {
    try {
      JsonNode tree = JSON.readTree(json);
      rejectOwned(tree);
      return JSON.treeToValue(tree, WorkTaskCapture.class);
    } catch (WorkDocumentRejectedException rejected) {
      throw rejected;
    } catch (Exception failure) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_REFERENCE",
          "Capture JSON could not be read. Send the capture object for this task.");
    }
  }

  private static void rejectOwned(JsonNode node) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      Iterator<String> names = node.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (SERVER_OWNED.contains(name)) {
          throw new WorkDocumentRejectedException(
              "SERVER_OWNED_FIELD",
              "Capture property " + name + " is server-owned. Remove it and send an alias or existingId.");
        }
        rejectOwned(node.get(name));
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        rejectOwned(child);
      }
    }
  }

  private static JsonSchemaElement schema(Type type) {
    if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
      return JsonArraySchema.builder()
          .items(schema(parameterized.getActualTypeArguments()[0]))
          .build();
    }
    if (!(type instanceof Class<?> clazz)) {
      throw new IllegalArgumentException("Unsupported capture field type: " + type);
    }
    if (clazz == JsonNode.class) {
      return JsonAnyOfSchema.builder()
          .anyOf(
              new JsonNullSchema(),
              new JsonStringSchema(),
              new JsonNumberSchema(),
              new JsonBooleanSchema(),
              JsonArraySchema.builder().items(new JsonStringSchema()).build(),
              JsonObjectSchema.builder().additionalProperties(true).build())
          .build();
    }
    if (clazz.isRecord()) {
      JsonObjectSchema.Builder object = JsonObjectSchema.builder().additionalProperties(false);
      List<String> required = new ArrayList<>();
      for (RecordComponent component : clazz.getRecordComponents()) {
        object.addProperty(component.getName(), schema(component.getGenericType()));
        required.add(component.getName());
      }
      return object.required(required).build();
    }
    if (clazz.isEnum()) {
      return JsonEnumSchema.builder()
          .enumValues(
              java.util.Arrays.stream(clazz.getEnumConstants())
                  .map(value -> ((Enum<?>) value).name())
                  .toList())
          .build();
    }
    if (clazz == String.class) {
      return new JsonStringSchema();
    }
    if (clazz == Integer.class || clazz == int.class || clazz == Long.class || clazz == long.class) {
      return new JsonIntegerSchema();
    }
    if (clazz == Boolean.class || clazz == boolean.class) {
      return new JsonBooleanSchema();
    }
    throw new IllegalArgumentException("Unsupported capture field type: " + type);
  }
}
