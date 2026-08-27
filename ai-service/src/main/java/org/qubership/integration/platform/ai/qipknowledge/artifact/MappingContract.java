package org.qubership.integration.platform.ai.qipknowledge.artifact;

import java.util.List;
import java.util.Optional;

/**
 * Known payload fields for one mapping port. Unknown contracts do not create a mapping obligation.
 */
public record MappingContract(List<Field> fields, boolean known) {

  public MappingContract {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public static MappingContract unknown() {
    return new MappingContract(List.of(), false);
  }

  public static MappingContract of(Field... fields) {
    return new MappingContract(List.of(fields), true);
  }

  public Optional<Field> field(String path) {
    if (!known || path == null || path.isBlank()) {
      return Optional.empty();
    }
    String normalized = path.trim();
    return fields.stream().filter(field -> normalized.equals(field.path())).findFirst();
  }

  public record Field(String path, String type, boolean required) {

    public Field {
      path = path == null ? "" : path.trim();
      type = type == null ? "" : type.trim();
    }
  }
}
