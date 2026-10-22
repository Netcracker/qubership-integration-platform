package org.qubership.integration.platform.ai.productpipeline.recovery;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.service.output.JsonSchemas;
import java.util.ArrayList;
import java.util.Comparator;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Projects the native reference domains into each diagnosis response schema. */
public final class RecoveryDecisionSchemas {
  private RecoveryDecisionSchemas() {}

  public static ResponseFormat responseFormat(RecoveryContext context) {
    var original = JsonSchemas.jsonSchemaFrom(RecoveryDecision.class).orElseThrow();
    var root = (JsonObjectSchema) original.rootElement();
    var narrowed = root.toBuilder()
        .addProperty("faultArtifactRef", faultReferences(context))
        .addProperty("evidenceRefs", evidenceReferences(context))
        .build();
    return ResponseFormat.builder().type(ResponseFormatType.JSON)
        .jsonSchema(JsonSchema.builder().name(original.name()).rootElement(narrowed).build()).build();
  }

  private static JsonSchemaElement faultReferences(RecoveryContext context) {
    var alternatives = new ArrayList<JsonSchemaElement>();
    RecoveryDecisionValidator.knownArtifactRefs(context).stream()
        .sorted(Comparator.comparing((Reference ref) -> ref.kind().name())
            .thenComparing(Reference::artifactId).thenComparing(Reference::contentHash))
        .map(RecoveryDecisionSchemas::reference)
        .forEach(alternatives::add);
    if (alternatives.isEmpty()) {
      return new JsonNullSchema();
    }
    alternatives.add(new JsonNullSchema());
    return JsonAnyOfSchema.builder().anyOf(alternatives).build();
  }

  private static JsonObjectSchema reference(Reference ref) {
    return JsonObjectSchema.builder()
        .addProperty("kind", JsonEnumSchema.builder().enumValues(ref.kind().name()).build())
        .addProperty("artifactId", JsonEnumSchema.builder().enumValues(ref.artifactId()).build())
        .addProperty("contentHash", JsonEnumSchema.builder().enumValues(ref.contentHash()).build())
        .required("kind", "artifactId", "contentHash")
        .additionalProperties(false)
        .build();
  }

  private static JsonSchemaElement evidenceReferences(RecoveryContext context) {
    var ids = RecoveryDecisionValidator.evidenceReferenceIds(context).stream().sorted().toList();
    if (ids.isEmpty()) {
      return JsonRawSchema.from("{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":0}");
    }
    return JsonArraySchema.builder().items(JsonEnumSchema.builder().enumValues(ids).build()).build();
  }
}
