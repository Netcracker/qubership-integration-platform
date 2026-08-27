package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.RequirementTriggerRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Maps an approved {@link RequirementBrief} into a single {@link NormalizedDesignFlow}.
 *
 * <p>Java copies identity fields the requirement-analysis model already wrote onto typed facts. It
 * does not read {@code text} for HTTP methods, topics, participants, or operation ids. Missing
 * identity is {@link ExtractionResult.NeedsInput} so analysis can rewrite the fact.
 *
 * <p>ENDPOINT {@code capabilityKey} selects the trigger family ({@code kafka-trigger-2},
 * {@code http-trigger}). Kafka copies {@code topic} and {@code operation}. HTTP copies
 * {@code httpMethod} and {@code path}. SERVICE_CALL copies {@code participant} and
 * {@code operation}. {@code text} is a human description only.
 *
 * <p>Script-only briefs (no positive SERVICE_CALL, with a script capability or a negative
 * service-call constraint) produce a {@code script} process step.
 */
public final class BriefFlowExtractor {

  private static final Set<String> KAFKA_TRIGGER_KEYS =
      Set.of("kafka-trigger-2", "kafka-trigger");

  public sealed interface ExtractionResult {
    record Complete(NormalizedDesignFlow flow) implements ExtractionResult {}

    record NeedsInput(List<String> missingFacts) implements ExtractionResult {}
  }

