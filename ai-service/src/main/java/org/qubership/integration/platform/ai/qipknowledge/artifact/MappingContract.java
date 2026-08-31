package org.qubership.integration.platform.ai.qipknowledge.artifact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

  public static MappingContract fromHopPaths(Iterable<String> paths) {
    if (paths == null) {
      return unknown();
    }
    List<Field> fields = new ArrayList<>();
    for (String path : paths) {
      if (path != null && !path.isBlank()) {
        fields.add(new Field(path, "", false));
      }
    }
    return fields.isEmpty() ? unknown() : new MappingContract(fields, true);
  }

  /**
   * Captured briefs use {@code Subject}; JSON Schema contracts use {@code $.Subject}. Quoted
   * constants and comma-separated field lists stay unchanged so callers can split them.
   */
  public static String canonicalPath(String path) {
    if (path == null) {
      return "";
    }
    String trimmed = path.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed;
    }
    if (trimmed.indexOf(',') >= 0) {
      return trimmed;
    }
    if (trimmed.startsWith("$")) {
      return trimmed;
    }
    return "$." + trimmed;
  }

  /**
   * Splits a captured comma-separated field list, or returns the single canonical JSON Path.
   * Duplicate targets collapse to one coverage entry.
   */
  public static List<String> expandedCanonicalPaths(String path) {
    List<String> parts = commaSeparatedFieldNames(path);
    if (parts.isEmpty()) {
      String canonical = canonicalPath(path);
      return canonical.isEmpty() ? List.of() : List.of(canonical);
    }
    List<String> expanded = new ArrayList<>();
    for (String part : parts) {
      expanded.add(canonicalPath(part));
    }
    return List.copyOf(expanded);
  }

  public static List<String> uniqueCanonicalPaths(List<String> paths) {
    Set<String> unique = new LinkedHashSet<>();
    if (paths != null) {
      for (String path : paths) {
        unique.addAll(expandedCanonicalPaths(path));
      }
    }
    return List.copyOf(unique);
  }

  public static List<String> commaSeparatedFieldNames(String path) {
    if (path == null || path.indexOf(',') < 0) {
      return List.of();
    }
    String trimmed = path.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return List.of();
    }
    List<String> parts = new ArrayList<>();
    for (String part : trimmed.split(",")) {
      String field = part.trim();
      if (field.isEmpty() || field.indexOf(' ') >= 0) {
        return List.of();
      }
      parts.add(field);
    }
    return parts.size() < 2 ? List.of() : parts;
  }

  public List<String> hopBodyFieldsCoveredBy(List<String> approvedPaths) {
    List<String> approved = uniqueCanonicalPaths(approvedPaths);
    if (!known) {
      return approved;
    }
    List<String> schemaPaths = new ArrayList<>();
    for (Field field : fields) {
      schemaPaths.add(field.path());
    }
    List<String> schemaFields = uniqueCanonicalPaths(schemaPaths);
    Set<String> required = new LinkedHashSet<>();
    for (String approvedPath : approved) {
      for (String schemaField : schemaFields) {
        if (pathTouches(approvedPath, schemaField)) {
          required.add(schemaField);
        }
      }
    }
    return List.copyOf(required);
  }

  public static boolean pathTouches(String left, String right) {
    String a = canonicalPath(left);
    String b = canonicalPath(right);
    if (a.isEmpty() || b.isEmpty()) {
      return false;
    }
    return a.equals(b) || a.startsWith(b + ".") || b.startsWith(a + ".");
  }

  public Optional<Field> field(String path) {
    if (!known || path == null || path.isBlank()) {
      return Optional.empty();
    }
    String want = canonicalPath(path);
    if (want.indexOf(',') >= 0) {
      return Optional.empty();
    }
    return fields.stream()
        .filter(item -> want.equals(canonicalPath(item.path())))
        .findFirst();
  }

  public record Field(String path, String type, boolean required) {

    public Field {
      path = path == null ? "" : path.trim();
      type = type == null ? "" : type.trim();
    }
  }
}
