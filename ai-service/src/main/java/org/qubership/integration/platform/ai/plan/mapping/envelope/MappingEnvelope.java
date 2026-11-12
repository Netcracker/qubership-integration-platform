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
    String mappingIntentId,
    org.qubership.integration.platform.ai.plan.mapping.CopyMappingPlan verifiedCopyPlan) {

  public MappingEnvelope {
    idToPath = idToPath == null ? Map.of() : Map.copyOf(idToPath);
  }

  public MappingEnvelope(MessageSchema source, MessageSchema target, Map<String, String> idToPath,
      String digest, String mappingIntentId) {
    this(source, target, idToPath, digest, mappingIntentId, null);
  }

  public MappingEnvelope(
      MessageSchema source, MessageSchema target, Map<String, String> idToPath, String digest) {
    this(source, target, idToPath, digest, null);
  }

  public MappingEnvelope withVerifiedCopyPlan(
      org.qubership.integration.platform.ai.plan.mapping.CopyMappingPlan plan) {
    return new MappingEnvelope(source, target, idToPath, digest, mappingIntentId, plan);
  }

  public MappingEnvelope withSource(MessageSchema source) {
    return new MappingEnvelope(source, target, idToPath, digest, mappingIntentId, verifiedCopyPlan);
  }

  public MappingEnvelope withMappingIntentId(String mappingIntentId) {
    return new MappingEnvelope(source, target, idToPath, digest, mappingIntentId, verifiedCopyPlan);
  }
}
