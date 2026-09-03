package org.qubership.integration.platform.ai.plan;

import dev.langchain4j.service.output.OutputParsingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.MappingTurnAgent;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.IntentChange;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.Kind;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.QuerySelector;
import org.qubership.integration.platform.ai.plan.MappingTurnCapture.RuleChange;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;

/**
 * Turns an author message into a typed {@link MappingTurnResult}. The model returns
 * {@link MappingTurnCapture}; this class resolves friendly names to approved interaction ids.
 */
@ApplicationScoped
public class MappingTurnInterpreter implements MappingTurnAdapter {

  private static final Logger LOG = Logger.getLogger(MappingTurnInterpreter.class);

  private final MappingTurnAgent agent;

  @Inject
  public MappingTurnInterpreter(MappingTurnAgent agent) {
    this.agent = Objects.requireNonNull(agent, "agent");
  }

  @Override
  public MappingTurnResult interpret(RequirementBrief brief, String authorMessage) {
    Objects.requireNonNull(brief, "brief");
    if (authorMessage == null || authorMessage.isBlank()) {
      return MappingTurnResult.changes();
    }
    MappingTurnCapture capture;
    try {
      capture =
          agent.interpret(renderFlow(brief.flow()), renderIntents(brief), authorMessage.trim());
    } catch (OutputParsingException e) {
      LOG.warnf(e, "Mapping turn capture could not be parsed; treating as no change");
      return MappingTurnResult.changes();
    }
    return fromCapture(capture, brief);
  }

  MappingTurnResult fromCapture(MappingTurnCapture capture, RequirementBrief brief) {
    RequirementFlow flow = brief == null ? null : brief.flow();
    List<MappingIntent> intents = brief == null ? List.of() : brief.mappingIntents();
    return fromCapture(capture, flow, intents);
  }

  MappingTurnResult fromCapture(MappingTurnCapture capture, RequirementFlow flow) {
    return fromCapture(capture, flow, List.of());
  }

  private MappingTurnResult fromCapture(
      MappingTurnCapture capture, RequirementFlow flow, List<MappingIntent> intents) {
    if (capture == null || capture.outcome() == Kind.NONE) {
      return MappingTurnResult.changes();
    }
    if (capture.outcome() == Kind.CLARIFICATION) {
      String reason =
          capture.clarificationReason().isBlank()
              ? "AMBIGUOUS_TRANSITION"
              : capture.clarificationReason();
      return new Clarification(reason, capture.candidates());
    }
    if (capture.outcome() == Kind.QUERY) {
      return queryFromCapture(capture, flow);
    }
    List<MappingTurnResult.Operation> operations = new ArrayList<>();
    List<String> ambiguous = new ArrayList<>();
    Optional<Clarification> clarification =
        addIntentOperations(capture, flow, operations, ambiguous);
    if (clarification.isPresent()) {
      return clarification.get();
    }
    if (!ambiguous.isEmpty() && operations.isEmpty()) {
      return new Clarification("AMBIGUOUS_TRANSITION", List.copyOf(ambiguous));
    }
    Optional<Clarification> ruleClarification =
        addRuleOperations(capture, flow, intents, operations);
    if (ruleClarification.isPresent()) {
      return ruleClarification.get();
    }
    Optional<Clarification> updateClarification =
        updateRuleOperations(capture, flow, intents, operations);
    if (updateClarification.isPresent()) {
      return updateClarification.get();
    }
    Optional<Clarification> deleteRuleClarification =
        deleteRuleOperations(capture, flow, intents, operations);
    if (deleteRuleClarification.isPresent()) {
      return deleteRuleClarification.get();
    }
    Optional<Clarification> deleteIntentClarification =
        deleteIntentOperations(capture, flow, intents, operations);
    if (deleteIntentClarification.isPresent()) {
      return deleteIntentClarification.get();
    }
    return new MappingTurnResult.Changes(operations);
  }

  private static MappingTurnResult queryFromCapture(
      MappingTurnCapture capture, RequirementFlow flow) {
    QuerySelector query = capture.query();
    ResolvedRef source = resolveOptionalRef(flow, query.sourceRef());
    ResolvedRef target = resolveOptionalRef(flow, query.targetRef());
    if (source.ambiguous() || target.ambiguous()) {
      List<String> candidates = new ArrayList<>();
      candidates.addAll(source.candidates());
      candidates.addAll(target.candidates());
      return new Clarification("AMBIGUOUS_TRANSITION", candidates);
    }
    if (missingNamedRef(query.sourceRef(), source) || missingNamedRef(query.targetRef(), target)) {
      List<String> missing = new ArrayList<>();
      if (missingNamedRef(query.sourceRef(), source)) {
        missing.add(query.sourceRef());
      }
      if (missingNamedRef(query.targetRef(), target)) {
        missing.add(query.targetRef());
      }
      return new Clarification("MISSING_TRANSITION", missing);
    }
    MappingQuerySelector.Coverage coverage = coverageOf(query.coverage());
    return new Query(
        new MappingQuerySelector(
            query.mappingIntentId(),
            source.id().isBlank() ? query.sourceRef() : source.id(),
            target.id().isBlank() ? query.targetRef() : target.id(),
            query.sourcePath(),
            query.targetPath(),
            query.unresolvedOnly(),
            coverage));
  }

