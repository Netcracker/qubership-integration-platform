package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.MappingQueryAnswer.RuleFact;
import org.qubership.integration.platform.ai.plan.MappingQueryAnswer.TransitionFact;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

/**
 * Reads mapping facts from the current requirement brief. The selector comes from interpretation;
 * this class does not consult the transcript.
 */
public final class MappingQueryLookup {

  private MappingQueryLookup() {}

  public static MappingQueryAnswer answer(RequirementBrief brief, MappingQuerySelector selector) {
    if (brief == null) {
      throw new IllegalArgumentException("brief");
    }
    MappingQuerySelector query =
        selector == null
            ? new MappingQuerySelector(
                null, null, null, null, null, false, MappingQuerySelector.Coverage.ANY)
            : selector;
    String language = "en";
    if (query.coverage() == MappingQuerySelector.Coverage.PASS_THROUGH) {
      return passThroughAnswer(brief, query, language);
    }
    List<MappingIntent> intents = matchingIntents(brief, query);
    if (query.unresolvedOnly()) {
      return unresolvedAnswer(intents, language);
    }
    if (query.coverage() == MappingQuerySelector.Coverage.MAPPED) {
      return mappedAnswer(intents, language);
    }
    List<RuleFact> rules = matchingRules(intents, query);
    if (rules.isEmpty() && passThroughBoundary(brief, query, intents)) {
      return render(
          language, true, List.of(passThroughFact(query.sourceRef(), query.targetRef())), List.of());
    }
    if (rules.isEmpty()) {
      return render(language, false, List.of(), List.of());
    }
    return render(language, true, mappedFacts(intentsFor(rules, intents)), rules);
  }

  private static MappingQueryAnswer passThroughAnswer(
      RequirementBrief brief, MappingQuerySelector query, String language) {
    if (query.mappingIntentId() != null) {
      return render(language, false, List.of(), List.of());
    }
    List<TransitionFact> transitions = new ArrayList<>();
    for (Transition transition : MappingGapCoverage.uncovered(brief)) {
      if (!matchesTransition(transition, query)) {
        continue;
      }
      transitions.add(
          new TransitionFact(
              transition.sourceInteractionId(), transition.targetInteractionId(), null, true));
    }
    return render(language, !transitions.isEmpty(), List.copyOf(transitions), List.of());
  }

  private static MappingQueryAnswer mappedAnswer(List<MappingIntent> intents, String language) {
    List<TransitionFact> transitions = mappedFacts(intents);
    return render(language, !transitions.isEmpty(), transitions, List.of());
  }

  private static MappingQueryAnswer unresolvedAnswer(List<MappingIntent> intents, String language) {
    List<RuleFact> rules = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    for (MappingIntent intent : intents) {
      for (MappingIntentRule rule : intent.rules()) {
        if (rule.status() != MappingRuleStatus.UNRESOLVED || rule.targetPath().isBlank()) {
          continue;
        }
        rules.add(toFact(intent, rule));
        unresolved.add(rule.targetPath());
      }
    }
    MappingQueryAnswer rendered = render(language, !rules.isEmpty(), List.of(), List.copyOf(rules));
    return new MappingQueryAnswer(
        rendered.language(),
        rendered.matchFound(),
        rendered.transitions(),
        rendered.rules(),
        List.copyOf(unresolved),
        rendered.rendered());
  }

  private static List<MappingIntent> matchingIntents(
      RequirementBrief brief, MappingQuerySelector query) {
    List<MappingIntent> matched = new ArrayList<>();
    for (MappingIntent intent : brief.mappingIntents()) {
      if (query.mappingIntentId() != null
          && !query.mappingIntentId().equals(intent.mappingIntentId())) {
        continue;
      }
      if (query.sourceRef() != null && !query.sourceRef().equals(intent.sourceRef())) {
        continue;
      }
      if (query.targetRef() != null && !query.targetRef().equals(intent.targetRef())) {
        continue;
      }
      matched.add(intent);
    }
    return matched;
  }

  private static List<RuleFact> matchingRules(
      List<MappingIntent> intents, MappingQuerySelector query) {
    List<RuleFact> rules = new ArrayList<>();
    for (MappingIntent intent : intents) {
      for (MappingIntentRule rule : intent.rules()) {
        if (!samePath(rule.sourcePath(), query.sourcePath())) {
          continue;
        }
        if (!samePath(rule.targetPath(), query.targetPath())) {
          continue;
        }
        rules.add(toFact(intent, rule));
      }
    }
    return rules;
  }

