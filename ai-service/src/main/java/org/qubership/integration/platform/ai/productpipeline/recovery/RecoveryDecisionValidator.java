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
    if (question == null || question.kind() == null || question.interactionId() == null
        || question.interactionId().isBlank() || question.propertyKey() == null || question.propertyKey().isBlank()) {
      findings.add("ASK_USER requires a scoped authorQuestionEvidence with interactionId, propertyKey and kind.");
      return;
    }
    Set<String> inputIds = new HashSet<>();
    context.authorInputs().forEach(input -> inputIds.add(input.inputId()));
    if (inputIds.isEmpty() || !inputIds.equals(new HashSet<>(question.reviewedInputIds()))) {
      findings.add("ASK_USER requires review of every saved author input. Missing source review cannot prove absence.");
    }
    var brief = context.approvedBrief();
    List<RequirementFact> scoped = brief == null ? List.of()
        : java.util.stream.Stream.concat(brief.facts().stream(), brief.requirements().stream()).distinct()
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.targetsInteraction(question.interactionId())
            || fact.elementIntent() != null && question.interactionId().equals(fact.elementIntent().elementRef()))
        .toList();
    boolean knownScope = !scoped.isEmpty() || brief != null && brief.flow() != null
        && brief.flow().interactions().stream().anyMatch(interaction ->
            question.interactionId().equals(interaction.interactionId()));
    if (!knownScope) {
      findings.add("ASK_USER must target an interaction or element in the saved requirements.");
    }
    Set<String> values = new HashSet<>();
    scoped.forEach(fact -> values.addAll(suppliedValues(fact, question.propertyKey())));
    Set<String> confirmedChoices = new HashSet<>();
    scoped.stream().map(RequirementFact::policyConflict)
        .filter(policy -> policy != null && question.propertyKey().equals(policy.propertyKey())
            && policy.decision() != null && policy.selectedValue() != null)
        .forEach(policy -> confirmedChoices.add(policy.selectedValue()));
    if (!confirmedChoices.isEmpty()) {
      values.clear();
      values.addAll(confirmedChoices);
    }
    if (brief != null) {
      brief.catalogBindings().stream().filter(binding -> question.interactionId().equals(binding.interactionId()))
          .forEach(binding -> {
            String value = switch (question.propertyKey()) {
              case "systemId", "integrationSystemId" -> binding.systemId();
              case "specificationGroupId", "integrationSpecificationGroupId" -> binding.specificationGroupId();
              case "specificationId", "integrationSpecificationId" -> binding.specificationId();
              case "integrationOperationId" -> binding.integrationOperationId();
              case "httpMethod", "httpMethodRestrict", "method", "integrationOperationMethod" -> binding.method();
              case "path", "integrationOperationPath" -> binding.path();
              default -> "";
            };
            if (value != null && !value.isBlank()) {
              values.add(value);
            }
          });
    }
    if (question.kind() == AuthorQuestionEvidence.Kind.MISSING_VALUE) {
      boolean hasDefault = context.evidence().findings().stream().anyMatch(finding ->
          question.interactionId().equals(finding.nodeId())
              && finding.schemaDefaults().containsKey(question.propertyKey()));
      if (!values.isEmpty() || hasDefault) {
        findings.add("ASK_USER cannot request a supplied value or schema default. Repair the producer from saved input.");
      }
    } else if (question.kind() == AuthorQuestionEvidence.Kind.CONFLICTING_VALUES && values.size() < 2) {
      findings.add("ASK_USER requires conflicting saved values for the same interaction and property.");
    } else if (question.kind() == AuthorQuestionEvidence.Kind.POLICY_CHOICE) {
      boolean matchingFinding = RecoveryCauseCode.SECURITY_POLICY.name().equals(context.evidence().observedCauseCode())
          && "accessControlType".equals(question.propertyKey()) && context.evidence().findings().stream().anyMatch(finding ->
              "security-policy-choice".equals(finding.code()) && question.interactionId().equals(finding.nodeId()));
      var policies = scoped.stream().map(RequirementFact::policyConflict)
          .filter(conflict -> conflict != null && question.propertyKey().equals(conflict.propertyKey())
              && org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator.EXTERNAL_RBAC_POLICY
                  .equals(conflict.policyId())).toList();
      boolean unresolved = policies.stream().anyMatch(conflict -> conflict.decision() == null);
      boolean resolved = policies.stream().anyMatch(conflict -> conflict.decision() != null);
      if (!matchingFinding || !unresolved || resolved) {
        findings.add("ASK_USER requires the unresolved policy rule and owner identified by the finding; preserve confirmed choices.");
      }
    } else if (question.kind() == AuthorQuestionEvidence.Kind.CATALOG_CHOICE) {
      if (!RecoveryCauseCode.CATALOG_RESOLUTION.name().equals(context.evidence().observedCauseCode())
          || brief != null && brief.catalogBindings().stream()
              .anyMatch(binding -> question.interactionId().equals(binding.interactionId()))) {
        findings.add("ASK_USER requires an unresolved catalog choice. Reuse the approved binding.");
      }
    }
  }

  private static Set<String> suppliedValues(RequirementFact fact, String key) {
    RequirementPolicyConflict policy = fact.policyConflict();
    if (policy != null && key.equals(policy.propertyKey()) && policy.decision() != null
        && policy.selectedValue() != null) {
      return Set.of(policy.selectedValue());
    }
    Set<String> values = new HashSet<>();
    if (policy != null && key.equals(policy.propertyKey()) && policy.requestedValue() != null
        && !policy.requestedValue().isBlank()) {
      values.add(policy.requestedValue());
    }
    String value = switch (key) {
      case "httpMethod", "httpMethodRestrict" -> fact.httpMethod();
      case "path", "contextPath" -> fact.path();
      case "topic" -> fact.topic();
      case "participant" -> fact.participant();
      case "operation" -> fact.operation();
      default -> "";
    };
    if (!value.isBlank()) {
      values.add(value);
    }
    if (fact.elementIntent() != null && fact.elementIntent().requestedProperties() != null) {
      fact.elementIntent().requestedProperties().stream()
          .filter(property -> property != null && key.equals(property.key())
              && property.value() != null && !property.value().isBlank())
          .forEach(property -> values.add(property.value()));
    }
    if (fact.elementIntent() != null && ("name".equals(key) || "requestedName".equals(key))
        && fact.elementIntent().requestedName() != null && !fact.elementIntent().requestedName().isBlank()) {
      values.add(fact.elementIntent().requestedName());
    }
    return values;
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
