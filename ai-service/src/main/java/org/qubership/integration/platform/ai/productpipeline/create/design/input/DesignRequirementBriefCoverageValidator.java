package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Design-path ({@code create-chain@2}) coverage for typed mapping intent on a requirement brief.
 * Does not invent mapping rules; fails closed when required stages or modes are incomplete.
 *
 * <p>Request-response detection: {@link RequirementFactKind} has no sync/async split. A positive
 * {@code ENDPOINT} fact is treated as request-response unless its {@code capabilityKey} names a
 * known fire-and-forget CIP trigger type ({@code async-api-trigger}, {@code kafka-trigger-2},
 * {@code quartz-scheduler}, {@code rabbitmq-trigger-2}, and common aliases). Blank or unknown
 * capability keys default to request-response (typical HTTP / chain entry).
 */
public final class DesignRequirementBriefCoverageValidator {

  /**
   * CIP trigger capability keys that are fire-and-forget (no RESPONSE mapping required).
   * Drawn from catalog trigger types; not inferred from API schemas.
   */
  private static final Set<String> FIRE_AND_FORGET_CAPABILITY_KEYS =
      Set.of(
          "async-api-trigger",
          "kafka-trigger-2",
          "kafka-trigger",
          "quartz-scheduler",
          "rabbitmq-trigger-2",
          "rabbitmq-trigger",
          "sds-trigger",
          "pubsub-trigger");