  private static boolean passThroughBoundary(
      RequirementBrief brief, MappingQuerySelector query, List<MappingIntent> intents) {
    if (!intents.isEmpty()
        || query.mappingIntentId() != null
        || query.sourcePath() != null
        || query.targetPath() != null
        || query.sourceRef() == null
        || query.targetRef() == null) {
      return false;
    }
    for (Transition transition : brief.flow().transitions()) {
      if (query.sourceRef().equals(transition.sourceInteractionId())
          && query.targetRef().equals(transition.targetInteractionId())) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesTransition(Transition transition, MappingQuerySelector query) {
    if (query.sourceRef() != null && !query.sourceRef().equals(transition.sourceInteractionId())) {
      return false;
    }
    return query.targetRef() == null || query.targetRef().equals(transition.targetInteractionId());
  }

  private static List<TransitionFact> mappedFacts(List<MappingIntent> intents) {
    List<TransitionFact> facts = new ArrayList<>();
    for (MappingIntent intent : intents) {
      facts.add(
          new TransitionFact(
              intent.sourceRef(), intent.targetRef(), intent.mappingIntentId(), false));
    }
    return facts;
  }

  private static List<MappingIntent> intentsFor(List<RuleFact> rules, List<MappingIntent> intents) {
    List<MappingIntent> used = new ArrayList<>();
    for (MappingIntent intent : intents) {
      for (RuleFact rule : rules) {
        if (intent.mappingIntentId().equals(rule.mappingIntentId())) {
          used.add(intent);
          break;
        }
      }
    }
    return used;
  }

  private static RuleFact toFact(MappingIntent intent, MappingIntentRule rule) {
    return new RuleFact(
        intent.mappingIntentId(),
        intent.sourceRef(),
        intent.targetRef(),
        rule.sourcePath(),
        rule.targetPath(),
        rule.expression(),
        rule.status());
  }

  private static TransitionFact passThroughFact(String sourceRef, String targetRef) {
    return new TransitionFact(sourceRef, targetRef, null, true);
  }

  private static boolean samePath(String stored, String selector) {
    if (selector == null) {
      return true;
    }
    return MappingContract.canonicalPath(stored).equals(MappingContract.canonicalPath(selector));
  }

  private static MappingQueryAnswer render(
      String language,
      boolean matchFound,
      List<TransitionFact> transitions,
      List<RuleFact> rules) {
    if (!matchFound) {
      return new MappingQueryAnswer(
          language, false, List.of(), List.of(), List.of(), noMatchText());
    }
    StringBuilder rendered = new StringBuilder();
    for (TransitionFact transition : transitions) {
      appendLine(rendered, transitionText(transition));
    }
    for (RuleFact rule : rules) {
      appendLine(rendered, ruleText(rule));
    }
    return new MappingQueryAnswer(
        language, true, transitions, rules, List.of(), rendered.toString());
  }

  private static void appendLine(StringBuilder rendered, String line) {
    if (!rendered.isEmpty()) {
      rendered.append('\n');
    }
    rendered.append(line);
  }

  private static String noMatchText() {
    return "No mapping matches this query.";
  }

  private static String transitionText(TransitionFact transition) {
    if (transition.passThrough()) {
      return "Transition "
          + transition.sourceRef()
          + " -> "
          + transition.targetRef()
          + " is pass-through.";
    }
    return "Transition "
        + transition.sourceRef()
        + " -> "
        + transition.targetRef()
        + " is mapped by "
        + transition.mappingIntentId()
        + ".";
  }

  private static String ruleText(RuleFact rule) {
    StringBuilder text = new StringBuilder();
    text.append(rule.mappingIntentId())
        .append(' ')
        .append(rule.sourceRef())
        .append(" -> ")
        .append(rule.targetRef())
        .append(" writes ")
        .append(rule.targetPath());
    if (!rule.sourcePath().isBlank()) {
      text.append(" from ").append(rule.sourcePath());
    }
    text.append(" with status ").append(rule.status());
    if (rule.expression() != null) {
      text.append(" expression ").append(rule.expression());
    }
    if (rule.status() == MappingRuleStatus.UNRESOLVED) {
      text.append(
          ". Required target "
              + rule.targetPath()
              + " remains unresolved on "
              + rule.mappingIntentId());
    }
    text.append('.');
    return text.toString();
  }
}
