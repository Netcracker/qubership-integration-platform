package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.create.OwnerCandidateSet.FindingOwnerCategory;

/**
 * Exhaustive producer-to-cause map. Every {@link HaltProducer} lists the {@link RecoveryCauseCode}
 * values it may emit. Routing reads {@link #ownerCategory}; it does not read formatted text.
 */
public final class HaltProducerCauseTable {

  private HaltProducerCauseTable() {}

  /** Causes {@code producer} is allowed to emit. Adding a producer without a branch does not compile. */
  public static Set<RecoveryCauseCode> causesOf(HaltProducer producer) {
    return switch (producer) {
      case REQUIREMENT_DISCOVERY ->
          Set.of(
              RecoveryCauseCode.CONTRACT_SHAPE,
              RecoveryCauseCode.MISSING_MANDATORY_INPUT,
              RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED);
      case REQUIREMENT_ANALYSIS ->
          Set.of(
              RecoveryCauseCode.CONTRACT_SHAPE,
              RecoveryCauseCode.MISSING_MANDATORY_INPUT,
              RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED);
      case DESIGN_INPUT ->
          Set.of(
              RecoveryCauseCode.MISSING_BRIEF_FACTS,
              RecoveryCauseCode.CONTRACT_SHAPE,
              RecoveryCauseCode.VALIDATION_BLOCKER,
              RecoveryCauseCode.MISSING_MANDATORY_INPUT);
      case DESIGN_PLANNING ->
          Set.of(
              RecoveryCauseCode.CONTRACT_SHAPE,
              RecoveryCauseCode.VALIDATION_BLOCKER,
              RecoveryCauseCode.MISSING_MANDATORY_INPUT);
      case PLANNING ->
          Set.of(
              RecoveryCauseCode.SECURITY_POLICY,
              RecoveryCauseCode.UNKNOWN_PROPERTY,
              RecoveryCauseCode.MISSING_REQUIRED_PROPERTY,
              RecoveryCauseCode.VALIDATION_BLOCKER,
              RecoveryCauseCode.CONTRACT_SHAPE,
              RecoveryCauseCode.MISSING_MANDATORY_INPUT,
              RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED);
      case DESIGN_EXECUTION ->
          Set.of(
              RecoveryCauseCode.SECURITY_POLICY,
              RecoveryCauseCode.UNKNOWN_PROPERTY,
              RecoveryCauseCode.MISSING_REQUIRED_PROPERTY,
              RecoveryCauseCode.MISSING_BRIEF_FACTS,
              RecoveryCauseCode.CATALOG_RESOLUTION,
              RecoveryCauseCode.MAPPING_CONTRACT,
              RecoveryCauseCode.CONTRACT_SHAPE,
              RecoveryCauseCode.VALIDATION_BLOCKER,
              RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED);
      case SPECIFICATION_IMPORT ->
          Set.of(RecoveryCauseCode.MISSING_MANDATORY_INPUT, RecoveryCauseCode.CONTRACT_SHAPE);
      case MATERIALIZATION ->
          Set.of(RecoveryCauseCode.VALIDATION_BLOCKER, RecoveryCauseCode.CONTRACT_SHAPE);
      case CATALOG_BINDING -> Set.of(RecoveryCauseCode.CATALOG_RESOLUTION);
      case COMPILER_VALIDATOR ->
          Set.of(
              RecoveryCauseCode.SECURITY_POLICY,
              RecoveryCauseCode.UNKNOWN_PROPERTY,
              RecoveryCauseCode.MISSING_REQUIRED_PROPERTY,
              RecoveryCauseCode.VALIDATION_BLOCKER);
      case STAGE_EXECUTOR ->
          Set.of(
              RecoveryCauseCode.INTERNAL,
              RecoveryCauseCode.TECHNICAL_RETRY_EXHAUSTED,
              RecoveryCauseCode.POLICY_FAILURE);
    };
  }

