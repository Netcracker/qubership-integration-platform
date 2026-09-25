package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/** Identifies a value without inferring its origin from a field name. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MappingSource.Message.class, name = "MESSAGE"),
    @JsonSubTypes.Type(value = MappingSource.Context.class, name = "CONTEXT"),
    @JsonSubTypes.Type(value = MappingSource.Constant.class, name = "CONSTANT"),
    @JsonSubTypes.Type(value = MappingSource.Outcome.class, name = "OUTCOME")
})
public sealed interface MappingSource {
  record Message(String interactionId, MappingPort port, String path) implements MappingSource {
    public Message {
      path = MappingContract.canonicalPath(path);
    }
  }
  record Context(String contextId) implements MappingSource {}
  record Constant(JsonNode value) implements MappingSource {}
  record Outcome(String interactionId, OutcomeField field) implements MappingSource {}
  enum OutcomeField { STATUS, RAW_BODY, PARSED_BODY, ERROR_TEXT }
}
