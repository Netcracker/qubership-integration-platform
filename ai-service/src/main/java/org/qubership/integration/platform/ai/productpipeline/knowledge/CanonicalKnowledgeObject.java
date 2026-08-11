package org.qubership.integration.platform.ai.productpipeline.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lossless canonical knowledge object as returned by the sidecar package query APIs.
 *
 * <p>This copy preserves upstream keys with null values, including {@code metadata.anchor: null}.
 */
public record CanonicalKnowledgeObject(
    @JsonProperty("ir_version") String irVersion,
    String id,
    String type,
    String title,
    String summary,
    Map<String, Object> metadata,
    List<Relation> relations,
    Content content,
    String version,
    String status,
    Source source) {

  public CanonicalKnowledgeObject {
    requireText(irVersion, "irVersion");
    requireText(id, "id");
    requireText(type, "type");
    title = title == null ? "" : title;
    summary = summary == null ? "" : summary;
    metadata = immutableNullableMap(metadata);
    relations = relations == null ? List.of() : List.copyOf(relations);
    content = Objects.requireNonNull(content, "content");
    requireText(version, "version");
    requireText(status, "status");
    source = Objects.requireNonNull(source, "source");
  }

  public record Relation(
      @JsonProperty("from") String fromId,
      String kind,
      @JsonProperty("to") String toId,
      Map<String, Object> attributes) {
    public Relation {
      requireText(fromId, "fromId");
      requireText(kind, "kind");
      requireText(toId, "toId");
      attributes = immutableNullableMap(attributes);
    }
  }

  public record Content(
      String format, String body, String raw, List<Map<String, Object>> sections) {
    public Content {
      requireText(format, "format");
      sections =
          sections == null
              ? List.of()
              : sections.stream().map(CanonicalKnowledgeObject::immutableNullableMap).toList();
    }
  }

  public record Source(
      String format,
      String document,
      @JsonProperty("section_id") String sectionId,
      String hash,
      @JsonProperty("knowledge_version") String knowledgeVersion) {
    public Source {
      requireText(format, "format");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static Map<String, Object> immutableNullableMap(Map<String, Object> value) {
    return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }
}