  /**
   * Runtime-authored sentence naming what to change. Built from the typed cause and the owner the
   * router already selected. The model does not write this sentence.
   */
  static String instruction(
      RecoveryCause cause, OwnerDiagnosis diagnosis, List<OwnerCandidate> candidates) {
    if (diagnosis != null && diagnosis.ambiguous()) {
      return "Pick which artifact to revise.";
    }
    String owner = diagnosis == null ? "" : diagnosis.owner().orElse("");
    RecoveryCause typed =
        cause == null ? RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER) : cause;
    if (owner.isBlank()) {
      if (typed.causeCode() == RecoveryCauseCode.INTERNAL) {
        return "Stop with a report. This run has no producer to reopen.";
      }
      return sentenceFor(typed, "the owning artifact");
    }
    return sentenceFor(typed, theRole(roleOf(owner, candidates)));
  }

  private static String roleOf(String ownerStageId, List<OwnerCandidate> candidates) {
    if (candidates == null) {
      return "";
    }
    for (OwnerCandidate candidate : candidates) {
      if (candidate != null && ownerStageId.equals(candidate.stageId())) {
        return OwnerCandidateSet.clarifyRole(candidate);
      }
    }
    return "";
  }

  private static String theRole(String role) {
    if (role == null || role.isBlank()) {
      return "the owning artifact";
    }
    return role.startsWith("the ") ? role : "the " + role;
  }

  private static String sentenceFor(RecoveryCause cause, String role) {
    return switch (cause.causeCode()) {
      case SECURITY_POLICY -> "State the access policy in " + role + ".";
      case MISSING_BRIEF_FACTS -> "Add the missing facts to " + role + ".";
      case MAPPING_CONTRACT -> mappingContractSentence(cause, role);
      case MISSING_REQUIRED_PROPERTY -> "Add the required property to " + role + ".";
      case UNKNOWN_PROPERTY -> "Remove the unknown property from the generated element.";
      case CATALOG_RESOLUTION -> {
        String fact = cause.requestedFact();
        yield fact.isBlank()
            ? "Name the catalog service this chain should use."
            : "Name the " + fact + " this chain should use.";
      }
      case MISSING_MANDATORY_INPUT -> "Supply the missing input and continue.";
      case CONTRACT_SHAPE -> "Correct the contract of " + role + ".";
      case POLICY_FAILURE -> "Adjust the policy this stage rejected.";
      case TECHNICAL_RETRY_EXHAUSTED -> "Change the inputs this stage consumed, then retry.";
      case DOMAIN_FAILURE -> "Correct the domain error in " + role + ".";
      case VALIDATION_BLOCKER -> "Correct the validation error in " + role + ".";
      case INTERNAL -> "Reopen " + role + " to route around this defect.";
    };
  }

  /**
   * Unknown-target repair must say the field is absent. Do not ask the producer to supply a value
   * for a path this contract does not declare.
   */
  private static String mappingContractSentence(RecoveryCause cause, String role) {
    if (hasFindingCode(cause, "MAPPING_UNKNOWN_TARGET")) {
      return "The field is absent from this contract. Correct the target in " + role + ".";
    }
    if (hasFindingCode(cause, "MAPPING_MISSING_REQUIRED_TARGET")) {
      return "Supply a valid mapping source in " + role + ".";
    }
    return "Correct the mapping in " + role + ".";
  }

  private static boolean hasFindingCode(RecoveryCause cause, String code) {
    if (cause == null || code == null || code.isBlank()) {
      return false;
    }
    for (PlanValidationFinding finding : cause.findings()) {
      if (finding != null && code.equals(finding.code())) {
        return true;
      }
    }
    return false;
  }

  /** Owner category the router uses for {@code causeCode}. Exhaustive over {@link RecoveryCauseCode}. */
  static FindingOwnerCategory ownerCategory(RecoveryCauseCode causeCode) {
    return switch (causeCode) {
      case SECURITY_POLICY, MISSING_BRIEF_FACTS, MAPPING_CONTRACT ->
          FindingOwnerCategory.POLICY_OR_BRIEF;
      case MISSING_REQUIRED_PROPERTY -> FindingOwnerCategory.PLAN_FILL;
      case UNKNOWN_PROPERTY -> FindingOwnerCategory.EXECUTION;
      case CONTRACT_SHAPE,
              POLICY_FAILURE,
              TECHNICAL_RETRY_EXHAUSTED,
              CATALOG_RESOLUTION,
              VALIDATION_BLOCKER,
              MISSING_MANDATORY_INPUT,
              DOMAIN_FAILURE,
              INTERNAL ->
          FindingOwnerCategory.UNSPECIFIED;
    };
  }
}
