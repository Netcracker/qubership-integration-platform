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

  /** Model-facing schema for one task kind. The universal capture is not a live response schema. */
  public static JsonObjectSchema responseSchema(WorkTaskKind kind, CaptureChoices choices) {
    CaptureChoices allowed = choices == null ? CaptureChoices.none() : choices;
    return switch (kind) {
      case LOGICAL_DESIGN -> (JsonObjectSchema) schema(LogicalDesignCapture.class);
      case SELECT_OPERATION -> operationSelectionSchema();
      case DEFINE_TRANSFERS -> outlineSchema(allowed);
      case DESCRIBE_CONTEXT -> retainedSchema(allowed);
      case MAP_TRANSFER -> mappingSchema(allowed, false);
      case REPAIR_RULE -> mappingSchema(allowed, true);
      case UNSPECIFIED ->
          throw new IllegalArgumentException(
              "A model request needs a task kind. Choose the kind before building the schema.");
    };
  }

  public static JsonNode readObject(String json, JsonObjectSchema schema) {
    if (json == null || json.isBlank()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "Model output is empty. Send one capture object for this task.");
    }
    JsonNode tree;
    try {
      tree = JSON.readTree(json);
    } catch (Exception failure) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "Model output is not a capture object. The task was not completed.");
    }
    if (!tree.isObject()) {
      throw new WorkDocumentRejectedException(
          "MALFORMED_CAPTURE", "Model output is not a capture object. The task was not completed.");
    }
    rejectExtras(tree, schema);
    return tree;
  }

  /** Fills the list fields the internal editor capture requires. */
  public static String withUniversalLists(JsonNode tree) {
    com.fasterxml.jackson.databind.node.ObjectNode body = tree.deepCopy();
    for (String name :
        List.of(
            "requirements",
            "steps",
            "connections",
            "sequenceGroups",
            "conditionGroups",
            "splitGroups",
            "loopGroups",
            "retryGroups",
            "errorScopeGroups",
            "transfers",
            "rules",
            "retainedValues",
            "deletes",
            "clarificationEvidenceIds",
            "defectEvidenceIds")) {
      if (!body.has(name)) {
        body.set(name, JSON.createArrayNode());
      }
    }
    for (String name :
        List.of("question", "unresolvedChoice", "defectRecordRef", "contradiction", "issueCategory")) {
      if (!body.has(name)) {
        body.put(name, "");
      }
    }
    return body.toString();
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

  private static JsonObjectSchema operationSelectionSchema() {
    return object(
        List.of("outcome", "candidateId"),
        "outcome",
        outcomeEnum(),
        "candidateId",
        new JsonStringSchema(),
        "question",
        new JsonStringSchema(),
        "choiceKind",
        choiceKindEnum(),
        "evidenceRefs",
        JsonArraySchema.builder().items(new JsonStringSchema()).build(),
        "defectRecordRef",
        new JsonStringSchema(),
        "contradiction",
        new JsonStringSchema(),
        "issueCategory",
        new JsonStringSchema());
  }

  private static JsonObjectSchema outlineSchema(CaptureChoices choices) {
    JsonObjectSchema transfer =
        object(
            List.of(
                "alias",
                "sourceStepId",
                "sourcePort",
                "targetPort",
                "outcome",
                "requirementIds",
                "requiredRetainedIds",
                "decision"),
            "alias",
            new JsonStringSchema(),
            "existingId",
            new JsonStringSchema(),
            "sourceStepId",
            stringEnum(choices.stepIds()),
            "sourcePort",
            stringEnum(choices.sourcePorts()),
            "targetPort",
            stringEnum(choices.targetPorts()),
            "outcome",
            enumOf("UNSPECIFIED", "SUCCESS", "FAILURE"),
            "requirementIds",
            JsonArraySchema.builder().items(new JsonStringSchema()).build(),
            "requiredRetainedIds",
            JsonArraySchema.builder().items(new JsonStringSchema()).build(),
            "decision",
            enumOf("", "NO_MAPPING"));
    JsonObjectSchema retained =
        object(
            List.of("alias", "producerStepId", "intendedUse", "evidenceRefs"),
            "alias",
            new JsonStringSchema(),
            "producerStepId",
            stringEnum(choices.stepIds()),
            "intendedUse",
            new JsonStringSchema(),
            "evidenceRefs",
            JsonArraySchema.builder().items(new JsonStringSchema()).build());
    JsonObjectSchema coverage =
        object(
            List.of("requirementId", "passageId", "disposition"),
            "requirementId",
            new JsonStringSchema(),
            "passageId",
            new JsonStringSchema(),
            "disposition",
            enumOf("ASSIGNED", "NO_MAPPING", "QUESTION"));
    return object(
        List.of("outcome"),
        "outcome",
        outcomeEnum(),
        "transfers",
        JsonArraySchema.builder().items(transfer).build(),
        "retainedPlaceholders",
        JsonArraySchema.builder().items(retained).build(),
        "coverage",
        JsonArraySchema.builder().items(coverage).build(),
        "question",
        questionSchema(CaptureChoices.none()),
        "defect",
        defectSchema(CaptureChoices.none()));
  }

  private static JsonObjectSchema retainedSchema(CaptureChoices choices) {
    JsonObjectSchema value =
        object(
            List.of("retainedId", "fieldPath", "evidenceRefs"),
            "retainedId",
            stringEnum(choices.retainedIds()),
            "fieldPath",
            new JsonStringSchema(),
            "evidenceRefs",
            JsonArraySchema.builder().items(stringEnum(choices.evidenceRefs())).build());
    return object(
        List.of("outcome"),
        "outcome",
        outcomeEnum(),
        "values",
        JsonArraySchema.builder().items(value).build(),
        "question",
        questionSchema(choices),
        "defect",
        defectSchema(choices));
  }

  private static JsonObjectSchema mappingSchema(CaptureChoices choices, boolean repair) {
    JsonSchemaElement identity =
        repair ? stringEnum(choices.ruleIds()) : new JsonStringSchema();
    String identityName = repair ? "existingId" : "alias";
    JsonObjectSchema source =
        object(
            List.of("sourceRef", "fieldPath"),
            "sourceRef",
            stringEnum(choices.sourceRefs()),
            "fieldPath",
            new JsonStringSchema());
    JsonObjectSchema constant =
        object(List.of("name", "value"), "name", new JsonStringSchema(), "value", anyJson());
    JsonObjectSchema relationship =
        object(
            List.of("sourceField", "targetField", "evidenceRefs"),
            "sourceField",
            new JsonStringSchema(),
            "targetField",
            new JsonStringSchema(),
            "evidenceRefs",
            JsonArraySchema.builder().items(stringEnum(choices.evidenceRefs())).build());
    JsonObjectSchema rule =
        object(
            List.of(identityName, "targetPath", "sources", "constants", "behavior", "evidenceRefs"),
            identityName,
            identity,
            "targetPath",
            new JsonStringSchema(),
            "sources",
            JsonArraySchema.builder().items(source).build(),
            "constants",
            JsonArraySchema.builder().items(constant).build(),
            "behavior",
            new JsonStringSchema(),
            "evidenceRefs",
            JsonArraySchema.builder().items(stringEnum(choices.evidenceRefs())).build(),
            "relationship",
            relationship);
    return object(
        List.of("outcome"),
        "outcome",
        outcomeEnum(),
        "rules",
        JsonArraySchema.builder().items(rule).build(),
        "decision",
        enumOf("", "NO_MAPPING"),
        "evidenceRefs",
        JsonArraySchema.builder().items(stringEnum(choices.evidenceRefs())).build(),
        "question",
        questionSchema(choices),
        "defect",
        defectSchema(choices));
  }

  private static JsonObjectSchema questionSchema(CaptureChoices choices) {
    return object(
        List.of(
            "text",
            "choiceKind",
            "sourceStepId",
            "sourcePort",
            "sourceField",
            "sourceRetainedId",
            "targetStepId",
            "targetPort",
            "targetField",
            "targetRetainedId",
            "evidenceRefs"),
        "text",
        new JsonStringSchema(),
        "choiceKind",
        choiceKindEnum(),
        "sourceStepId",
        new JsonStringSchema(),
        "sourcePort",
        new JsonStringSchema(),
        "sourceField",
        new JsonStringSchema(),
        "sourceRetainedId",
        new JsonStringSchema(),
        "targetStepId",
        new JsonStringSchema(),
        "targetPort",
        new JsonStringSchema(),
        "targetField",
        new JsonStringSchema(),
        "targetRetainedId",
        new JsonStringSchema(),
        "evidenceRefs",
        JsonArraySchema.builder().items(stringEnum(choices.evidenceRefs())).build());
  }

  private static JsonObjectSchema defectSchema(CaptureChoices choices) {
    return object(
        List.of("recordRef", "category", "contradiction", "evidenceRefs"),
        "recordRef",
        new JsonStringSchema(),
        "category",
        new JsonStringSchema(),
        "contradiction",
        new JsonStringSchema(),
        "evidenceRefs",
        JsonArraySchema.builder().items(stringEnum(choices.evidenceRefs())).build());
  }

  private static JsonObjectSchema object(List<String> required, Object... namesAndSchemas) {
    JsonObjectSchema.Builder builder = JsonObjectSchema.builder().additionalProperties(false);
    for (int index = 0; index < namesAndSchemas.length; index += 2) {
      builder.addProperty((String) namesAndSchemas[index], (JsonSchemaElement) namesAndSchemas[index + 1]);
    }
    return builder.required(required).build();
  }

  private static JsonEnumSchema outcomeEnum() {
    return enumOf("PREPARED", "NEEDS_CLARIFICATION", "INPUT_DEFECT");
  }

  private static JsonEnumSchema choiceKindEnum() {
    return enumOf("UNSPECIFIED", "FIELD_RELATIONSHIP");
  }

  private static JsonEnumSchema enumOf(String... values) {
    return JsonEnumSchema.builder().enumValues(List.of(values)).build();
  }

  private static JsonSchemaElement stringEnum(List<String> values) {
    if (values == null || values.isEmpty()) {
      return new JsonStringSchema();
    }
    return JsonEnumSchema.builder().enumValues(values).build();
  }

  private static JsonSchemaElement anyJson() {
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

  private static void rejectExtras(JsonNode node, JsonSchemaElement schema) {
    if (node == null || node.isNull() || schema == null || schema instanceof JsonAnyOfSchema) {
      return;
    }
    if (schema instanceof JsonObjectSchema object && node.isObject()) {
      Iterator<String> names = node.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        JsonSchemaElement child = object.properties().get(name);
        if (child == null) {
          throw new WorkDocumentRejectedException(
              "EXTRA_PROPERTY",
              "Capture property " + name + " is not in this task schema. Remove it.");
        }
        rejectExtras(node.get(name), child);
      }
      return;
    }
    if (schema instanceof JsonArraySchema array && node.isArray()) {
      for (JsonNode child : node) {
        rejectExtras(child, array.items());
      }
      return;
    }
    if (schema instanceof JsonEnumSchema enumeration && node.isTextual()) {
      if (enumeration.enumValues() != null && !enumeration.enumValues().contains(node.asText())) {
        throw new WorkDocumentRejectedException(
            "MALFORMED_REFERENCE",
            "Value " + node.asText() + " is not one of the allowed choices. Use a listed value.");
      }
    }
  }
}
