package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

/** Shared source and value admission for discovery and recovery questions. */
public final class AuthorQuestionAdmission {
  private AuthorQuestionAdmission() {}

  public static void validate(AuthorQuestionEvidence question, List<RequirementFact> facts,
      RequirementFlow flow, List<CatalogBindingHint> bindings, Set<String> inputIds,
      Set<String> defaults, boolean sourceOwnerReviewed, List<String> findings) {
    if (question == null || question.kind() == null || question.interactionId() == null
        || question.interactionId().isBlank() || question.propertyKey() == null || question.propertyKey().isBlank()) {
      findings.add("ASK_USER requires a scoped authorQuestionEvidence with interactionId, propertyKey and kind.");
      return;
    }
    if (inputIds.isEmpty() || !inputIds.equals(new HashSet<>(question.reviewedInputIds()))) {
      findings.add("ASK_USER requires review of every saved author input. Missing source review cannot prove absence.");
    }
    var scoped = facts.stream().filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> fact.targetsInteraction(question.interactionId())
            || fact.elementIntent() != null && question.interactionId().equals(fact.elementIntent().elementRef())).toList();
    if (scoped.isEmpty() && (flow == null || flow.interactions().stream().noneMatch(interaction ->
        question.interactionId().equals(interaction.interactionId())))) {
      findings.add("ASK_USER must target an interaction or element in the saved requirements.");
    }
    Set<String> values = new HashSet<>();
    scoped.forEach(fact -> values.addAll(suppliedValues(fact, question.propertyKey())));
    if (flow != null) flow.interactions().stream().filter(interaction ->
        question.interactionId().equals(interaction.interactionId())).forEach(interaction -> {
          String value = switch (question.propertyKey()) {
            case "participant" -> interaction.participant();
            case "operation" -> interaction.operation();
            case "description" -> interaction.description();
            default -> "";
          };
          if (value != null && !value.isBlank()) values.add(value);
        });
    var policies = scoped.stream().map(RequirementFact::policyConflict)
        .filter(policy -> policy != null && question.propertyKey().equals(policy.propertyKey())).toList();
    Set<String> confirmed = new HashSet<>();
    policies.stream().filter(policy -> policy.decision() != null && policy.selectedValue() != null)
        .forEach(policy -> confirmed.add(policy.selectedValue()));
    if (!confirmed.isEmpty()) { values.clear(); values.addAll(confirmed); }
    bindings.stream().filter(binding -> question.interactionId().equals(binding.interactionId())).forEach(binding -> {
      String value = switch (question.propertyKey()) {
        case "systemId", "integrationSystemId" -> binding.systemId();
        case "specificationGroupId", "integrationSpecificationGroupId" -> binding.specificationGroupId();
        case "specificationId", "integrationSpecificationId" -> binding.specificationId();
        case "integrationOperationId" -> binding.integrationOperationId();
        case "httpMethod", "httpMethodRestrict", "method", "integrationOperationMethod" -> binding.method();
        case "path", "integrationOperationPath" -> binding.path();
        default -> "";
      };
      if (value != null && !value.isBlank()) { values.add(value); }
    });
    switch (question.kind()) {
      case MISSING_VALUE -> {
        if (!values.isEmpty() || defaults.contains(question.propertyKey())) {
          findings.add("ASK_USER cannot request a supplied value or schema default. Repair the producer from saved input.");
        } else if (!sourceOwnerReviewed) {
          findings.add(RecoveryDecisionValidator.SOURCE_RECONCILIATION_REQUIRED
              + ": The source owner must check saved author messages before a missing projected value can be asked again.");
        }
      }
      case AMBIGUOUS_ANSWER -> {
        if (!sourceOwnerReviewed) findings.add(RecoveryDecisionValidator.SOURCE_RECONCILIATION_REQUIRED
            + ": The source owner must review the saved answer and its question before asking again.");
        if (!values.isEmpty()) findings.add("Resolve supplied values before asking an ambiguous-answer question.");
      }
      case CONFLICTING_VALUES -> {
        if (values.size() < 2) findings.add("ASK_USER requires conflicting saved values for the same interaction and property.");
      }
      case POLICY_CHOICE -> {
        if (policies.stream().noneMatch(policy -> policy.decision() == null
            && CompilerSecurityValidator.matchesExternalRbacPolicy(policy))
            || policies.stream().anyMatch(policy -> policy.decision() != null
                && CompilerSecurityValidator.matchesExternalRbacPolicy(policy))) {
          findings.add("ASK_USER requires the unresolved policy rule and owner identified by the finding; preserve confirmed choices."
              + " The external HTTP policy recommends RBAC for requested NONE or ABAC. A policy ID alone cannot"
              + " establish that recommendation; correct invented policy metadata without asking the author.");
        }
      }
      case CATALOG_CHOICE -> {
        if (bindings.stream().anyMatch(binding -> question.interactionId().equals(binding.interactionId())))
          findings.add("ASK_USER requires an unresolved catalog choice. Reuse the approved binding.");
      }
    }
  }

  public static Set<String> suppliedValues(RequirementFact fact, String key) {
    var policy = fact.policyConflict();
    if (policy != null && key.equals(policy.propertyKey()) && policy.decision() != null
        && policy.selectedValue() != null) return Set.of(policy.selectedValue());
    Set<String> values = new HashSet<>();
    if (policy != null && key.equals(policy.propertyKey()) && policy.requestedValue() != null
        && !policy.requestedValue().isBlank()) values.add(policy.requestedValue());
    String value = switch (key) {
      case "httpMethod", "httpMethodRestrict" -> fact.httpMethod();
      case "path", "contextPath" -> fact.path();
      case "topic" -> fact.topic();
      case "participant" -> fact.participant();
      case "operation" -> fact.operation();
      default -> "";
    };
    if (value != null && !value.isBlank()) values.add(value);
    if (fact.elementIntent() != null) {
      fact.elementIntent().requestedProperties().stream().filter(property -> key.equals(property.key())
          && property.value() != null && !property.value().isBlank()).forEach(property -> values.add(property.value()));
      if (("name".equals(key) || "requestedName".equals(key)) && fact.elementIntent().requestedName() != null)
        values.add(fact.elementIntent().requestedName());
    }
    return values;
  }
}
