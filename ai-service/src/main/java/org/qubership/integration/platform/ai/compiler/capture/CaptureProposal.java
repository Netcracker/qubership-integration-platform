package org.qubership.integration.platform.ai.compiler.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.TreeMap;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;

/** Exact proposal equality preserves literals, types, array order, and absent fields. */
public final class CaptureProposal {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private CaptureProposal() {}

  public static String digest(Object value) {
    return CanonicalPayloadHash.sha256Hex(canonical(JSON.valueToTree(value)).toString());
  }

  public static String mutationDigest(String tool, JsonNode arguments) {
    JsonNode mutation = arguments.deepCopy();
    if (mutation instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
      if ("captureGraphPatch".equals(tool)) {
        if (object.path("patchId").isTextual() && !object.path("patchId").asText().isBlank()) {
          object.remove("patchId");
        }
        object.remove("rationale");
      } else if ("repairScriptBodies".equals(tool)) {
        if (object.path("patchId").isTextual() && !object.path("patchId").asText().isBlank()) {
          object.remove("patchId");
        }
        object.remove("rationale");
      }
    }
    return digest(mutation);
  }

  public static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var entries = new TreeMap<String, JsonNode>();
      node.properties().forEach(entry -> entries.put(entry.getKey(), canonical(entry.getValue())));
      var result = JSON.createObjectNode();
      entries.forEach(result::set);
      return result;
    }
    if (node.isArray()) {
      var result = JSON.createArrayNode();
      node.forEach(value -> result.add(canonical(value)));
      return result;
    }
    return node;
  }
}