  public void validate(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    List<RequirementDataMapping> mappings = brief.dataMappings();
    for (RequirementDataMapping mapping : mappings) {
      validateMappingShape(mapping);
    }
    List<String> missing = listMissingEdges(brief);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(missing.getFirst());
    }
  }

  /**
   * Human-readable missing stage edges (empty when coverage passes). Does not invent mapping
   * rules; only reports topology gaps.
   */
  public List<String> listMissingEdges(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    List<RequirementFact> outboundCalls = positiveFacts(brief, RequirementFactKind.SERVICE_CALL);
    if (outboundCalls.isEmpty()) {
      return List.of();
    }
    List<RequirementDataMapping> mappings = brief.dataMappings();
    List<String> missing = new ArrayList<>();
    RequirementFact trigger = firstPositiveEndpoint(brief);
    String firstCallId = outboundCalls.getFirst().sourceFactId();
    String lastCallId = outboundCalls.getLast().sourceFactId();

    if (trigger == null) {
      missing.add(
          "INITIALIZATION mapping required: trigger → first outbound call (no ENDPOINT fact)");
    } else {
      addMissingEdge(
          missing,
          mappings,
          RequirementDataMapping.Stage.INITIALIZATION,
          trigger.sourceFactId(),
          firstCallId);
    }

    for (int i = 0; i < outboundCalls.size() - 1; i++) {
      addMissingEdge(
          missing,
          mappings,
          RequirementDataMapping.Stage.CONVERSION,
          outboundCalls.get(i).sourceFactId(),
          outboundCalls.get(i + 1).sourceFactId());
    }

    if (trigger != null && looksRequestResponse(trigger)) {
      addMissingEdge(
          missing,
          mappings,
          RequirementDataMapping.Stage.RESPONSE,
          lastCallId,
          trigger.sourceFactId());
    }
    return List.copyOf(missing);
  }

  /**
   * Fills missing required stage edges as {@code PASS_THROUGH} with a synthetic source fact id.
   * Existing mappings are kept. Caller must still {@link #validate(RequirementBrief)}.
   */
  public RequirementBrief withPassThroughForMissingEdges(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    List<RequirementFact> outboundCalls = positiveFacts(brief, RequirementFactKind.SERVICE_CALL);
    if (outboundCalls.isEmpty()) {
      return brief;
    }
    RequirementFact trigger = firstPositiveEndpoint(brief);
    if (trigger == null) {
      return brief;
    }
    List<RequirementDataMapping> merged = new ArrayList<>(brief.dataMappings());
    String firstCallId = outboundCalls.getFirst().sourceFactId();
    String lastCallId = outboundCalls.getLast().sourceFactId();
    addPassThroughIfMissing(
        merged,
        RequirementDataMapping.Stage.INITIALIZATION,
        trigger.sourceFactId(),
        firstCallId,
        "map-init-pass-through");
    for (int i = 0; i < outboundCalls.size() - 1; i++) {
      addPassThroughIfMissing(
          merged,
          RequirementDataMapping.Stage.CONVERSION,
          outboundCalls.get(i).sourceFactId(),
          outboundCalls.get(i + 1).sourceFactId(),
          "map-conv-pass-through-" + (i + 1));
    }
    if (looksRequestResponse(trigger)) {
      addPassThroughIfMissing(
          merged,
          RequirementDataMapping.Stage.RESPONSE,
          lastCallId,
          trigger.sourceFactId(),
          "map-resp-pass-through");
    }
    return new RequirementBrief(
        brief.goal(),
        brief.inputs(),
        brief.constraints(),
        brief.assumptions(),
        brief.citations(),
        brief.summary(),
        brief.approvedDraftReference(),
        brief.approvedDraftText(),
        brief.facts(),
        merged);
  }

  private static void addMissingEdge(
      List<String> missing,
      List<RequirementDataMapping> mappings,
      RequirementDataMapping.Stage stage,
      String fromIntentRef,
      String toIntentRef) {
    if (hasStageEdge(mappings, stage, fromIntentRef, toIntentRef)) {
      return;
    }
    missing.add(stage + " mapping required: " + fromIntentRef + " → " + toIntentRef);
  }

  private static void addPassThroughIfMissing(
      List<RequirementDataMapping> mappings,
      RequirementDataMapping.Stage stage,
      String fromIntentRef,
      String toIntentRef,
      String mappingId) {
    if (hasStageEdge(mappings, stage, fromIntentRef, toIntentRef)) {
      return;
    }
    mappings.add(
        new RequirementDataMapping(
            mappingId,
            stage,
            fromIntentRef,
            toIntentRef,
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of(),
            List.of(mappingId + "-fact")));
  }

  private static boolean hasStageEdge(
      List<RequirementDataMapping> mappings,
      RequirementDataMapping.Stage stage,
      String fromIntentRef,
      String toIntentRef) {
    return mappings.stream()
        .filter(mapping -> mapping != null && mapping.stage() == stage)
        .anyMatch(
            mapping ->
                fromIntentRef.equals(mapping.fromIntentRef())
                    && toIntentRef.equals(mapping.toIntentRef()));
  }

  private static void validateMappingShape(RequirementDataMapping mapping) {
    if (mapping == null) {
      throw new IllegalArgumentException("dataMappings must not contain null entries");
    }
    if (mapping.stage() == null) {
      throw new IllegalArgumentException("dataMapping stage is required");
    }
    if (mapping.mode() == null) {
      throw new IllegalArgumentException("dataMapping mode is required");
    }
    switch (mapping.mode()) {
      case EXPLICIT -> {
        if (mapping.rules() == null || mapping.rules().isEmpty()) {
          throw new IllegalArgumentException("EXPLICIT mapping requires at least one rule");
        }
        for (RequirementDataMapping.Rule rule : mapping.rules()) {
          if (rule == null
              || rule.sourcePath() == null
              || rule.sourcePath().isBlank()
              || rule.targetPath() == null
              || rule.targetPath().isBlank()) {
            throw new IllegalArgumentException(
                "EXPLICIT mapping rule requires sourcePath and targetPath");
          }
        }
      }
      case PASS_THROUGH -> {
        if (mapping.rules() != null && !mapping.rules().isEmpty()) {
          throw new IllegalArgumentException(
              "PASS_THROUGH mapping must not declare rules; cite a sourceFactId for pass-through"
                  + " intent");
        }
        if (mapping.sourceFactIds() == null || mapping.sourceFactIds().isEmpty()) {
          throw new IllegalArgumentException(
              "PASS_THROUGH mapping requires at least one sourceFactId");
        }
      }
    }
  }

  private static RequirementFact firstPositiveEndpoint(RequirementBrief brief) {
    List<RequirementFact> endpoints = positiveFacts(brief, RequirementFactKind.ENDPOINT);
    return endpoints.isEmpty() ? null : endpoints.getFirst();
  }

  private static List<RequirementFact> positiveFacts(
      RequirementBrief brief, RequirementFactKind kind) {
    return brief.facts().stream()
        .filter(fact -> fact != null)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.kind() == kind)
        .toList();
  }

  /**
   * Whether the trigger looks request-response (caller awaits a reply). See class Javadoc for the
   * capabilityKey heuristic when {@link RequirementFactKind} has no sync polarity.
   */
  private static boolean looksRequestResponse(RequirementFact trigger) {
    String key = trigger.capabilityKey();
    if (key == null || key.isBlank()) {
      return true;
    }
    return !FIRE_AND_FORGET_CAPABILITY_KEYS.contains(key);
  }
}
