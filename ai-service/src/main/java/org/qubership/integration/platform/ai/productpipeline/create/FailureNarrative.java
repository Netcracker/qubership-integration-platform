package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.llm.agent.OwnerDiagnosisDraft;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/**
 * Model-authored halt-card body from structured evidence. Empty when the turn fails; callers keep
 * Retry and the raw evidence instead of a canned sentence.
 */
public final class FailureNarrative {

  private static final Logger LOG = Logger.getLogger(FailureNarrative.class);

  private final FailureNarrativeAgent agent;

  public FailureNarrative(FailureNarrativeAgent agent) {
    this.agent = agent;
  }

  /** Test / runtime helper without LLM; narrate returns empty so the caller keeps raw evidence. */
  public FailureNarrative() {
    this(null);
  }

  /**
   * Asks the model to explain the halt. Empty when there is no agent, the call fails, or the reply
   * is blank. Never a fallback marketing sentence.
   */
  public Optional<String> narrate(
      String responseLocale,
      String stageId,
      StageOutcomeClass outcomeClass,
      String exceptionMessage,
      String validationFindings) {
    return narrate(
        responseLocale, stageId, outcomeClass, exceptionMessage, validationFindings, "");
  }

  public Optional<String> narrate(
      String responseLocale,
      String stageId,
      StageOutcomeClass outcomeClass,
      String exceptionMessage,
      String validationFindings,
      String followUpText) {
    if (agent == null) {
      return Optional.empty();
    }
    String locale = normalizedLocale(responseLocale);
    String stage = stageId == null || stageId.isBlank() ? "" : stageId.trim();
    String outcome = outcomeClass == null ? "" : outcomeClass.name();
    String exception = exceptionMessage == null ? "" : exceptionMessage;
    String findings = optionalField(validationFindings);
    String followUp = optionalField(followUpText);
    try {
      String authored = agent.narrate(locale, stage, outcome, exception, findings, followUp);
      if (authored != null && !authored.isBlank()) {
        return Optional.of(authored.trim());
      }
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Failure narrative LLM failed; keeping raw evidence");
    }
    return Optional.empty();
  }

  /**
   * Same turn as the halt narrative, plus an owner from {@code candidates}. An owner outside the
   * set is dropped; the narrative is kept. Empty owner when there is no agent or the call fails.
   */
  public OwnerDiagnosis diagnose(
      String responseLocale,
      String stageId,
      StageOutcomeClass outcomeClass,
      String exceptionMessage,
      String validationFindings,
      List<OwnerCandidate> candidates,
      String followUpText) {
    List<OwnerCandidate> closed = candidates == null ? List.of() : List.copyOf(candidates);
    if (agent == null) {
      return OwnerDiagnosis.none("");
    }
    String locale = normalizedLocale(responseLocale);
    String stage = stageId == null || stageId.isBlank() ? "" : stageId.trim();
    String outcome = outcomeClass == null ? "" : outcomeClass.name();
    String exception = exceptionMessage == null ? "" : exceptionMessage;
    String findings = optionalField(validationFindings);
    String followUp = optionalField(followUpText);
    String candidateSet = OwnerCandidateSet.format(closed);
    try {
      OwnerDiagnosisDraft draft =
          agent.diagnose(locale, stage, outcome, exception, findings, candidateSet, followUp);
      return fromDraft(draft, closed);
    } catch (RuntimeException ex) {
      LOG.warnf(ex, "Owner diagnosis LLM failed; keeping raw evidence");
      return OwnerDiagnosis.none("");
    }
  }

  /** Formats validation findings for the narrative turn; empty when none are present. */
  public static String findingsText(List<ArtifactCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (ArtifactCandidate candidate : candidates) {
      appendFindings(lines, candidate);
    }
    return String.join("\n", lines);
  }

  private static OwnerDiagnosis fromDraft(
      OwnerDiagnosisDraft draft, List<OwnerCandidate> candidates) {
    if (draft == null) {
      return OwnerDiagnosis.none("");
    }
    String narrative = draft.narrative() == null ? "" : draft.narrative().trim();
    if (draft.ambiguous()) {
      return OwnerDiagnosis.ask(narrative);
    }
    String owner = draft.ownerStageId() == null ? "" : draft.ownerStageId().trim();
    if (owner.isBlank()) {
      return OwnerDiagnosis.none(narrative);
    }
    if (!OwnerCandidateSet.containsStage(candidates, owner)) {
      return OwnerDiagnosis.none(narrative);
    }
    return OwnerDiagnosis.of(narrative, owner);
  }

  private static void appendFindings(List<String> lines, ArtifactCandidate candidate) {
    if (candidate == null || !(candidate.payload() instanceof PlanValidationResult result)) {
      return;
    }
    for (PlanValidationFinding finding : result.findings()) {
      if (finding != null) {
        lines.add(formatFinding(finding));
      }
    }
  }

  private static String formatFinding(PlanValidationFinding finding) {
    String code = finding.code() == null ? "" : finding.code();
    String message = finding.message() == null ? "" : finding.message();
    return code + ": " + message + (finding.blocker() ? " (blocker)" : "");
  }

  private static String optionalField(String value) {
    return value == null || value.isBlank() ? "(none)" : value.trim();
  }

  private static String normalizedLocale(String responseLocale) {
    return responseLocale == null || responseLocale.isBlank()
        ? ResponseLocaleResolver.DEFAULT_LOCALE
        : responseLocale.trim();
  }
}