  private static MappingQuerySelector.Coverage coverageOf(String coverage) {
    if (coverage == null || coverage.isBlank()) {
      return MappingQuerySelector.Coverage.ANY;
    }
    String normalized = coverage.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    if ("MAPPED".equals(normalized)) {
      return MappingQuerySelector.Coverage.MAPPED;
    }
    if ("PASS_THROUGH".equals(normalized) || "PASSTHROUGH".equals(normalized)) {
      return MappingQuerySelector.Coverage.PASS_THROUGH;
    }
    return MappingQuerySelector.Coverage.ANY;
  }

  private static boolean missingNamedRef(String raw, ResolvedRef resolved) {
    return raw != null && !raw.isBlank() && resolved.id().isBlank() && resolved.candidates().isEmpty();
  }

  private static ResolvedRef resolveOptionalRef(RequirementFlow flow, String raw) {
    if (raw == null || raw.isBlank()) {
      return ResolvedRef.missing();
    }
    return resolveRef(flow, raw);
  }

  private static Optional<Clarification> addIntentOperations(
      MappingTurnCapture capture,
      RequirementFlow flow,
      List<MappingTurnResult.Operation> operations,
      List<String> ambiguous) {
    for (IntentChange change : capture.addIntents()) {
      ResolvedRef source = resolveRef(flow, change.sourceRef());
      ResolvedRef target = resolveRef(flow, change.targetRef());
      if (source.ambiguous() || target.ambiguous()) {
        ambiguous.addAll(source.candidates());
        ambiguous.addAll(target.candidates());
        continue;
      }
      if (source.id().isBlank() || target.id().isBlank()) {
        return Optional.of(
            new Clarification(
                "MISSING_TRANSITION", List.of(change.sourceRef(), change.targetRef())));
      }
      operations.add(
          new AddIntent(
              source.id(), target.id(), change.rules(), change.implementationPreference()));
    }
    return Optional.empty();
  }

  private static Optional<Clarification> addRuleOperations(
      MappingTurnCapture capture,
      RequirementFlow flow,
      List<MappingIntent> intents,
      List<MappingTurnResult.Operation> operations) {
    for (RuleChange change : capture.addRules()) {
      Optional<ResolvedIntent> resolved = resolveIntent(change, flow, intents);
      if (resolved.isPresent() && resolved.get().clarification() != null) {
        return Optional.of(resolved.get().clarification());
      }
      String intentId =
          resolved.isPresent() ? resolved.get().mappingIntentId() : change.mappingIntentId();
      if (intentId.isBlank() || change.targetPath().isBlank()) {
        continue;
      }
      operations.add(
          new AddRule(intentId, change.sourcePath(), change.targetPath(), change.expression()));
    }
    return Optional.empty();
  }

  private static Optional<Clarification> updateRuleOperations(
      MappingTurnCapture capture,
      RequirementFlow flow,
      List<MappingIntent> intents,
      List<MappingTurnResult.Operation> operations) {
    for (RuleChange change : capture.updateRules()) {
      Optional<ResolvedIntent> resolved = resolveIntent(change, flow, intents);
      if (resolved.isPresent() && resolved.get().clarification() != null) {
        return Optional.of(resolved.get().clarification());
      }
      String intentId =
          resolved.isPresent() ? resolved.get().mappingIntentId() : change.mappingIntentId();
      if (intentId.isBlank() || change.targetPath().isBlank()) {
        continue;
      }
      MappingIntent intent = byId(intents, intentId);
      String sourcePath = change.sourcePath();
      String expression = change.expression();
      if (intent != null) {
        MappingIntentRule current = uniqueRule(intent, change.targetPath());
        if (current == null) {
          List<MappingIntentRule> matches = matchingRules(intent, change.targetPath());
          String reason = matches.isEmpty() ? "ZERO_MATCH" : "MULTI_MATCH";
          return Optional.of(
              new Clarification(reason, List.of(MappingTurnProcessor.selector(intentId, change.targetPath()))));
        }
        if (sourcePath.isBlank() && !quotedConstant(change.sourcePath())) {
          sourcePath = current.sourcePath();
        }
        if (change.expression() == null && !quotedConstant(change.sourcePath()) && change.sourcePath().isBlank()) {
          expression = current.expression();
        }
        if (quotedConstant(change.sourcePath())) {
          expression = change.expression();
        }
      }
      operations.add(
          new UpdateRule(
              intentId, change.targetPath(), sourcePath, change.newTargetPath(), expression));
    }
    return Optional.empty();
  }

