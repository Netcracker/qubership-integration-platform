package org.qubership.integration.platform.ai.compiler.policy;

import java.util.List;
import java.util.Optional;

/** Merged index of production compiler generator specifications. */
public record CompilerGeneratorSpecIndex(List<CompilerGeneratorSpec> specs) {

  public CompilerGeneratorSpecIndex {
    specs = specs == null ? List.of() : List.copyOf(specs);
  }

  public Optional<CompilerGeneratorSpec> findBySkillName(String skillName) {
    return specs.stream().filter(spec -> spec.skillName().equals(skillName)).findFirst();
  }

  public Optional<CompilerGeneratorSpec> findByGeneratorId(String generatorId) {
    return specs.stream().filter(spec -> generatorId.equals(spec.generatorId())).findFirst();
  }
}
