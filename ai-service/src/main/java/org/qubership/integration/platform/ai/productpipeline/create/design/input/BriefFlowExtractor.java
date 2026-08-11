package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Maps an approved {@link RequirementBrief} into a single {@link NormalizedDesignFlow}.
 *
 * <p>Derives trigger path/method/operation and service-call participants only from explicit brief
 * facts (and HTTP-shaped {@code inputs} when the ENDPOINT fact carries no HTTP identity). Returns
 * {@link ExtractionResult.NeedsInput} when required identity is absent — never invents path,
 * method, operation, or participant names.
 *
 * <p>SERVICE_CALL fact text must use {@code <Participant>: <operationQuery>} so the target system
 * and search query are both explicit.
 *
 * <p>Script-only briefs (no positive SERVICE_CALL, with script intent and/or an explicit "no
 * service call" constraint) produce a {@code script} process step instead of requiring outbound
 * binding.
 */
public final class BriefFlowExtractor {

  private static final Pattern HTTP_IDENTITY =
      Pattern.compile(
          "(?i)\\b(?:HTTP\\s+)?(GET|POST|PUT|PATCH|DELETE)\\b(?:[^\\n/]{0,80}?)(/[\\w./{}-]*)"
              + "(?:\\s+([A-Za-z][\\w.-]*))?");

  private static final Pattern SCRIPT_INTENT =
      Pattern.compile("(?i)\\bscript\\b|\\bqip script\\b");

  private static final Pattern NO_SERVICE_CALL =
      Pattern.compile("(?i)\\bno\\s+service\\s*calls?\\b|\\bservice-call\\b");

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

    List<RequirementFact> endpoints = positive(brief, RequirementFactKind.ENDPOINT);
    List<RequirementFact> calls = positive(brief, RequirementFactKind.SERVICE_CALL);
    boolean scriptOnly = calls.isEmpty() && isScriptOnlyBrief(brief);

    RequirementFact triggerFact = endpoints.isEmpty() ? null : endpoints.getFirst();
    HttpIdentity triggerHttp =
        (triggerFact == null ? Optional.<HttpIdentity>empty() : parseHttpIdentity(triggerFact.text()))
            .or(() -> firstHttpIdentity(brief.inputs()))
            .or(() -> firstHttpIdentityFromFacts(brief))
            .or(() -> parseHttpIdentity(brief.summary()))
            .orElse(null);
    if (triggerFact == null && triggerHttp != null) {
      triggerFact =
          new RequirementFact(
              "brief-http-trigger",
              RequirementFactPolarity.POSITIVE,
              RequirementFactKind.ENDPOINT,
              "http-trigger",
              triggerHttp.method() + " " + triggerHttp.path());
    }
    if (triggerFact == null) {
      missing.add("ENDPOINT trigger fact");
    }
    if (!scriptOnly && calls.isEmpty()) {
      missing.add("SERVICE_CALL process step");
    }
    if (triggerHttp == null || triggerHttp.path() == null) {
      missing.add("trigger path (ENDPOINT fact or inputs, e.g. HTTP POST /path)");
    }
    if (triggerHttp == null || triggerHttp.method() == null) {
      missing.add("trigger method (ENDPOINT fact or inputs, e.g. HTTP POST /path)");
    }
    String operationName =
        triggerHttp == null
            ? null
            : firstNonBlank(triggerHttp.operationId(), triggerHttp.method());
    if (operationName == null) {
      missing.add("trigger operation name or method");
    }
    if (triggerFact == null || triggerHttp == null || (!scriptOnly && calls.isEmpty())) {
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
        Optional<ServiceCallIdentity> callIdentity = parseServiceCall(call.text());
        if (callIdentity.isEmpty()) {
          missing.add(
              "SERVICE_CALL participant and operation query ("
                  + call.sourceFactId()
                  + " must be '<Participant>: <operationQuery>')");
          continue;
        }
        ServiceCallIdentity identity = callIdentity.get();
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

    List<NormalizedDesignFlow.DataMapping> mappings = new ArrayList<>();
    for (RequirementDataMapping mapping : brief.dataMappings()) {
      String fromStep = intentToStep.get(mapping.fromIntentRef());
      String toStep = intentToStep.get(mapping.toIntentRef());
      if (fromStep == null || toStep == null) {
        missing.add(
            "mapping "
                + mapping.mappingId()
                + " intent refs "
                + mapping.fromIntentRef()
                + " → "
                + mapping.toIntentRef());
        continue;
      }
      NormalizedDesignFlow.MappingStage stage =
          NormalizedDesignFlow.MappingStage.valueOf(mapping.stage().name());
      NormalizedDesignFlow.MappingMode mode =
          NormalizedDesignFlow.MappingMode.valueOf(mapping.mode().name());
      List<NormalizedDesignFlow.MappingRule> rules = new ArrayList<>();
      if (mapping.rules() != null) {
        for (RequirementDataMapping.Rule rule : mapping.rules()) {
          rules.add(
              new NormalizedDesignFlow.MappingRule(
                  rule.sourcePath(),
                  rule.targetPath(),
                  rule.expression(),
                  mapping.sourceFactIds()));
        }
      }
      mappings.add(
          new NormalizedDesignFlow.DataMapping(
              mapping.mappingId(),
              stage,
              fromStep,
              toStep,
              mode,
              rules,
              mapping.sourceFactIds()));
    }
    if (!missing.isEmpty()) {
      return new ExtractionResult.NeedsInput(List.copyOf(missing));
    }

    NormalizedDesignFlow.Trigger trigger =
        new NormalizedDesignFlow.Trigger(
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
            List.of(),
            List.of(),
            mappings,
            List.copyOf(brief.constraints()),
            List.copyOf(brief.assumptions()));
    return new ExtractionResult.Complete(flow);
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
    if (brief.constraints() != null) {
      for (String constraint : brief.constraints()) {
        if (constraint != null && NO_SERVICE_CALL.matcher(constraint).find()) {
          return true;
        }
      }
    }
    return brief.facts().stream()
        .filter(Objects::nonNull)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.NEGATIVE)
        .anyMatch(
            fact -> {
              String key = fact.capabilityKey() == null ? "" : fact.capabilityKey();
              String text = fact.text() == null ? "" : fact.text();
              return key.toLowerCase(Locale.ROOT).contains("service-call")
                  || NO_SERVICE_CALL.matcher(text).find();
            });
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
    if (key.toLowerCase(Locale.ROOT).contains("script")) {
      return true;
    }
    return fact.text() != null && SCRIPT_INTENT.matcher(fact.text()).find();
  }

  private static List<RequirementFact> positive(RequirementBrief brief, RequirementFactKind kind) {
    return brief.facts().stream()
        .filter(Objects::nonNull)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.kind() == kind)
        .toList();
  }

  private static Optional<HttpIdentity> parseHttpIdentity(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = HTTP_IDENTITY.matcher(text.trim());
    if (!matcher.find()) {
      return Optional.empty();
    }
    String method = matcher.group(1).toUpperCase(Locale.ROOT);
    String path = matcher.group(2);
    if (path.endsWith("\"") || path.endsWith("'")) {
      path = path.substring(0, path.length() - 1);
    }
    String operationId = trimToNull(matcher.group(3));
    return Optional.of(new HttpIdentity(method, path, operationId));
  }

  private static Optional<HttpIdentity> firstHttpIdentity(List<String> inputs) {
    if (inputs == null) {
      return Optional.empty();
    }
    for (String input : inputs) {
      Optional<HttpIdentity> parsed = parseHttpIdentity(input);
      if (parsed.isPresent()) {
        return parsed;
      }
    }
    return Optional.empty();
  }

  private static Optional<HttpIdentity> firstHttpIdentityFromFacts(RequirementBrief brief) {
    for (RequirementFact fact : brief.facts()) {
      if (fact == null || fact.polarity() != RequirementFactPolarity.POSITIVE) {
        continue;
      }
      Optional<HttpIdentity> parsed = parseHttpIdentity(fact.text());
      if (parsed.isPresent()) {
        return parsed;
      }
    }
    return Optional.empty();
  }

  private static Optional<ServiceCallIdentity> parseServiceCall(String text) {
    String trimmed = trimToNull(text);
    if (trimmed == null) {
      return Optional.empty();
    }
    int separator = trimmed.indexOf(':');
    if (separator <= 0 || separator >= trimmed.length() - 1) {
      return Optional.empty();
    }
    String participant = trimToNull(trimmed.substring(0, separator));
    String query = trimToNull(trimmed.substring(separator + 1));
    if (participant == null || query == null) {
      return Optional.empty();
    }
    return Optional.of(new ServiceCallIdentity(participant, query));
  }

  private static String participantId(String displayName) {
    String slug =
        displayName
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
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
    String trimmed = trimToNull(text);
    if (trimmed == null) {
      return null;
    }
    return SCRIPT_INTENT.matcher(trimmed).find() ? trimmed : null;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record HttpIdentity(String method, String path, String operationId) {}

  private record ServiceCallIdentity(String participantDisplayName, String operationQuery) {}
}
