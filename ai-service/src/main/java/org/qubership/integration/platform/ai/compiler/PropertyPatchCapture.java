package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;

/** LLM-facing property patch with schema-coerced values. */
@JsonIgnoreProperties(ignoreUnknown = true)
record PropertyPatchCapture(
    @Description("ADD, UPDATE, or REMOVE")
        @JsonProperty(required = true) GraphPatchOperation operation,
    @Description("Existing plan node id")
        @JsonProperty(required = true) String targetNodeId,
    @Description("Catalog property key from describeElementPatchSchema")
        @JsonProperty(required = true) String key,
    @Description("Unused placeholder when mapEntries or arrayValue is set; send {}") JsonNode value,
    @Description("String, number, boolean, or serialized JSON object/array; schema-coerced when value is {}")
        String scalarValue,
    @Description("String list such as headerModificationToRemove; send value={}")
        List<String> arrayValue,
    @Description("Flat string object as name=value pairs, e.g. [\"type=m2m\"] or [\"X-Trace=1\"]")
        List<String> mapEntries,
    @Description("String map such as headerModificationToAdd; send value={}")
        Map<String, String> mapValue) {

  PropertyPatchCapture(
      GraphPatchOperation operation, String targetNodeId, String key, JsonNode value) {
    this(operation, targetNodeId, key, value, null, null, null, null);
  }

  PropertyPatchCapture(
      GraphPatchOperation operation,
      String targetNodeId,
      String key,
      JsonNode value,
      String scalarValue) {
    this(operation, targetNodeId, key, value, scalarValue, null, null, null);
  }

  PropertyPatchCapture(
      GraphPatchOperation operation,
      String targetNodeId,
      String key,
      JsonNode value,
      String scalarValue,
      List<String> arrayValue) {
    this(operation, targetNodeId, key, value, scalarValue, arrayValue, null, null);
  }

  PropertyPatchCapture(
      GraphPatchOperation operation,
      String targetNodeId,
      String key,
      JsonNode value,
      String scalarValue,
      List<String> arrayValue,
      Map<String, String> mapValue) {
    this(operation, targetNodeId, key, value, scalarValue, arrayValue, null, mapValue);
  }
}
