package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;

/** Immutable scheduler state for one pinned compiler DAG planning run. */
public record PlanningSchedulerState(
    ResolvedCompilerDag dag,
    Set<String> presentArtifactTypes,
    Set<String> completedSkillIds,
    Set<String> invocationKeys,
    Map<String, String> latestDigestByArtifactType,
    int invocationCount,
    int graphRevisionCount) {

  public PlanningSchedulerState {
    Objects.requireNonNull(dag, "dag");
    presentArtifactTypes =
        presentArtifactTypes == null ? Set.of() : Set.copyOf(presentArtifactTypes);
    completedSkillIds = completedSkillIds == null ? Set.of() : Set.copyOf(completedSkillIds);
    invocationKeys = invocationKeys == null ? Set.of() : Set.copyOf(invocationKeys);
    latestDigestByArtifactType =
        latestDigestByArtifactType == null ? Map.of() : Map.copyOf(latestDigestByArtifactType);
  }

  public PlanningSchedulerState complete(String skillId, String... producedArtifacts) {
    LinkedHashSet<String> completed = new LinkedHashSet<>(completedSkillIds);
    completed.add(skillId);
    LinkedHashSet<String> present = new LinkedHashSet<>(presentArtifactTypes);
    LinkedHashMap<String, String> digests = new LinkedHashMap<>(latestDigestByArtifactType);
    if (producedArtifacts != null) {
      for (String producedArtifact : producedArtifacts) {
        if (producedArtifact == null || producedArtifact.isBlank()) {
          continue;
        }
        String normalized = CompilerDerivedPlanningScheduler.normalizeArtifactType(producedArtifact);
        present.add(normalized);
        digests.put(normalized, skillId);
      }
    }
    return new PlanningSchedulerState(
        dag, present, completed, invocationKeys, digests, invocationCount, graphRevisionCount);
  }

  public PlanningSchedulerState addInvocationKey(String invocationKey) {
    LinkedHashSet<String> keys = new LinkedHashSet<>(invocationKeys);
    keys.add(invocationKey);
    return new PlanningSchedulerState(
        dag, presentArtifactTypes, completedSkillIds, keys, latestDigestByArtifactType, invocationCount, graphRevisionCount);
  }

  public PlanningSchedulerState withInvocationCount(int nextInvocationCount) {
    return new PlanningSchedulerState(
        dag,
        presentArtifactTypes,
        completedSkillIds,
        invocationKeys,
        latestDigestByArtifactType,
        nextInvocationCount,
        graphRevisionCount);
  }

  public PlanningSchedulerState withGraphRevisionCount(int nextGraphRevisionCount) {
    return new PlanningSchedulerState(
        dag,
        presentArtifactTypes,
        completedSkillIds,
        invocationKeys,
        latestDigestByArtifactType,
        invocationCount,
        nextGraphRevisionCount);
  }
}
