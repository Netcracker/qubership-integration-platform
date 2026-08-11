package org.qubership.integration.platform.ai.compiler.plan;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;

/** Selects the next ready generator from the workspace manifest. */
@ApplicationScoped
public class CompilerOrchestrationService {

  public Optional<String> nextReadySkillId(
      CompilerGeneratorPolicy policy,
      GeneratorPlanManifest manifest,
      Set<String> completedSkillIds,
      List<String> wiredSkillIds) {
    for (var descriptor : policy.generators()) {
      if (!wiredSkillIds.contains(descriptor.skillId())) {
        continue;
      }
      if (completedSkillIds.contains(descriptor.skillId())) {
        continue;
      }
      GeneratorPlan plan = findPlan(manifest, descriptor.skillId()).orElse(null);
      if (plan == null || plan.status() != GeneratorPlanStatus.READY) {
        continue;
      }
      return Optional.of(descriptor.skillId());
    }
    return Optional.empty();
  }

  public void autoCompleteSkipped(Set<String> completedSkillIds, GeneratorPlanManifest manifest) {
    for (GeneratorPlan plan : manifest.plans()) {
      if (plan.status() == GeneratorPlanStatus.SKIPPED) {
        completedSkillIds.add(plan.skillId());
      }
    }
  }

  public Optional<GeneratorPlan> findPlan(GeneratorPlanManifest manifest, String skillId) {
    if (manifest == null) {
      return Optional.empty();
    }
    return manifest.plans().stream().filter(plan -> skillId.equals(plan.skillId())).findFirst();
  }

  public GeneratorPlanStatus statusForSkill(GeneratorPlanManifest manifest, String skillId) {
    return findPlan(manifest, skillId)
        .map(GeneratorPlan::status)
        .orElse(GeneratorPlanStatus.BLOCKED);
  }
}
