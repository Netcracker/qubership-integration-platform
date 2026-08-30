package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Validates a structured recovery decision against the recovery context. */
public final class RecoveryDecisionValidator {

  private static final List<String> STAGE_ID_SUBSTRINGS =
      List.of(
          "requirement-analysis",
          "design-execution",
          "design-planning",
          "requirement-discovery",
          "__owner_candidates__",
          "go back to");

  private RecoveryDecisionValidator() {}

  public record Result(boolean accepted, List<String> findings) {}

  public static Result validate(RecoveryDecision decision, RecoveryContext context) {
    if (decision == null || context == null) {
      return new Result(false, List.of("Missing decision or context."));
    }
    List<String> findings = new ArrayList<>();
    RecoveryEvidence evidence = context.evidence();
    if (evidence == null) {
      findings.add("Missing recovery evidence.");
      return new Result(false, List.copyOf(findings));
    }

    if (decision.causeClass() == null) {
      findings.add("Missing cause class.");
    }
    if (decision.action() == null) {
      findings.add("Missing recovery action.");
    }
    if (!findings.isEmpty()) {
      return new Result(false, List.copyOf(findings));
    }

    validateAllowedAction(decision, findings);
    validateEvidenceRefs(decision, evidence, findings);
    validateFaultArtifactRef(decision, evidence, findings);
    validateActionSpecificRules(decision, evidence, findings);
    validateUserSummary(decision, findings);

    return new Result(findings.isEmpty(), List.copyOf(findings));
  }

  private static void validateAllowedAction(RecoveryDecision decision, List<String> findings) {
    boolean allowed =
        switch (decision.causeClass()) {
          case BRIEF_DEFECT ->
              decision.action() == RecoveryAction.REVISE_BRIEF
                  || decision.action() == RecoveryAction.ASK_USER;
          case DERIVATION_DEFECT ->
              decision.action() == RecoveryAction.REGENERATE_ARTIFACT
                  || decision.action() == RecoveryAction.PARK;
          case TECHNICAL_FAILURE ->
              decision.action() == RecoveryAction.RETRY_OPERATION
                  || decision.action() == RecoveryAction.PARK;
          case UNCLASSIFIED -> decision.action() == RecoveryAction.PARK;
        };
    if (!allowed) {
      findings.add(
          "Action "
              + decision.action()
              + " is not allowed for cause class "
              + decision.causeClass()
              + ".");
    }
  }

  private static void validateEvidenceRefs(
      RecoveryDecision decision, RecoveryEvidence evidence, List<String> findings) {
    if (decision.evidenceRefs().isEmpty()) {
      findings.add("Missing evidence references.");
      return;
    }
    Set<String> knownIds = knownEvidenceIds(evidence);
    for (String evidenceRef : decision.evidenceRefs()) {
      if (evidenceRef == null || evidenceRef.isBlank() || !knownIds.contains(evidenceRef)) {
        findings.add("Evidence reference is not present in the recovery context: " + evidenceRef);
      }
    }
  }

  private static Set<String> knownEvidenceIds(RecoveryEvidence evidence) {
    Set<String> ids = new HashSet<>();
    ids.add(evidence.failureId());
    for (SemanticFinding finding : evidence.findings()) {
      if (finding != null && finding.occurrenceId() != null && !finding.occurrenceId().isBlank()) {
        ids.add(finding.occurrenceId());
      }
    }
    return ids;
  }

  private static void validateFaultArtifactRef(
      RecoveryDecision decision, RecoveryEvidence evidence, List<String> findings) {
    Reference fault = decision.faultArtifactRef();
    if (decision.action() == RecoveryAction.PARK) {
      if (fault != null && !knownArtifactRefs(evidence).contains(fault)) {
        findings.add("Fault artifact reference is not present in the recovery context.");
      }
      return;
    }
    if (fault == null) {
      findings.add("Fault artifact reference is required for action " + decision.action() + ".");
      return;
    }
    if (!knownArtifactRefs(evidence).contains(fault)) {
      findings.add("Fault artifact reference is not present in the recovery context.");
    }
  }

  private static Set<Reference> knownArtifactRefs(RecoveryEvidence evidence) {
    Set<Reference> refs = new HashSet<>();
    if (evidence.approvedBriefRef() != null) {
      refs.add(evidence.approvedBriefRef());
    }
    if (evidence.approvedSemanticRef() != null) {
      refs.add(evidence.approvedSemanticRef());
    }
    refs.addAll(evidence.rejectedArtifactRefs());
    return refs;
  }

  private static void validateActionSpecificRules(
      RecoveryDecision decision, RecoveryEvidence evidence, List<String> findings) {
    Reference fault = decision.faultArtifactRef();
    Reference approvedBrief = evidence.approvedBriefRef();

    if (decision.action() == RecoveryAction.REVISE_BRIEF) {
      if (fault == null) {
        return;
      }
      boolean targetsBrief =
          fault.kind() == Kind.REQUIREMENT_BRIEF
              || (approvedBrief != null && approvedBrief.equals(fault));
      if (!targetsBrief) {
        findings.add("REVISE_BRIEF must target the approved brief.");
      }
    }

    if (decision.action() == RecoveryAction.REGENERATE_ARTIFACT) {
      if (fault != null && approvedBrief != null && approvedBrief.equals(fault)) {
        findings.add("REGENERATE_ARTIFACT must not target the approved brief.");
      }
    }

    if (decision.action() == RecoveryAction.ASK_USER) {
      String question = decision.question() == null ? "" : decision.question().trim();
      if (question.isEmpty()) {
        findings.add("ASK_USER requires a product question.");
      }
      rejectStageIds(decision.question(), "ASK_USER question", findings);
    }

    if (decision.action() == RecoveryAction.RETRY_OPERATION) {
      TechnicalFailureRecord technicalFailure = evidence.technicalFailure();
      if (technicalFailure == null || !technicalFailure.retryable()) {
        findings.add(
            "RETRY_OPERATION requires a retryable technical failure; park instead when the"
                + " side-effect is unknown.");
      }
    }
  }

  private static void validateUserSummary(RecoveryDecision decision, List<String> findings) {
    rejectStageIds(decision.userSummary(), "userSummary", findings);
  }

  private static void rejectStageIds(String text, String field, List<String> findings) {
    if (text == null || text.isBlank()) {
      return;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    for (String substring : STAGE_ID_SUBSTRINGS) {
      if (lower.contains(substring)) {
        findings.add(field + " must not contain stage identifiers.");
        return;
      }
    }
  }
}