  private static Optional<Clarification> deleteRuleOperations(
      MappingTurnCapture capture,
      RequirementFlow flow,
      List<MappingIntent> intents,
      List<MappingTurnResult.Operation> operations) {
    for (RuleChange change : capture.deleteRules()) {
      Optional<ResolvedIntent> resolved = resolveIntent(change, flow, intents);
      if (resolved.isPresent() && resolved.get().clarification() != null) {
        return Optional.of(resolved.get().clarification());
      }
      String intentId =
          resolved.isPresent() ? resolved.get().mappingIntentId() : change.mappingIntentId();
      if (intentId.isBlank() || change.targetPath().isBlank()) {
        continue;
      }
      operations.add(new DeleteRule(intentId, change.targetPath()));
    }
    return Optional.empty();
  }

  private static Optional<Clarification> deleteIntentOperations(
      MappingTurnCapture capture,
      RequirementFlow flow,
      List<MappingIntent> intents,
      List<MappingTurnResult.Operation> operations) {
    for (IntentChange change : capture.deleteIntents()) {
      ResolvedRef source = resolveRef(flow, change.sourceRef());
      ResolvedRef target = resolveRef(flow, change.targetRef());
      if (source.ambiguous() || target.ambiguous()) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(source.candidates());
        candidates.addAll(target.candidates());
        return Optional.of(new Clarification("AMBIGUOUS_TRANSITION", candidates));
      }
      MappingIntent intent = intentAt(intents, source.id(), target.id());
      if (intent == null) {
        return Optional.of(
            new Clarification(
                "ZERO_MATCH", List.of(change.sourceRef(), change.targetRef())));
      }
      operations.add(new DeleteIntent(intent.mappingIntentId()));
    }
    return Optional.empty();
  }

  private static Optional<ResolvedIntent> resolveIntent(
      RuleChange change, RequirementFlow flow, List<MappingIntent> intents) {
    if (!change.mappingIntentId().isBlank()) {
      if (byId(intents, change.mappingIntentId()) != null || intents.isEmpty()) {
        return Optional.of(new ResolvedIntent(change.mappingIntentId(), null));
      }
      return Optional.of(
          new ResolvedIntent(
              "",
              new Clarification(
                  "ZERO_MATCH",
                  List.of(
                      MappingTurnProcessor.selector(
                          change.mappingIntentId(), change.targetPath())))));
    }
    if (!change.sourceRef().isBlank() || !change.targetRef().isBlank()) {
      ResolvedRef source = resolveRef(flow, change.sourceRef());
      ResolvedRef target = resolveRef(flow, change.targetRef());
      if (source.ambiguous() || target.ambiguous()) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(source.candidates());
        candidates.addAll(target.candidates());
        return Optional.of(new ResolvedIntent("", new Clarification("AMBIGUOUS_TRANSITION", candidates)));
      }
      MappingIntent intent = intentAt(intents, source.id(), target.id());
      if (intent == null) {
        return Optional.of(
            new ResolvedIntent(
                "",
                new Clarification(
                    "OMITTED_TRANSITION", List.of(change.sourceRef(), change.targetRef()))));
      }
      return Optional.of(new ResolvedIntent(intent.mappingIntentId(), null));
    }
    if (intents.size() == 1) {
      return Optional.of(new ResolvedIntent(intents.getFirst().mappingIntentId(), null));
    }
    if (intents.size() > 1 && !change.targetPath().isBlank()) {
      List<String> candidates = new ArrayList<>();
      for (MappingIntent intent : intents) {
        if (!matchingRules(intent, change.targetPath()).isEmpty()) {
          candidates.add(MappingTurnProcessor.selector(intent.mappingIntentId(), change.targetPath()));
        }
      }
      if (candidates.size() != 1) {
        if (candidates.isEmpty()) {
          for (MappingIntent intent : intents) {
            candidates.add(intent.mappingIntentId());
          }
        }
        return Optional.of(
            new ResolvedIntent("", new Clarification("OMITTED_TRANSITION", candidates)));
      }
      String intentId = candidates.getFirst().substring(0, candidates.getFirst().indexOf(':'));
      return Optional.of(new ResolvedIntent(intentId, null));
    }
    return Optional.empty();
  }

  private static MappingIntentRule uniqueRule(MappingIntent intent, String targetPath) {
    List<MappingIntentRule> matches = matchingRules(intent, targetPath);
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private static List<MappingIntentRule> matchingRules(MappingIntent intent, String targetPath) {
    String canonical = MappingContract.canonicalPath(targetPath);
    List<MappingIntentRule> matches = new ArrayList<>();
    for (MappingIntentRule rule : intent.rules()) {
      if (canonical.equals(MappingContract.canonicalPath(rule.targetPath()))) {
        matches.add(rule);
      }
    }
    return matches;
  }

  private static boolean quotedConstant(String sourcePath) {
    if (sourcePath == null || sourcePath.length() < 2) {
      return false;
    }
    String trimmed = sourcePath.trim();
    return trimmed.startsWith("\"") && trimmed.endsWith("\"");
  }

  private static MappingIntent byId(List<MappingIntent> intents, String mappingIntentId) {
    for (MappingIntent intent : intents) {
      if (intent.mappingIntentId().equals(mappingIntentId)) {
        return intent;
      }
    }
    return null;
  }

  private static MappingIntent intentAt(
      List<MappingIntent> intents, String sourceRef, String targetRef) {
    for (MappingIntent intent : intents) {
      if (sourceRef.equals(intent.sourceRef()) && targetRef.equals(intent.targetRef())) {
        return intent;
      }
    }
    return null;
  }

  static String renderFlow(RequirementFlow flow) {
    if (flow == null || flow.interactions().isEmpty()) {
      return "(none)";
    }
    StringBuilder sb = new StringBuilder();
    for (Interaction interaction : flow.interactions()) {
      sb.append("- interactionId=")
          .append(interaction.interactionId())
          .append(" participant=")
          .append(interaction.participant())
          .append(" operation=")
          .append(interaction.operation())
          .append('\n');
    }
    if (!flow.transitions().isEmpty()) {
      sb.append("Transitions:\n");
      for (var transition : flow.transitions()) {
        sb.append("- ")
            .append(transition.sourceInteractionId())
            .append(" -> ")
            .append(transition.targetInteractionId())
            .append('\n');
      }
    }
    return sb.toString();
  }

  static String renderIntents(RequirementBrief brief) {
    if (brief == null || brief.mappingIntents().isEmpty()) {
      return "(none)";
    }
    StringBuilder sb = new StringBuilder();
    for (MappingIntent intent : brief.mappingIntents()) {
      sb.append("- mappingIntentId=")
          .append(intent.mappingIntentId())
          .append(" ")
          .append(intent.sourceRef())
          .append(" -> ")
          .append(intent.targetRef())
          .append(" rules=");
      for (int i = 0; i < intent.rules().size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(intent.rules().get(i).targetPath());
      }
      if (intent.implementationPreference() != null) {
        sb.append(" preference=").append(intent.implementationPreference());
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  static ResolvedRef resolveRef(RequirementFlow flow, String raw) {
    if (flow == null || raw == null || raw.isBlank()) {
      return ResolvedRef.missing();
    }
    String wanted = raw.trim();
    if (flow.interaction(wanted).isPresent()) {
      return ResolvedRef.of(wanted);
    }
    List<String> operationMatches = new ArrayList<>();
    List<String> participantMatches = new ArrayList<>();
    for (Interaction interaction : flow.interactions()) {
      if (wanted.equalsIgnoreCase(interaction.operation())) {
        operationMatches.add(interaction.interactionId());
      }
      if (wanted.equalsIgnoreCase(interaction.participant())
          || wanted.equalsIgnoreCase(
              interaction.participant() + " " + interaction.operation())) {
        participantMatches.add(interaction.interactionId());
      }
    }
    if (operationMatches.size() == 1) {
      return ResolvedRef.of(operationMatches.getFirst());
    }
    if (operationMatches.size() > 1) {
      return ResolvedRef.ambiguous(operationMatches);
    }
    if (participantMatches.size() == 1) {
      return ResolvedRef.of(participantMatches.getFirst());
    }
    if (participantMatches.size() > 1) {
      return ResolvedRef.ambiguous(participantMatches);
    }
    return ResolvedRef.missing();
  }

  record ResolvedRef(String id, List<String> candidates) {
    static ResolvedRef of(String id) {
      return new ResolvedRef(id, List.of());
    }

    static ResolvedRef missing() {
      return new ResolvedRef("", List.of());
    }

    static ResolvedRef ambiguous(List<String> candidates) {
      return new ResolvedRef("", List.copyOf(candidates));
    }

    boolean ambiguous() {
      return id.isBlank() && !candidates.isEmpty();
    }
  }

  private record ResolvedIntent(String mappingIntentId, Clarification clarification) {}
}
