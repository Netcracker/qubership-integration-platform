package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  private static final Pattern EXPLICIT_RULE =
      Pattern.compile(
          "^(?:(\\d+)\\s*[:.)]\\s*)?(.+?)\\s*(?:->|→)\\s*(.+?)(?:\\s*\\|\\s*(.+))?$");

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
   *
   * <p>Lines keep internal {@code sourceFactId} values (often SHA-256). Prefer {@link
   * #listReadableMissingEdges(RequirementBrief)} for chat cards.
   */
  public List<String> listMissingEdges(RequirementBrief brief) {
    return listMissingEdges(brief, false);
  }

  /**
   * Same topology gaps as {@link #listMissingEdges(RequirementBrief)}, labeled with fact {@code
   * kind} and short {@code text} (or a role fallback). Digests stay out of the label.
   */
  public List<String> listReadableMissingEdges(RequirementBrief brief) {
    return listMissingEdges(brief, true);
  }

  private List<String> listMissingEdges(RequirementBrief brief, boolean readable) {
    Objects.requireNonNull(brief, "brief");
    List<String> missing = new ArrayList<>();
    for (RequiredEdge edge : missingRequiredEdges(brief)) {
      if (edge.fromIntentRef() == null) {
        missing.add(
            readable
                ? "INITIALIZATION: trigger → first outbound call (no ENDPOINT fact)"
                : "INITIALIZATION mapping required: trigger → first outbound call"
                    + " (no ENDPOINT fact)");
      } else if (readable) {
        missing.add(
            edge.stage()
                + ": "
                + factLabel(brief, edge.fromIntentRef(), "source")
                + " → "
                + factLabel(brief, edge.toIntentRef(), "target"));
      } else {
        missing.add(
            edge.stage()
                + " mapping required: "
                + edge.fromIntentRef()
                + " → "
                + edge.toIntentRef());
      }
    }
    return List.copyOf(missing);
  }

  /**
   * Fills missing required stage edges as {@code PASS_THROUGH} with a synthetic source fact id.
   * Existing mappings are kept. Caller must still {@link #validate(RequirementBrief)}.
   */
  public RequirementBrief withPassThroughForMissingEdges(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    List<RequirementDataMapping> merged = new ArrayList<>(brief.dataMappings());
    for (RequiredEdge edge : missingRequiredEdges(brief)) {
      if (edge.fromIntentRef() != null) {
        addPassThroughIfMissing(
            merged,
            edge.stage(),
            edge.fromIntentRef(),
            edge.toIntentRef(),
            edge.mappingId());
      }
    }
    return withMappings(brief, merged);
  }

  /**
   * Parses user-authored mapping rules and attaches them to the numbered missing edges shown in
   * the UI. Each line uses {@code [edge number:] sourcePath -> targetPath [| expression]}.
   */
  public RequirementBrief withExplicitMappingsForMissingEdges(
      RequirementBrief brief, String specification) {
    Objects.requireNonNull(brief, "brief");
    List<RequiredEdge> missing = missingRequiredEdges(brief);
    if (missing.isEmpty()) {
      return brief;
    }
    if (missing.stream().anyMatch(edge -> edge.fromIntentRef() == null)) {
      throw new IllegalArgumentException(
          "An ENDPOINT fact is required before explicit data mappings can be attached");
    }
    if (specification == null || specification.isBlank()) {
      throw new IllegalArgumentException(
          "Describe at least one mapping rule as sourcePath -> targetPath");
    }

    Map<Integer, List<RequirementDataMapping.Rule>> rulesByEdge = new LinkedHashMap<>();
    for (String rawLine : specification.lines().toList()) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      Matcher matcher = EXPLICIT_RULE.matcher(line);
      if (!matcher.matches()) {
        throw new IllegalArgumentException(
            "Invalid mapping rule '"
                + line
                + "'. Use: 1: $.source -> $.target | optional expression");
      }
      String edgeNumberText = matcher.group(1);
      if (edgeNumberText == null && missing.size() > 1) {
        throw new IllegalArgumentException(
            "Prefix each mapping rule with its edge number, for example: 1: $.source -> $.target");
      }
      int edgeNumber = edgeNumberText == null ? 1 : Integer.parseInt(edgeNumberText);
      if (edgeNumber < 1 || edgeNumber > missing.size()) {
        throw new IllegalArgumentException(
            "Mapping edge number "
                + edgeNumber
                + " is outside the displayed range 1-"
                + missing.size());
      }
      rulesByEdge
          .computeIfAbsent(edgeNumber, ignored -> new ArrayList<>())
          .add(
              new RequirementDataMapping.Rule(
                  matcher.group(2), matcher.group(3), matcher.group(4)));
    }
    if (rulesByEdge.isEmpty()) {
      throw new IllegalArgumentException(
          "Describe at least one mapping rule as sourcePath -> targetPath");
    }

    List<RequirementDataMapping> merged = new ArrayList<>(brief.dataMappings());
    for (Map.Entry<Integer, List<RequirementDataMapping.Rule>> entry : rulesByEdge.entrySet()) {
      RequiredEdge edge = missing.get(entry.getKey() - 1);
      merged.add(
          new RequirementDataMapping(
              edge.mappingId().replace("pass-through", "explicit"),
              edge.stage(),
              edge.fromIntentRef(),
              edge.toIntentRef(),
              RequirementDataMapping.Mode.EXPLICIT,
              entry.getValue(),
              List.of("design-input:mapping-answer")));
    }
    return withMappings(brief, merged);
  }

  private static List<RequiredEdge> missingRequiredEdges(RequirementBrief brief) {
    List<RequirementFact> outboundCalls = positiveFacts(brief, RequirementFactKind.SERVICE_CALL);
    if (outboundCalls.isEmpty()) {
      return List.of();
    }
    List<RequiredEdge> required = new ArrayList<>();
    RequirementFact trigger = firstPositiveEndpoint(brief);
    String triggerId = trigger == null ? null : trigger.sourceFactId();
    required.add(
        new RequiredEdge(
            RequirementDataMapping.Stage.INITIALIZATION,
            triggerId,
            outboundCalls.getFirst().sourceFactId(),
            "map-init-pass-through"));
    for (int i = 0; i < outboundCalls.size() - 1; i++) {
      required.add(
          new RequiredEdge(
              RequirementDataMapping.Stage.CONVERSION,
              outboundCalls.get(i).sourceFactId(),
              outboundCalls.get(i + 1).sourceFactId(),
              "map-conv-pass-through-" + (i + 1)));
    }
    if (trigger != null && looksRequestResponse(trigger)) {
      required.add(
          new RequiredEdge(
              RequirementDataMapping.Stage.RESPONSE,
              outboundCalls.getLast().sourceFactId(),
              triggerId,
              "map-resp-pass-through"));
    }
    return required.stream()
        .filter(
            edge ->
                edge.fromIntentRef() == null
                    || !hasStageEdge(
                        brief.dataMappings(),
                        edge.stage(),
                        edge.fromIntentRef(),
                        edge.toIntentRef()))
        .toList();
  }

  private static RequirementBrief withMappings(
      RequirementBrief brief, List<RequirementDataMapping> mappings) {
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
        mappings);
  }

  private record RequiredEdge(
      RequirementDataMapping.Stage stage,
      String fromIntentRef,
      String toIntentRef,
      String mappingId) {}

  /** Card label: {@code KIND "short text"}; falls back to a role when the fact is missing. */
  private static String factLabel(RequirementBrief brief, String sourceFactId, String roleFallback) {
    if (sourceFactId == null || sourceFactId.isBlank()) {
      return roleFallback;
    }
    for (RequirementFact fact : brief.facts()) {
      if (fact == null || fact.sourceFactId() == null) {
        continue;
      }
      if (!sourceFactId.equals(fact.sourceFactId())) {
        continue;
      }
      String kind = fact.kind() == null ? "FACT" : fact.kind().name();
      String text = shorten(fact.text(), 72);
      return kind + " \"" + text + '"';
    }
    return roleFallback;
  }

  private static String shorten(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String trimmed = text.strip();
    if (trimmed.length() <= maxChars) {
      return trimmed;
    }
    return trimmed.substring(0, Math.max(0, maxChars - 1)).stripTrailing() + "…";
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
