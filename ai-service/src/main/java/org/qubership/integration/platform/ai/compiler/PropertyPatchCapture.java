package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;

/** LLM-facing property patch with a structured JSON value (not JSON-in-string). */
@JsonIgnoreProperties(ignoreUnknown = true)
record PropertyPatchCapture(
    @Description("ADD, UPDATE, or REMOVE") GraphPatchOperation operation,
    @Description("Existing plan node id") String targetNodeId,
    @Description("Catalog property key from describeElementPatchSchema") String key,
    @Description("Unused placeholder when mapEntries or arrayValue is set; send {}") JsonNode value,
    @Description("String, number, or boolean; the server uses this when value is {}")
        String scalarValue,
    @Description("String list such as headerModificationToRemove; send value={}")
        List<String> arrayValue,
    @Description("Add/keep headers as name=value strings, e.g. [\"X-Trace=1\", \"X-Keep=\"]")
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
