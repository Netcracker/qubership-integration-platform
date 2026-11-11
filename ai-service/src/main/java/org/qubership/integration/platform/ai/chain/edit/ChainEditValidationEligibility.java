package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerValidationPipeline;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

/**
 * Edit approval uses the compiled validation bundle, but does not refuse a catalog-legal chain
 * because CREATE-time validators already fail on the imported seed.
 *
 * <p>A typical HTTP trigger in the catalog is {@code externalRoute=true} with {@code
 * accessControlType=NONE}. {@code cip-security-validator} treats that as a blocker. COMPARE_AND_PATCH
 * would otherwise refuse every endpoint edit of such a chain.
 */
final class ChainEditValidationEligibility {

  private static final String GENERIC_FAILURE =
      "The compiled chain did not pass validation, so there is nothing to approve.";

  private ChainEditValidationEligibility() {}

  static boolean approvalEligible(
      ChainPlanGraph seed,
      CompilerValidationBundle compiled,
      CompilerValidationPipeline pipeline) {
    return introducedBlockers(seed, compiled, pipeline).isEmpty();
  }

  static String failureMessage(
      ChainPlanGraph seed,
      CompilerValidationBundle compiled,
      CompilerValidationPipeline pipeline) {
    List<String> blockers = introducedBlockers(seed, compiled, pipeline);
    if (blockers.isEmpty()) {
      return GENERIC_FAILURE;
    }
    StringBuilder message = new StringBuilder(GENERIC_FAILURE);
    message.append(" Findings: ");
    int limit = Math.min(5, blockers.size());
    for (int i = 0; i < limit; i++) {
      if (i > 0) {
        message.append("; ");
      }
      message.append(blockers.get(i));
    }
    if (blockers.size() > limit) {
      message.append(" (+").append(blockers.size() - limit).append(" more)");
    }
    return message.toString();
  }

  private static List<String> introducedBlockers(
      ChainPlanGraph seed,
      CompilerValidationBundle compiled,
      CompilerValidationPipeline pipeline) {
    if (compiled == null || pipeline == null) {
      return List.of(GENERIC_FAILURE);
    }
    if (compiled.approvalEligible()) {
      return List.of();
    }
    List<String> introduced = new ArrayList<>();
    for (CompilerValidationPass pass : compiled.passes()) {
      if (pass == null || pass.result() == null || pass.result().valid()) {
        continue;
      }
      Set<String> compiledBlockers = blockerMessages(pass.result());
      if (compiledBlockers.isEmpty()) {
        introduced.add(pass.validatorSkillId() + ": " + nullToEmpty(pass.result().summary()));
        continue;
      }
      ValidationResult seedResult = seedPass(pipeline, pass.validatorSkillId(), seed);
      Set<String> seedBlockers = blockerMessages(seedResult);
      for (String blocker : compiledBlockers) {
        if (!seedBlockers.contains(blocker)) {
          introduced.add(blocker);
        }
      }
    }
    return List.copyOf(introduced);
  }

  private static ValidationResult seedPass(
      CompilerValidationPipeline pipeline, String validatorSkillId, ChainPlanGraph seed) {
    if (seed == null) {
      return new ValidationResult(true, List.of(), "no seed");
    }
    try {
      return pipeline.validatePass(validatorSkillId, null, seed);
    } catch (RuntimeException e) {
      return new ValidationResult(true, List.of(), "seed pass skipped");
    }
  }

  private static Set<String> blockerMessages(ValidationResult result) {
    LinkedHashSet<String> messages = new LinkedHashSet<>();
    if (result == null || result.issues() == null) {
      return messages;
    }
    for (ValidationIssue issue : result.issues()) {
      if (issue == null || issue.severity() != ValidationSeverity.BLOCKER) {
        continue;
      }
      if (issue.message() != null && !issue.message().isBlank()) {
        messages.add(issue.message().trim());
      }
    }
    return messages;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
