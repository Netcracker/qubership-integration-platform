package org.qubership.integration.platform.ai.compiler.catalog;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Merged compiler skill catalog used before exposing backend-runnable capabilities. */
public record CompilerSkillCatalog(List<CompilerSkillDescriptor> skills) {

  public CompilerSkillCatalog {
    skills = skills == null ? List.of() : List.copyOf(skills);
  }

  public Optional<CompilerSkillDescriptor> find(String name) {
    return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  public List<CompilerSkillDescriptor> runnableSkills() {
    return skills.stream().filter(CompilerSkillDescriptor::runnable).toList();
  }

  public boolean excludesFromRuntimePolicy(CompilerSkillDescriptor descriptor) {
    return switch (descriptor.disposition()) {
      case PRIVATE, BUILD_TIME, SPECIFICATION_ONLY -> true;
      default -> false;
    };
  }

  /** Skills without a catalog entry keep legacy capability-based access. */
  public boolean allowsRuntimeAccess(String skillName) {
    return find(skillName).map(descriptor -> !excludesFromRuntimePolicy(descriptor)).orElse(true);
  }

  public Map<CompilerSkillDisposition, Integer> dispositionCounts() {
    Map<CompilerSkillDisposition, Integer> counts = new EnumMap<>(CompilerSkillDisposition.class);
    for (CompilerSkillDisposition disposition : CompilerSkillDisposition.values()) {
      counts.put(disposition, 0);
    }
    for (CompilerSkillDescriptor skill : skills) {
      counts.merge(skill.disposition(), 1, Integer::sum);
    }
    return Map.copyOf(counts);
  }
}
