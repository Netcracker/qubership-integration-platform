package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureProjection;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;

public final class LocalMappingTarget {
  private LocalMappingTarget() {}

  public static Optional<RequirementElementIntent> resolve(
      List<RequirementFact> facts, RequirementFlow flow, String targetRef) {
    if (facts == null || flow == null || targetRef == null || targetRef.isBlank()) return Optional.empty();
    List<RequirementFact> candidates = facts.stream().filter(Objects::nonNull)
        .filter(fact -> fact.elementIntent() != null && targetRef.equals(fact.elementIntent().elementRef()))
        .toList();
    if (candidates.size() != 1) return Optional.empty();
    RequirementFact fact = candidates.getFirst();
    if (fact.polarity() != RequirementFactPolarity.POSITIVE || fact.sourceFactId().isBlank()
        || fact.text().isBlank() || fact.elementIntent().ownerInteractionId() == null
        || !fact.interactionIds().contains(fact.elementIntent().ownerInteractionId()))
      return Optional.empty();
    return valid(fact.elementIntent(), flow, targetRef);
  }

  public static Optional<RequirementElementIntent> resolveDraft(
      RequirementCaptureInput.DraftInput draft, String targetRef) {
    if (draft == null || draft.flow() == null || draft.flow().interactions() == null
        || draft.flow().transitions() == null || draft.facts() == null || targetRef == null
        || targetRef.isBlank()) return Optional.empty();
    var candidates = draft.facts().stream().filter(Objects::nonNull)
        .filter(fact -> fact.elementIntent() != null && targetRef.equals(fact.elementIntent().elementRef()))
        .toList();
    if (candidates.size() != 1) return Optional.empty();
    var fact = candidates.getFirst();
    if (fact.polarity() != RequirementCaptureInput.Polarity.POSITIVE || fact.sourceFactId() == null
        || fact.sourceFactId().isBlank() || fact.text() == null || fact.text().isBlank()
        || fact.elementIntent().ownerInteractionId() == null || fact.interactionIds() == null
        || !fact.interactionIds().contains(fact.elementIntent().ownerInteractionId()))
      return Optional.empty();
    return valid(fact.elementIntent(), RequirementCaptureProjection.flow(draft), targetRef);
  }

  public static MappingSchemaSide outputSchema(
      MappingIntent intent, Map<MappingSource.Message, MappingSchemaSide> origins) {
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    var fields = new TreeMap<String, com.fasterxml.jackson.databind.JsonNode>();
    for (var rule : intent.rules()) {
      CopyMappingPlanner.requireUnconditional(rule);
      if (!(rule.value() instanceof MappingValue.Copy copy)
          || !(copy.source() instanceof MappingSource.Message origin)
          || !"ERROR".equals(rule.missingSourcePolicy()) || rule.targetPath() == null
          || !rule.targetPath().matches("\\$\\.[A-Za-z_][A-Za-z0-9_]*")) {
        throw new IllegalArgumentException("Local output needs unconditional verified COPY fields");
      }
      String name = rule.targetPath().substring(2);
      if (fields.putIfAbsent(name, CopyMappingPlanner.verifiedFieldSchema(origins.get(origin), origin.path())) != null) {
        throw new IllegalArgumentException("Local output assigns a field more than once: " + rule.targetPath());
      }
    }
    if (fields.isEmpty()) throw new IllegalArgumentException("Local output has no COPY fields");
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    fields.forEach(properties::set);
    try {
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(schema)));
      return new MappingSchemaSide("1", intent.targetRef(), null, MappingPort.OUTPUT,
          "application/json", null, digest, "verified-local-copy", schema);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot pin local COPY output schema", failure);
    }
  }

  private static Optional<RequirementElementIntent> valid(
      RequirementElementIntent intent, RequirementFlow flow, String targetRef) {
    if (intent.placement() != RequirementElementIntent.Placement.LOCAL_NODE
        || !"script".equals(intent.elementType()) || intent.ownerInteractionId() == null
        || intent.ownerInteractionId().isBlank() || intent.predecessorElementRef() == null
        || intent.predecessorElementRef().isBlank()) return Optional.empty();
    var owners = flow.interactions().stream()
        .filter(item -> intent.ownerInteractionId().equals(item.interactionId())).toList();
    long predecessor = flow.interactions().stream().filter(item -> intent.predecessorElementRef().equals(item.interactionId())).count();
    boolean collision = flow.interactions().stream().anyMatch(item -> targetRef.equals(item.interactionId()));
    boolean terminal = flow.transitions().stream()
        .noneMatch(edge -> intent.predecessorElementRef().equals(edge.sourceInteractionId()));
    return owners.size() == 1 && owners.getFirst().direction() == RequirementFlow.Direction.INBOUND
        && predecessor == 1 && terminal && !collision ? Optional.of(intent) : Optional.empty();
  }
}
