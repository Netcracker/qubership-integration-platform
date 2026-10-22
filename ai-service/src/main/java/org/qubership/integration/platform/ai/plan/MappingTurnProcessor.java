package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.ConfirmationRequired;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapPassThroughConfirmation.TransitionRef;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/**
 * Conversation seam for one mapping turn: current requirement brief plus one author message.
 * Interpretation is typed. Queries are answered from the stored brief. Apply goes through
 * {@link MappingTurnApplicator}.
 */
public final class MappingTurnProcessor {

  private MappingTurnProcessor() {}

  public static MappingTurnApplication process(
      RequirementBrief brief, String authorMessage, MappingTurnAdapter adapter) {
    return process(brief, authorMessage, adapter, null);
  }

  public static MappingTurnApplication process(
      RequirementBrief brief,
      String authorMessage,
      MappingTurnAdapter adapter,
      MappingTurnTelemetry telemetry) {
    Objects.requireNonNull(brief, "brief");
    Objects.requireNonNull(adapter, "adapter");
    long startNs = System.nanoTime();
    String message = authorMessage == null ? "" : authorMessage;
    MappingTurnApplication application = processMessage(brief, message, adapter);
    MappingTurnResult recorded =
        application.result() != null ? application.result() : MappingTurnResult.changes();
    if (telemetry != null) {
      telemetry.record(
          recorded, application, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
    }
    return application;
  }

  /**
   * Mapping-gap describe turn: persist each valid ADD_INTENT, omit empty-rule, unapproved, and
   * undescribed hops, and record coverage remainder telemetry. Hash confirmation is not coverage.
   */
  public static MappingTurnApplication processGap(
      RequirementBrief brief,
      String authorMessage,
      MappingTurnAdapter adapter,
      MappingTurnTelemetry telemetry) {
    Objects.requireNonNull(brief, "brief");
    Objects.requireNonNull(adapter, "adapter");
    long startNs = System.nanoTime();
    String message = authorMessage == null ? "" : authorMessage;
    MappingTurnResult result = adapter.interpretGap(brief, message);
    MappingTurnApplication application = applyGapInterpreted(brief, result);
    MappingTurnResult recorded =
        application.result() != null ? application.result() : MappingTurnResult.changes();
    if (telemetry != null) {
      int remainder = MappingGapCoverage.uncovered(application.brief()).size();
      telemetry.record(
          recorded,
          application,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
          appliedHopCount(brief, application.brief()),
          omittedHopCount(recorded, application.brief()),
          remainder,
          remainder == 0 ? "LEAVE" : "STAY");
    }
    return application;
  }

  private static MappingTurnApplication applyGapInterpreted(
      RequirementBrief brief, MappingTurnResult result) {
    if (result instanceof Query(var selector)) {
      return new MappingTurnApplication(
          brief, false, MappingQueryLookup.answer(brief, selector), result);
    }
    if (result instanceof Clarification || result instanceof ConfirmationRequired) {
      return MappingTurnApplication.rejected(brief, result);
    }
    if (!(result instanceof MappingTurnResult.Changes(var operations)) || operations.isEmpty()) {
      return MappingTurnApplication.rejected(brief, result);
    }
    List<MappingTurnResult.Operation> keep = gapOperations(operations);
    if (keep.isEmpty()) {
      return MappingTurnApplication.rejected(brief, result);
    }
    MappingTurnApplication application =
        MappingTurnApplicator.applyValid(brief, new MappingTurnResult.Changes(keep));
    return new MappingTurnApplication(
        application.brief(), application.applied(), application.answer(), result);
  }

  /** Omits empty-rule hops; the interpreter already dropped hops the author never described. */
  private static List<MappingTurnResult.Operation> gapOperations(
      List<MappingTurnResult.Operation> operations) {
    List<MappingTurnResult.Operation> keep = new ArrayList<>();
    for (MappingTurnResult.Operation operation : operations) {
      if (operation instanceof AddIntent add && add.rules().isEmpty()) {
        continue;
      }
      keep.add(operation);
    }
    return keep;
  }

  private static int appliedHopCount(RequirementBrief before, RequirementBrief after) {
    return Math.max(0, after.mappingIntents().size() - before.mappingIntents().size());
  }

  private static int omittedHopCount(
      MappingTurnResult result, RequirementBrief after) {
    if (!(result instanceof MappingTurnResult.Changes(var operations))) {
      return 0;
    }
    int omitted = 0;
    for (MappingTurnResult.Operation operation : operations) {
      if (operation instanceof AddIntent add
          && intentAt(after, add.sourceRef(), add.targetRef()) == null) {
        omitted++;
      }
    }
    return omitted;
  }

  /** Formats a mapping-intent id and target path for clarification candidates. */
  public static String selector(String mappingIntentId, String targetPath) {
    String id = mappingIntentId == null ? "" : mappingIntentId;
    if (targetPath == null || targetPath.isBlank()) {
      return id;
    }
    return id + ":" + targetPath;
  }

  /** Stable pin for the mapping-relevant brief revision an interpretation was made against. */
  public static String revisionOf(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    StringBuilder sb = new StringBuilder();
    for (var transition : brief.flow().transitions()) {
      sb.append(transition.sourceInteractionId())
          .append('>')
          .append(transition.targetInteractionId())
          .append('|');
    }
    for (MappingIntent intent : brief.mappingIntents()) {
      sb.append(intent.mappingIntentId())
          .append('@')
          .append(intent.sourceRef())
          .append('>')
          .append(intent.targetRef())
          .append('#');
      for (MappingIntentRule rule : intent.rules()) {
        sb.append(MappingContract.canonicalPath(rule.sourcePath()))
            .append('>')
            .append(MappingContract.canonicalPath(rule.targetPath()))
            .append('=')
            .append(rule.expression())
            .append('/')
            .append(rule.status())
            .append(';');
      }
    }
    return Integer.toUnsignedString(sb.toString().hashCode());
  }

  /**
   * Applies a previously interpreted result when it is still pinned to {@code brief}. A stale pin
   * is ignored and the author message is interpreted against the latest brief.
   */
  public static MappingTurnApplication applyResult(
      RequirementBrief brief,
      MappingTurnResult result,
      String expectedRevision,
      String authorMessage,
      MappingTurnAdapter adapter) {
    Objects.requireNonNull(brief, "brief");
    Objects.requireNonNull(adapter, "adapter");
    if (!Objects.equals(expectedRevision, revisionOf(brief))) {
      return process(brief, authorMessage, adapter);
    }
    return applyInterpreted(brief, result);
  }

  private static MappingTurnApplication processMessage(
      RequirementBrief brief, String message, MappingTurnAdapter adapter) {
    Optional<MappingTurnApplication> confirmed = tryConfirmation(brief, message);
    if (confirmed.isPresent()) {
      return confirmed.get();
    }
    MappingTurnResult result = adapter.interpret(brief, message);
    return applyInterpreted(brief, result);
  }

  private static MappingTurnApplication applyInterpreted(
      RequirementBrief brief, MappingTurnResult result) {
    if (result instanceof Query(var selector)) {
      return new MappingTurnApplication(
          brief, false, MappingQueryLookup.answer(brief, selector), result);
    }
    return applyAuthorChanges(brief, result);
  }

  private static Optional<MappingTurnApplication> tryConfirmation(
      RequirementBrief brief, String message) {
    Optional<MappingGapPassThroughConfirmation> parsed =
        MappingGapPassThroughConfirmation.parse(message);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    MappingGapPassThroughConfirmation confirmation = parsed.get();
    if (!Objects.equals(confirmation.briefSha(), revisionOf(brief))) {
      return Optional.of(
          MappingTurnApplication.rejected(brief, new Clarification("STALE_REVISION", List.of())));
    }
    List<MappingTurnResult.Operation> deletes = new ArrayList<>();
    for (TransitionRef ref : confirmation.uncovered()) {
      MappingIntent intent = intentAt(brief, ref.sourceRef(), ref.targetRef());
      if (intent != null) {
        deletes.add(new DeleteIntent(intent.mappingIntentId()));
      }
    }
    if (deletes.isEmpty()) {
      return Optional.empty();
    }
    MappingTurnResult changes = new MappingTurnResult.Changes(deletes);
    MappingTurnApplication application = MappingTurnApplicator.apply(brief, changes);
    return Optional.of(
        new MappingTurnApplication(
            application.brief(), application.applied(), application.answer(), changes));
  }

  private static MappingTurnApplication applyAuthorChanges(
      RequirementBrief brief, MappingTurnResult result) {
    if (result instanceof Clarification || result instanceof ConfirmationRequired) {
      return MappingTurnApplication.rejected(brief, result);
    }
    if (!(result instanceof MappingTurnResult.Changes(var operations)) || operations.isEmpty()) {
      MappingTurnApplication application = MappingTurnApplicator.apply(brief, result);
      return new MappingTurnApplication(
          application.brief(), application.applied(), application.answer(), result);
    }
    MappingTurnResult gated = gateWrites(brief, operations);
    if (!(gated instanceof MappingTurnResult.Changes(var gatedOps))) {
      return MappingTurnApplication.rejected(brief, gated);
    }
    List<MappingTurnResult.Operation> remaining = remainingOperations(brief, gatedOps);
    if (remaining.isEmpty()) {
      return MappingTurnApplication.applied(brief, gated);
    }
    MappingTurnApplication application =
        MappingTurnApplicator.apply(brief, new MappingTurnResult.Changes(remaining));
    return new MappingTurnApplication(
        application.brief(), application.applied(), application.answer(), gated);
  }

  static MappingTurnResult gateWrites(
      RequirementBrief brief, List<MappingTurnResult.Operation> operations) {
    for (MappingTurnResult.Operation operation : operations) {
      Optional<MappingTurnResult> blocked = gateOne(brief, operation);
      if (blocked.isPresent()) {
        return blocked.get();
      }
    }
    return new MappingTurnResult.Changes(operations);
  }

  private static Optional<MappingTurnResult> gateOne(
      RequirementBrief brief, MappingTurnResult.Operation operation) {
    return switch (operation) {
      case DeleteIntent delete ->
          Optional.of(
              new ConfirmationRequired(
                  ConfirmationRequired.Kind.DELETE_INTENT, delete.mappingIntentId(), null));
      case DeleteRule delete -> gateDeleteRule(brief, delete);
      case AddRule add -> gateAddRule(brief, add);
      case UpdateRule update -> gateExactRule(brief, update.mappingIntentId(), update.targetPath());
      case AddIntent ignored -> Optional.empty();
    };
  }

  private static Optional<MappingTurnResult> gateDeleteRule(
      RequirementBrief brief, DeleteRule delete) {
    Optional<MappingTurnResult> match =
        gateExactRule(brief, delete.mappingIntentId(), delete.targetPath());
    if (match.isPresent()) {
      return match;
    }
    MappingIntent intent = byId(brief, delete.mappingIntentId());
    if (intent != null && activeRuleCount(intent) <= 1) {
      return Optional.of(
          new ConfirmationRequired(
              ConfirmationRequired.Kind.DELETE_LAST_RULE,
              delete.mappingIntentId(),
              delete.targetPath()));
    }
    return Optional.empty();
  }

  private static Optional<MappingTurnResult> gateAddRule(RequirementBrief brief, AddRule add) {
    MappingIntent intent = byId(brief, add.mappingIntentId());
    if (intent == null) {
      return Optional.of(
          new Clarification("ZERO_MATCH", List.of(selector(add.mappingIntentId(), add.targetPath()))));
    }
    List<MappingIntentRule> matches = matchingRules(intent, add.targetPath());
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (sameRule(matches.getFirst(), add.sourcePath(), add.targetPath(), add.expression())) {
      return Optional.empty();
    }
    return Optional.of(
        new Clarification(
            "TARGET_CONFLICT", List.of(selector(add.mappingIntentId(), add.targetPath()))));
  }

  private static Optional<MappingTurnResult> gateExactRule(
      RequirementBrief brief, String mappingIntentId, String targetPath) {
    MappingIntent intent = byId(brief, mappingIntentId);
    if (intent == null) {
      return Optional.of(
          new Clarification("ZERO_MATCH", List.of(selector(mappingIntentId, targetPath))));
    }
    List<MappingIntentRule> matches = matchingRules(intent, targetPath);
    if (matches.isEmpty()) {
      return Optional.of(
          new Clarification("ZERO_MATCH", List.of(selector(mappingIntentId, targetPath))));
    }
    if (matches.size() > 1) {
      return Optional.of(
          new Clarification("MULTI_MATCH", List.of(selector(mappingIntentId, targetPath))));
    }
    return Optional.empty();
  }

  private static List<MappingTurnResult.Operation> remainingOperations(
      RequirementBrief brief, List<MappingTurnResult.Operation> operations) {
    List<MappingTurnResult.Operation> remaining = new ArrayList<>();
    for (MappingTurnResult.Operation operation : operations) {
      if (!alreadyReflected(brief, operation)) {
        remaining.add(operation);
      }
    }
    return remaining;
  }

  private static boolean alreadyReflected(
      RequirementBrief brief, MappingTurnResult.Operation operation) {
    return switch (operation) {
      case AddIntent add -> intentAt(brief, add.sourceRef(), add.targetRef()) != null;
      case AddRule add -> {
        MappingIntent intent = byId(brief, add.mappingIntentId());
        yield intent != null
            && matchingRules(intent, add.targetPath()).stream()
                .anyMatch(
                    rule -> sameRule(rule, add.sourcePath(), add.targetPath(), add.expression()));
      }
      case UpdateRule update -> {
        MappingIntent intent = byId(brief, update.mappingIntentId());
        String target =
            update.newTargetPath() == null ? update.targetPath() : update.newTargetPath();
        yield intent != null
            && matchingRules(intent, target).stream()
                .anyMatch(
                    rule -> sameRule(rule, update.sourcePath(), target, update.expression()));
      }
      case DeleteRule delete -> {
        MappingIntent intent = byId(brief, delete.mappingIntentId());
        yield intent == null || matchingRules(intent, delete.targetPath()).isEmpty();
      }
      case DeleteIntent delete -> byId(brief, delete.mappingIntentId()) == null;
    };
  }

  private static boolean sameRule(
      MappingIntentRule rule, String sourcePath, String targetPath, String expression) {
    return MappingContract.canonicalPath(rule.sourcePath())
            .equals(MappingContract.canonicalPath(sourcePath))
        && MappingContract.canonicalPath(rule.targetPath())
            .equals(MappingContract.canonicalPath(targetPath))
        && Objects.equals(rule.expression(), expression);
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

  private static long activeRuleCount(MappingIntent intent) {
    long count = 0;
    for (MappingIntentRule rule : intent.rules()) {
      if (rule.status() != MappingRuleStatus.UNRESOLVED) {
        count++;
      }
    }
    return count;
  }

  private static MappingIntent byId(RequirementBrief brief, String mappingIntentId) {
    for (MappingIntent intent : brief.mappingIntents()) {
      if (intent.mappingIntentId().equals(mappingIntentId)) {
        return intent;
      }
    }
    return null;
  }

  private static MappingIntent intentAt(
      RequirementBrief brief, String sourceRef, String targetRef) {
    for (MappingIntent intent : brief.mappingIntents()) {
      if (sourceRef.equals(intent.sourceRef()) && targetRef.equals(intent.targetRef())) {
        return intent;
      }
    }
    return null;
  }
}
