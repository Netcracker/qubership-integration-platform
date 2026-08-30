package org.qubership.integration.platform.ai.plan.mapping.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;

/** Frozen mapper source/target envelope. Persist as {@code MAPPING_ENVELOPE} in Task 13. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingEnvelope(
    MessageSchema source,
    MessageSchema target,
    Map<String, String> idToPath,
    String digest,
    String mappingIntentId) {

  public MappingEnvelope {
    idToPath = idToPath == null ? Map.of() : Map.copyOf(idToPath);
  }

  public MappingEnvelope(
      MessageSchema source, MessageSchema target, Map<String, String> idToPath, String digest) {
    this(source, target, idToPath, digest, null);
  }

  public MappingEnvelope withSource(MessageSchema source) {
    return new MappingEnvelope(source, target, idToPath, digest, mappingIntentId);
  }

  public MappingEnvelope withMappingIntentId(String mappingIntentId) {
    return new MappingEnvelope(source, target, idToPath, digest, mappingIntentId);
  }
}
