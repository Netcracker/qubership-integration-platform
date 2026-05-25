package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
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
    List<String> warnings) {

  public static ElementPropertiesSchemaModel empty(
      String elementType, String elementDocumentUri, String message) {
    List<String> w = new ArrayList<>();
    w.add(message);
    return new ElementPropertiesSchemaModel(
        elementType, elementDocumentUri, Map.of(), Set.of(), List.of(), List.copyOf(w));
  }
}