  public ExtractionResult extract(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    List<String> missing = new ArrayList<>();
    String chainName = trimToNull(brief.goal());
    if (chainName == null) {
      missing.add("goal / chain name");
    }

    List<RequirementFact> endpoints = triggerFacts(brief);
    List<RequirementFact> calls = positive(brief, RequirementFactKind.SERVICE_CALL);
    boolean scriptOnly = calls.isEmpty() && isScriptOnlyBrief(brief);

    RequirementFact triggerFact = endpoints.isEmpty() ? null : endpoints.getFirst();
    KafkaIdentity kafkaTrigger = kafkaFromEndpoint(triggerFact);
    HttpIdentity triggerHttp =
        kafkaTrigger != null ? null : httpFromEndpoint(triggerFact);
    if (triggerFact == null) {
      missing.add("ENDPOINT trigger fact");
    }
    if (!scriptOnly && calls.isEmpty()) {
      missing.add("SERVICE_CALL process step");
    }
    String operationName;
    if (kafkaTrigger != null) {
      if (kafkaTrigger.topic() == null) {
        missing.add("ENDPOINT.topic");
      }
      operationName = kafkaTrigger.operationName();
      if (operationName == null) {
        missing.add("ENDPOINT.operation");
      }
    } else {
      if (triggerHttp == null || triggerHttp.path() == null) {
        missing.add("ENDPOINT.path");
      }
      if (triggerHttp == null || triggerHttp.method() == null) {
        missing.add("ENDPOINT.httpMethod");
      }
      operationName =
          triggerHttp == null
              ? null
              : firstNonBlank(triggerHttp.operationId(), triggerHttp.method());
      if (operationName == null) {
        missing.add("ENDPOINT.httpMethod");
      }
    }
    if (triggerFact == null || (!scriptOnly && calls.isEmpty())) {
      return new ExtractionResult.NeedsInput(List.copyOf(missing));
    }

    Map<String, String> intentToStep = new LinkedHashMap<>();
    intentToStep.put(triggerFact.sourceFactId(), "step-trigger");

    Map<String, NormalizedDesignFlow.Participant> participants = new LinkedHashMap<>();
    String clientId = "p-client";
    participants.put(
        clientId,
        new NormalizedDesignFlow.Participant(
            clientId, "Client", "EXTERNAL", List.of(triggerFact.sourceFactId())));

    List<NormalizedDesignFlow.Step> steps = new ArrayList<>();
    String firstTargetDisplayName = null;
    int index = 1;

    if (scriptOnly) {
      String cipId = "p-cip";
      participants.putIfAbsent(
          cipId,
          new NormalizedDesignFlow.Participant(
              cipId, "CIP Chain", "INTERNAL", List.of(triggerFact.sourceFactId())));
      List<RequirementFact> scriptFacts = scriptFacts(brief);
      // Script-only is already established (no SERVICE_CALL + forbid/script intent). Live briefs
      // sometimes emit only ENDPOINT + CONSTRAINT without a BEHAVIOR/script fact — synthesize one
      // script step from summary/goal rather than looping NEEDS_INPUT at design-input.
      if (scriptFacts.isEmpty()) {
        String scriptLabel =
            firstNonBlank(
                scriptLabelFromText(brief.summary()),
                firstNonBlank(scriptLabelFromText(brief.goal()), "Return plain text from script"));
        String syntheticId = "brief-script";
        String stepId = "step-" + index++;
        intentToStep.put(syntheticId, stepId);
        steps.add(
            new NormalizedDesignFlow.Step(
                stepId,
                "script",
                clientId,
                cipId,
                scriptLabel,
                scriptLabel,
                List.of(syntheticId)));
      } else {
        for (RequirementFact scriptFact : scriptFacts) {
          String stepId = "step-" + index++;
          intentToStep.put(scriptFact.sourceFactId(), stepId);
          steps.add(
              new NormalizedDesignFlow.Step(
                  stepId,
                  "script",
                  clientId,
                  cipId,
                  scriptFact.text(),
                  scriptFact.text(),
                  List.of(scriptFact.sourceFactId())));
        }
      }
    } else {
      for (RequirementFact call : calls) {
        ServiceCallIdentity identity = serviceCallFrom(call);
        if (identity == null) {
          if (trimToNull(call.participant()) == null) {
            missing.add("SERVICE_CALL.participant");
          }
          if (trimToNull(call.operation()) == null) {
            missing.add("SERVICE_CALL.operation");
          }
          continue;
        }
        String stepId = "step-" + index++;
        intentToStep.put(call.sourceFactId(), stepId);
        String targetId = participantId(identity.participantDisplayName());
        if (firstTargetDisplayName == null) {
          firstTargetDisplayName = identity.participantDisplayName();
        }
        participants.putIfAbsent(
            targetId,
            new NormalizedDesignFlow.Participant(
                targetId,
                identity.participantDisplayName(),
                "EXTERNAL",
                List.of(call.sourceFactId())));
        steps.add(
            new NormalizedDesignFlow.Step(
                stepId,
                "service-call",
                clientId,
                targetId,
                identity.operationQuery(),
                "",
                List.of(call.sourceFactId())));
      }
    }

    List<NormalizedDesignFlow.DataMapping> mappings = explicitMappings(brief, intentToStep);
    List<NormalizedDesignFlow.Connection> connections =
        passThroughConnections(brief, intentToStep, steps);
    if (!missing.isEmpty()) {
      return new ExtractionResult.NeedsInput(List.copyOf(missing));
    }

    NormalizedDesignFlow.Trigger trigger =
        kafkaTrigger != null
            ? new NormalizedDesignFlow.Trigger(
                "kafka",
                clientId,
                firstTargetDisplayName,
                kafkaTrigger.topic(),
                operationName,
                List.of(triggerFact.sourceFactId()))
            : new NormalizedDesignFlow.Trigger(
                "http",
                clientId,
                firstTargetDisplayName,
                triggerHttp.path(),
                operationName,
                List.of(triggerFact.sourceFactId()));

    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "flow-1",
            chainName,
            brief.summary() == null ? "" : brief.summary().trim(),
            trigger,
            List.copyOf(participants.values()),
            steps,
            connections,
            List.of(),
            mappings,
            List.copyOf(brief.constraints()),
            List.copyOf(brief.assumptions()),
            bindingResolutionPolicy(brief));
    return new ExtractionResult.Complete(flow);
  }

  /**
   * Projects typed mapping intent onto the step ids of an authored IDS flow. The IDS author owns
   * topology and labels; the approved brief remains the source of truth for mapping semantics.
   */
  public NormalizedDesignFlow withMappings(
      RequirementBrief brief, NormalizedDesignFlow authoredFlow) {
    Objects.requireNonNull(brief, "brief");
    Objects.requireNonNull(authoredFlow, "authoredFlow");
    List<RequirementFact> endpoints = triggerFacts(brief);
    List<RequirementFact> calls = positive(brief, RequirementFactKind.SERVICE_CALL);
    List<NormalizedDesignFlow.Step> serviceCallSteps =
        authoredFlow.steps().stream()
            .filter(step -> "service-call".equalsIgnoreCase(step.kind()))
            .toList();
    if (!brief.dataMappings().isEmpty() && endpoints.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot project data mappings because the requirement brief has no ENDPOINT fact");
    }
    if (!brief.dataMappings().isEmpty() && calls.size() != serviceCallSteps.size()) {
      throw new IllegalArgumentException(
          serviceCallCoverageGap(calls.size(), serviceCallSteps.size()));
    }

    Map<String, String> intentToStep = new LinkedHashMap<>();
    if (!endpoints.isEmpty()) {
      intentToStep.put(endpoints.getFirst().sourceFactId(), "step-trigger");
    }
    for (int i = 0; i < calls.size(); i++) {
      intentToStep.put(calls.get(i).sourceFactId(), serviceCallSteps.get(i).stepId());
    }
    List<NormalizedDesignFlow.DataMapping> mappings = explicitMappings(brief, intentToStep);
    List<NormalizedDesignFlow.Connection> connections =
        passThroughConnections(brief, intentToStep, authoredFlow.steps());
    if (mappings.isEmpty() && connections.isEmpty()) {
      mappings = authoredFlow.dataMappings();
      connections = authoredFlow.connections();
    }
    return new NormalizedDesignFlow(
        authoredFlow.schemaVersion(),
        authoredFlow.flowId(),
        authoredFlow.chainName(),
        authoredFlow.description(),
        authoredFlow.trigger(),
        authoredFlow.participants(),
        authoredFlow.steps(),
        connections,
        authoredFlow.transformations(),
        mappings,
        authoredFlow.constraints(),
        authoredFlow.assumptions(),
        bindingResolutionPolicy(brief));
  }

  private static NormalizedDesignFlow.BindingResolutionPolicy bindingResolutionPolicy(
      RequirementBrief brief) {
    boolean catalogOnly =
        brief.facts().stream()
            .filter(Objects::nonNull)
            .filter(fact -> fact.polarity() == RequirementFactPolarity.NEGATIVE)
            .map(RequirementFact::capabilityKey)
            .filter(Objects::nonNull)
            .anyMatch(key -> key.contains("apihub"));
    return catalogOnly
        ? NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_ONLY
        : NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_FIRST;
  }

  private static List<NormalizedDesignFlow.DataMapping> explicitMappings(
      RequirementBrief brief, Map<String, String> intentToStep) {
    List<NormalizedDesignFlow.DataMapping> mappings = new ArrayList<>();
    for (RequirementDataMapping mapping : brief.dataMappings()) {
      if (mapping == null || mapping.mode() != RequirementDataMapping.Mode.EXPLICIT) {
        continue;
      }
      String fromStep = intentToStep.get(mapping.fromIntentRef());
      String toStep = intentToStep.get(mapping.toIntentRef());
      if (fromStep == null || toStep == null) {
        continue;
      }
      List<String> sourceFactIds =
          mapping.sourceFactIds().isEmpty()
              ? List.of("requirement-mapping:" + mapping.mappingId())
              : mapping.sourceFactIds();
      List<NormalizedDesignFlow.MappingRule> rules = new ArrayList<>();
      for (RequirementDataMapping.Rule rule : mapping.rules()) {
        rules.add(
            new NormalizedDesignFlow.MappingRule(
                rule.sourcePath(), rule.targetPath(), rule.expression(), sourceFactIds));
      }
      mappings.add(
          new NormalizedDesignFlow.DataMapping(
              normalizedMappingId(mapping, fromStep, toStep),
              NormalizedDesignFlow.MappingStage.valueOf(mapping.stage().name()),
              fromStep,
              toStep,
              NormalizedDesignFlow.MappingMode.EXPLICIT,
              rules,
              sourceFactIds));
    }
    return List.copyOf(mappings);
  }

  /**
   * Direct execution edges for boundaries without an explicit mapping intent. Unknown schemas and
   * legacy {@code PASS_THROUGH} rows become connections, not mapping records.
   */
  private static List<NormalizedDesignFlow.Connection> passThroughConnections(
      RequirementBrief brief,
      Map<String, String> intentToStep,
      List<NormalizedDesignFlow.Step> steps) {
    Set<String> explicitEdges = explicitForwardEdges(brief, intentToStep);
    List<String> chain = new ArrayList<>();
    if (!triggerFacts(brief).isEmpty()) {
      chain.add("step-trigger");
    }
    List<RequirementFact> calls = positive(brief, RequirementFactKind.SERVICE_CALL);
    if (!calls.isEmpty()) {
      for (RequirementFact call : calls) {
        String stepId = intentToStep.get(call.sourceFactId());
        if (stepId != null) {
          chain.add(stepId);
        }
      }
    } else {
      for (NormalizedDesignFlow.Step step : steps) {
        if (isProcessStep(step)) {
          chain.add(step.stepId());
        }
      }
    }
    List<NormalizedDesignFlow.Connection> connections = new ArrayList<>();
    for (int i = 0; i < chain.size() - 1; i++) {
      String from = chain.get(i);
      String to = chain.get(i + 1);
      if (explicitEdges.contains(from + '\n' + to)) {
        continue;
      }
      connections.add(
          new NormalizedDesignFlow.Connection(from, to, null, provenanceForEdge(brief, from, to)));
    }
    return List.copyOf(connections);
  }

  private static Set<String> explicitForwardEdges(
      RequirementBrief brief, Map<String, String> intentToStep) {
    Set<String> edges = new LinkedHashSet<>();
    for (RequirementDataMapping mapping : brief.dataMappings()) {
      if (mapping == null || mapping.mode() != RequirementDataMapping.Mode.EXPLICIT) {
        continue;
      }
      String fromStep = intentToStep.get(mapping.fromIntentRef());
      String toStep = intentToStep.get(mapping.toIntentRef());
      if (fromStep == null || toStep == null) {
        continue;
      }
      if (mapping.stage() == RequirementDataMapping.Stage.RESPONSE) {
        continue;
      }
      edges.add(fromStep + '\n' + toStep);
    }
    return edges;
  }

  private static List<String> provenanceForEdge(RequirementBrief brief, String from, String to) {
    List<String> ids = new ArrayList<>();
    for (RequirementFact fact : triggerFacts(brief)) {
      if ("step-trigger".equals(from) || "step-trigger".equals(to)) {
        ids.add(fact.sourceFactId());
        break;
      }
    }
    for (RequirementFact fact : positive(brief, RequirementFactKind.SERVICE_CALL)) {
      ids.add(fact.sourceFactId());
    }
    if (ids.isEmpty()) {
      return List.of("connection:" + from + ":" + to);
    }
    return List.copyOf(ids);
  }

  private static boolean isProcessStep(NormalizedDesignFlow.Step step) {
    if (step == null || step.kind() == null || step.stepId() == null || step.stepId().isBlank()) {
      return false;
    }
    String kind = step.kind().toLowerCase(Locale.ROOT);
    return "service-call".equals(kind) || "script".equals(kind);
  }

  private static String normalizedMappingId(
      RequirementDataMapping mapping, String fromStep, String toStep) {
    if (!mapping.mappingId().isBlank()) {
      return mapping.mappingId();
    }
    return "map-"
        + mapping.stage().name().toLowerCase(Locale.ROOT)
        + "-"
        + fromStep
        + "-to-"
        + toStep;
  }

  /**
   * Overlay cannot bind mapping edges until the authored IDS has one service-call step per brief
   * SERVICE_CALL fact. Missing steps are an IDS coverage gap: regenerate the diagram, do not invent
   * step ids.
   */
  private static String serviceCallCoverageGap(int briefCalls, int idsCalls) {
    if (idsCalls < briefCalls) {
      return "Cannot project data mappings: the authored IDS is missing required outbound"
          + " service-call steps (brief has "
          + briefCalls
          + ", IDS has "
          + idsCalls
          + "). Add each SERVICE_CALL as a CIP -> external participant message in the sequence"
          + " diagram.";
    }
    return "Cannot project data mappings: requirement brief has "
        + briefCalls
        + (briefCalls == 1 ? " service call" : " service calls")
        + " but the authored IDS has "
        + idsCalls;
  }

  static boolean isScriptOnlyBrief(RequirementBrief brief) {
    if (!positive(brief, RequirementFactKind.SERVICE_CALL).isEmpty()) {
      return false;
    }
    return hasScriptIntent(brief) || forbidsServiceCalls(brief);
  }

  private static boolean hasScriptIntent(RequirementBrief brief) {
    return brief.facts().stream()
        .filter(Objects::nonNull)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .anyMatch(BriefFlowExtractor::looksLikeScriptFact);
  }

  private static boolean forbidsServiceCalls(RequirementBrief brief) {
    return brief.facts().stream()
        .filter(Objects::nonNull)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.NEGATIVE)
        .map(RequirementFact::capabilityKey)
        .filter(Objects::nonNull)
        .anyMatch(key -> key.contains("service-call"));
  }

  private static List<RequirementFact> scriptFacts(RequirementBrief brief) {
    List<RequirementFact> scripts =
        brief.facts().stream()
            .filter(Objects::nonNull)
            .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
            .filter(BriefFlowExtractor::looksLikeScriptFact)
            .toList();
    if (!scripts.isEmpty()) {
      return scripts;
    }
    return positive(brief, RequirementFactKind.BEHAVIOR);
  }

  private static boolean looksLikeScriptFact(RequirementFact fact) {
    String key = fact.capabilityKey() == null ? "" : fact.capabilityKey();
    return key.contains("script");
  }

  private static List<RequirementFact> triggerFacts(RequirementBrief brief) {
    List<RequirementFact> catalogTriggers = RequirementTriggerRole.positiveTriggers(brief.facts());
    if (!catalogTriggers.isEmpty()) {
      return catalogTriggers;
    }
    return positive(brief, RequirementFactKind.ENDPOINT);
  }

  private static List<RequirementFact> positive(RequirementBrief brief, RequirementFactKind kind) {
    return brief.facts().stream()
        .filter(Objects::nonNull)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.kind() == kind)
        .toList();
  }

  private static HttpIdentity httpFromEndpoint(RequirementFact triggerFact) {
    if (triggerFact == null || isKafkaTrigger(triggerFact)) {
      return null;
    }
    String method = trimToNull(triggerFact.httpMethod());
    return new HttpIdentity(
        method == null ? null : method.toUpperCase(Locale.ROOT),
        trimToNull(triggerFact.path()),
        trimToNull(triggerFact.operation()));
  }

  private static KafkaIdentity kafkaFromEndpoint(RequirementFact triggerFact) {
    if (triggerFact == null || !isKafkaTrigger(triggerFact)) {
      return null;
    }
    return new KafkaIdentity(
        trimToNull(triggerFact.topic()), trimToNull(triggerFact.operation()));
  }

  private static boolean isKafkaTrigger(RequirementFact fact) {
    String key = fact.capabilityKey() == null ? "" : fact.capabilityKey();
    return KAFKA_TRIGGER_KEYS.contains(key);
  }

  private static ServiceCallIdentity serviceCallFrom(RequirementFact fact) {
    String participant = trimToNull(fact.participant());
    String operation = trimToNull(fact.operation());
    if (participant == null || operation == null) {
      return null;
    }
    return new ServiceCallIdentity(participant, operation);
  }

  private static String participantId(String displayName) {
    StringBuilder slug = new StringBuilder();
    boolean dash = false;
    for (int i = 0; i < displayName.length(); i++) {
      char ch = Character.toLowerCase(displayName.charAt(i));
      if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
        slug.append(ch);
        dash = false;
      } else if (slug.length() > 0 && !dash) {
        slug.append('-');
        dash = true;
      }
    }
    if (dash && slug.length() > 0) {
      slug.setLength(slug.length() - 1);
    }
    return "p-" + slug;
  }

  private static String firstNonBlank(String value, String fallback) {
    String trimmed = trimToNull(value);
    return trimmed != null ? trimmed : trimToNull(fallback);
  }

  /**
   * Prefer summary/goal text that already mentions a script so the synthetic step stays grounded in
   * the brief; otherwise return null and let the caller use a stable default label.
   */
  private static String scriptLabelFromText(String text) {
    return trimToNull(text);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record HttpIdentity(String method, String path, String operationId) {}

  private record KafkaIdentity(String topic, String operationName) {}

  private record ServiceCallIdentity(String participantDisplayName, String operationQuery) {}
}
