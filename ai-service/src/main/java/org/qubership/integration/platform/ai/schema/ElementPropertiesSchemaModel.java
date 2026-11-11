package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merged view of the catalog {@code properties} object for an element type (inner {@code
 * properties.properties} block in QIP YAML schemas).
 */
public record ElementPropertiesSchemaModel(
    String elementType,
    String elementDocumentUri,
    Map<String, JsonNode> propertyDefs,
    Set<String> unconditionalRequired,
    List<JsonNode> rootOneOfGroups,
    List<SchemaIfThenBranch> conditionalBranches,
    List<String> warnings) {

  public static ElementPropertiesSchemaModel empty(
      String elementType, String elementDocumentUri, String message) {
    List<String> w = new ArrayList<>();
    w.add(message);
    return new ElementPropertiesSchemaModel(
        elementType,
        elementDocumentUri,
        Map.of(),
        Set.of(),
        List.of(),
        List.of(),
        List.copyOf(w));
  }

  public Set<String> requiredKeysFor(Map<String, String> properties) {
    Set<String> keys = new LinkedHashSet<>(unconditionalRequired);
    for (SchemaIfThenBranch branch : conditionalBranches) {
      applyBranch(keys, properties, branch);
    }
    return Set.copyOf(keys);
  }

  private static void applyBranch(
      Set<String> keys, Map<String, String> properties, SchemaIfThenBranch branch) {
    String actual = properties.get(branch.discriminatorKey());
    if (actual == null) {
      return;
    }
    if (branch.discriminatorValue().equals(actual)) {
      keys.addAll(branch.thenRequired());
      for (SchemaIfThenBranch nested : branch.nestedThen()) {
        applyBranch(keys, properties, nested);
      }
    } else {
      keys.addAll(branch.elseRequired());
      for (SchemaIfThenBranch nested : branch.nestedElse()) {
        applyBranch(keys, properties, nested);
      }
    }
  }
}
