package org.qubership.integration.platform.ai.compiler.policy;

import java.util.List;

/** Typed result of generator policy compilation for one knowledge pack build. */
public record CompilerGeneratorPolicyBuildResult(
    CompilerGeneratorPolicy policy, List<CompilerGeneratorPolicyIssue> validationIssues) {

  public CompilerGeneratorPolicyBuildResult {
    validationIssues =
        validationIssues == null ? List.of() : List.copyOf(validationIssues);
  }
}
