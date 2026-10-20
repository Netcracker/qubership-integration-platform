package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;

/** Validates a structured recovery decision against the recovery context. */
public final class RecoveryDecisionValidator {

  private static final Set<String> GENERATED_CONTRACT_CAUSES = Set.of(
      RecoveryCauseCode.UNKNOWN_PROPERTY.name(),
      RecoveryCauseCode.MISSING_REQUIRED_PROPERTY.name(),
      RecoveryCauseCode.CONTRACT_SHAPE.name(),
      RecoveryCauseCode.GRAPH_STRUCTURE.name());

  private static final List<String> STAGE_ID_SUBSTRINGS =
      List.of(
          "requirement-analysis",
          "design-execution",
          "design-planning",
          "requirement-discovery",
          "__owner_candidates__",
          "go back to");

  private RecoveryDecisionValidator() {}

  public static final String SOURCE_RECONCILIATION_REQUIRED = "SOURCE_RECONCILIATION_REQUIRED";

  public record Result(boolean accepted, List<String> findings) {
    public boolean requiresSourceReconciliation() {
      return findings.size() == 1 && findings.getFirst().startsWith(SOURCE_RECONCILIATION_REQUIRED + ":");
    }
  }

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
    validateEvidenceRefs(decision, context, findings);
    validateFaultArtifactRef(decision, context, findings);
    validateActionSpecificRules(decision, evidence, findings);
    if (decision.action() == RecoveryAction.REVISE_BRIEF && context.approvedBrief() != null) {
      for (var change : decision.proposedBriefChanges()) {
        if (change.authorDecisionRequired()) {
          continue;
        }
        context.approvedBrief().facts().stream()
            .filter(fact -> fact.sourceFactId().equals(change.sourceFactId()))
            .map(RequirementFact::policyConflict)
            .filter(policy -> policy != null && policy.decision() != null
                && policy.propertyKey().equals(change.field())
                && !java.util.Objects.equals(policy.selectedValue(), change.proposedValue()))
            .forEach(policy -> findings.add("REVISE_BRIEF cannot silently replace a confirmed policy choice. Repair the generated output."));
      }
    }
    if (decision.action() == RecoveryAction.ASK_USER) {
      validateAuthorQuestion(decision.authorQuestionEvidence(), context, findings);
    }
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
      RecoveryDecision decision, RecoveryContext context, List<String> findings) {
    if (decision.evidenceRefs().isEmpty()) {
      findings.add("Missing evidence references.");
      return;
    }
    Set<String> knownIds = evidenceReferenceIds(context);
    for (String evidenceRef : decision.evidenceRefs()) {
      if (evidenceRef == null || evidenceRef.isBlank() || !knownIds.contains(evidenceRef)) {
        findings.add("Evidence reference is not present in the recovery context: " + evidenceRef);
      }
    }
  }

  static Set<String> evidenceReferenceIds(RecoveryContext context) {
    Set<String> ids = context.evidence() == null ? new HashSet<>() : knownEvidenceIds(context.evidence());
    if (context.evidence() != null) {
      knownArtifactRefs(context).forEach(ref -> {
        ids.add(ref.artifactId());
        ids.add(ref.contentHash());
      });
    }
    context.priorAttempts().forEach(attempt -> {
      ids.add(attempt.failureId());
      ids.add(attempt.evidenceRef().artifactId());
      ids.add(attempt.evidenceRef().contentHash());
      attempt.findings().forEach(finding -> ids.add(finding.occurrenceId()));
    });
    context.authorInputs().forEach(input -> ids.add(input.inputId()));
    if (context.approvedBrief() != null) {
      java.util.stream.Stream.concat(context.approvedBrief().facts().stream(), context.approvedBrief().requirements().stream())
          .forEach(fact -> ids.add(fact.sourceFactId()));
    }
    ids.removeIf(id -> id == null || id.isBlank());
    return ids;
  }

  private static Set<String> knownEvidenceIds(RecoveryEvidence evidence) {
    Set<String> ids = new HashSet<>();
    ids.add(evidence.failureId());
    for (SemanticFinding finding : evidence.findings()) {
      if (finding != null && finding.occurrenceId() != null && !finding.occurrenceId().isBlank()) {
        ids.add(finding.occurrenceId());
      }
    }
    for (String recordId : evidence.documentRecordRefs()) {
      if (recordId != null && !recordId.isBlank()) {
        ids.add(recordId);
      }
    }
    return ids;
  }

  private static void validateFaultArtifactRef(
      RecoveryDecision decision, RecoveryContext context, List<String> findings) {
    Reference fault = decision.faultArtifactRef();
    if (decision.action() == RecoveryAction.PARK) {
      if (fault != null && !knownArtifactRefs(context).contains(fault)) {
        findings.add("Fault artifact reference is not present in the recovery context.");
      }
      return;
    }
    if (fault == null) {
      findings.add("Fault artifact reference is required for action " + decision.action() + ".");
      return;
    }
    if (!knownArtifactRefs(context).contains(fault)) {
      findings.add("Fault artifact reference is not present in the recovery context.");
    }
  }

  private static Set<Reference> knownArtifactRefs(RecoveryContext context) {
    RecoveryEvidence evidence = context.evidence();
    Set<Reference> refs = new HashSet<>();
    if (evidence.approvedBriefRef() != null) {
      refs.add(evidence.approvedBriefRef());
    }
    if (evidence.approvedSemanticRef() != null) {
      refs.add(evidence.approvedSemanticRef());
    }
    refs.addAll(evidence.rejectedArtifactRefs());
    if (context.approvedPlanRef() != null) {
      refs.add(context.approvedPlanRef());
    }
    return refs;
  }

  private static void validateActionSpecificRules(
      RecoveryDecision decision, RecoveryEvidence evidence, List<String> findings) {
    Reference fault = decision.faultArtifactRef();
    Reference approvedBrief = evidence.approvedBriefRef();

    if (evidence.observedCauseCode() != null
        && GENERATED_CONTRACT_CAUSES.contains(evidence.observedCauseCode())
        && decision.action() != RecoveryAction.REGENERATE_ARTIFACT) {
      findings.add(decision.action() + " is not allowed for observed cause " + evidence.observedCauseCode()
          + ". Repair the generated artifact from the saved requirements; the runtime owns recovery limits.");
    }

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

  private static void validateAuthorQuestion(
      AuthorQuestionEvidence question, RecoveryContext context, List<String> findings) {
    var brief = context.approvedBrief();
    var facts = brief == null ? List.<RequirementFact>of() : java.util.stream.Stream.concat(
        brief.facts().stream(), brief.requirements().stream()).distinct().toList();
    AuthorQuestionAdmission.validate(question, facts, brief == null ? null : brief.flow(),
        brief == null ? List.of() : brief.catalogBindings(),
        context.authorInputs().stream().map(input -> input.inputId()).collect(java.util.stream.Collectors.toSet()),
        context.evidence().findings().stream().filter(finding -> question != null
            && java.util.Objects.equals(question.interactionId(), finding.nodeId()))
            .flatMap(finding -> finding.schemaDefaults().keySet().stream()).collect(java.util.stream.Collectors.toSet()),
        false, findings);
    if (question == null) { return; }
    if (question.kind() == AuthorQuestionEvidence.Kind.POLICY_CHOICE
        && (!RecoveryCauseCode.SECURITY_POLICY.name().equals(context.evidence().observedCauseCode())
            || context.evidence().findings().stream().noneMatch(finding ->
                "security-policy-choice".equals(finding.code())
                    && question.interactionId().equals(finding.nodeId())))) {
      findings.add("ASK_USER requires the unresolved policy rule and owner identified by the finding; preserve confirmed choices.");
    }
    if (question.kind() == AuthorQuestionEvidence.Kind.CATALOG_CHOICE
        && !RecoveryCauseCode.CATALOG_RESOLUTION.name().equals(context.evidence().observedCauseCode())) {
      findings.add("ASK_USER requires an unresolved catalog choice. Reuse the approved binding.");
    }
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
