package org.qubership.integration.platform.ai.compiler.policy;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

/** Compiled generator execution policy for ai-service orchestration. */
public record CompilerGeneratorPolicy(
    QipKnowledgePackVersion packVersion,
    CompilerGeneratorPolicySource sources,
    List<CompilerGeneratorDescriptor> generators) {

  /** Readiness signal names declared for a generator skill, or empty when unknown. */
  public List<String> readinessSignalsFor(String capabilityId) {
    return generators().stream()
        .filter(descriptor -> capabilityId.equals(descriptor.skillId()))
        .findFirst()
        .map(
            descriptor ->
                descriptor.readiness() != null
                    ? descriptor.readiness().signals()
                    : List.<String>of())
        .orElse(List.of());
  }
}
