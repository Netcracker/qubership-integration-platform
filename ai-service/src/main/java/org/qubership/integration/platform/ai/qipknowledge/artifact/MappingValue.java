package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Typed operands shared by capture, validation, and script generation. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "operation")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MappingValue.Copy.class, name = "COPY"),
    @JsonSubTypes.Type(value = MappingValue.Fallback.class, name = "FALLBACK"),
    @JsonSubTypes.Type(value = MappingValue.Lookup.class, name = "LOOKUP"),
    @JsonSubTypes.Type(value = MappingValue.Interpolate.class, name = "INTERPOLATE"),
    @JsonSubTypes.Type(value = MappingValue.JsonSerialize.class, name = "JSON_SERIALIZE"),
    @JsonSubTypes.Type(value = MappingValue.CurrentDate.class, name = "CURRENT_DATE"),
    @JsonSubTypes.Type(value = MappingValue.Custom.class, name = "CUSTOM")
})
public sealed interface MappingValue {
  record Copy(MappingSource source) implements MappingValue {}
  record Fallback(List<MappingValue> values, MissingValue missing) implements MappingValue {
    public Fallback { values = values == null ? List.of() : List.copyOf(values); }
  }
  record Lookup(MappingValue value, List<LookupEntry> entries, MappingValue defaultValue,
      CaseNormalization normalization) implements MappingValue {
    public Lookup { entries = entries == null ? List.of() : List.copyOf(entries); }
  }
  record LookupEntry(JsonNode key, MappingValue value) {}
  record Interpolate(String template, List<NamedValue> values,
      MissingInterpolation missing) implements MappingValue {
    public Interpolate { values = values == null ? List.of() : List.copyOf(values); }
  }
  record JsonSerialize(List<NamedValue> fields) implements MappingValue {
    public JsonSerialize { fields = fields == null ? List.of() : List.copyOf(fields); }
  }
  record NamedValue(String name, MappingValue value) {}
  record CurrentDate(String zoneId, String format) implements MappingValue {}
  record Custom(String description, List<NamedValue> inputs, List<Example> examples) implements MappingValue {
    public Custom {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
      examples = examples == null ? List.of() : List.copyOf(examples);
    }
    public Custom(String description, List<Example> examples) {
      this(description, List.of(), examples);
    }
  }
  record Example(JsonNode input, JsonNode context, JsonNode outcome, JsonNode expected) {}
  enum MissingValue { NULL, NULL_OR_BLANK }
  enum MissingInterpolation { EMPTY, ERROR }
  enum CaseNormalization { NONE, LOWER, UPPER }

  static String describe(MappingValue value) {
    return switch (value) {
      case Copy copy -> switch (copy.source()) {
        case MappingSource.Message message -> message.path();
        case MappingSource.Context ignored -> "retained context value";
        case MappingSource.Constant constant -> "constant " + constant.value();
        case MappingSource.Outcome outcome -> "service " + outcome.field().name()
            .toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
      };
      case Fallback fallback -> fallback.values().stream().map(MappingValue::describe)
          .collect(java.util.stream.Collectors.joining("; otherwise "));
      case Lookup lookup -> describe(lookup.value()) + ": " + lookup.entries().stream()
          .map(entry -> entry.key() + " → " + describe(entry.value()))
          .collect(java.util.stream.Collectors.joining(", ")) + "; default " + describe(lookup.defaultValue());
      case Interpolate interpolation -> "text " + interpolation.template();
      case JsonSerialize json -> "JSON with " + json.fields().stream().map(NamedValue::name)
          .collect(java.util.stream.Collectors.joining(", "));
      case CurrentDate date -> "today in " + date.zoneId() + " (" + date.format() + ")";
      case Custom custom -> custom.description();
    };
  }

  @JsonIgnore
  default List<MappingSource> sources() {
    List<MappingSource> result = new ArrayList<>();
    collectSources(this, result);
    return List.copyOf(result);
  }

  private static void collectSources(MappingValue value, List<MappingSource> result) {
    if (value == null) return;
    switch (value) {
      case Copy copy -> { if (copy.source() != null) result.add(copy.source()); }
      case Fallback fallback -> fallback.values().forEach(item -> collectSources(item, result));
      case Lookup lookup -> {
        collectSources(lookup.value(), result);
        lookup.entries().forEach(entry -> collectSources(entry.value(), result));
        collectSources(lookup.defaultValue(), result);
      }
      case Interpolate interpolation ->
          interpolation.values().forEach(item -> collectSources(item.value(), result));
      case JsonSerialize json -> json.fields().forEach(item -> collectSources(item.value(), result));
      case CurrentDate ignored -> { }
      case Custom custom -> custom.inputs().forEach(item -> collectSources(item.value(), result));
    }
  }

  @JsonIgnore
  default boolean standard() {
    return switch (this) {
      case Custom ignored -> false;
      case Copy ignored -> true;
      case CurrentDate ignored -> true;
      case Fallback fallback -> fallback.values().stream().allMatch(MappingValue::standard);
      case Lookup lookup -> lookup.value() != null && lookup.value().standard()
          && lookup.entries().stream().allMatch(entry -> entry.value() != null && entry.value().standard())
          && lookup.defaultValue() != null && lookup.defaultValue().standard();
      case Interpolate interpolation -> interpolation.values().stream()
          .allMatch(item -> item.value() != null && item.value().standard());
      case JsonSerialize json -> json.fields().stream()
          .allMatch(item -> item.value() != null && item.value().standard());
    };
  }
}
