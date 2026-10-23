package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.CompilerPipelinePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;

/** Shared compiler DAG closure logic for run pins and profile compatibility checks. */
public final class CompilerPipelineClosureSupport {

  private CompilerPipelineClosureSupport() {}

  public static boolean requiredTerminalsProducible(
      ProductPipelineProfile profile, CompilerPipelineIndex activeIndex) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(activeIndex, "activeIndex");
    CompilerPipelinePolicy policy = profile.compilerPipeline();
    if (policy == null) {
      return false;
    }
    if (!policy.supportedIndexSchemas().contains(activeIndex.schemaVersion())) {
      return false;
    }
    if (activeIndex.packageIdentity() == null) {
      return false;
    }
    try {
      resolveClosure(
          indexBySkillId(activeIndex.nodes()),
          new LinkedHashSet<>(policy.allowedPhases()),
          normalizeArtifacts(policy.requiredTerminalArtifacts()));
      return true;
    } catch (IllegalStateException ignored) {
      return false;
    }
  }

  public static LinkedHashSet<String> resolveClosure(
      Map<String, CompilerPipelineNode> byId,
      Set<String> allowedPhases,
      Set<String> requiredTerminals) {
    LinkedHashSet<String> closure = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    for (CompilerPipelineNode node : byId.values()) {
      if (!allowedPhases.isEmpty() && !allowedPhases.contains(node.compilerPhase())) {
        continue;
      }
      if (producesAny(node, requiredTerminals)) {
        queue.add(node.skillId());
      }
    }
    if (queue.isEmpty() && !requiredTerminals.isEmpty()) {
      throw new IllegalStateException(
          "no compiler nodes produce required terminal artifacts " + requiredTerminals);
    }
    while (!queue.isEmpty()) {
      String skillId = queue.poll();
      if (!closure.add(skillId)) {
        continue;
      }
      CompilerPipelineNode node = byId.get(skillId);
      if (node == null) {
        throw new IllegalStateException("compiler closure references unknown skill: " + skillId);
      }
      for (String dependency : node.dependsOn()) {
        CompilerPipelineNode dep = byId.get(dependency);
        if (dep == null) {
          throw new IllegalStateException(
              "compiler node " + skillId + " depends on unknown skill: " + dependency);
        }
        if (!allowedPhases.isEmpty() && !allowedPhases.contains(dep.compilerPhase())) {
          continue;
        }
        queue.add(dependency);
      }
    }
    return closure;
  }

  public static Map<String, CompilerPipelineNode> indexBySkillId(List<CompilerPipelineNode> nodes) {
    Map<String, CompilerPipelineNode> byId = new LinkedHashMap<>();
    for (CompilerPipelineNode node : nodes) {
      byId.put(node.skillId(), node);
    }
    return byId;
  }

  public static Set<String> normalizeArtifacts(List<ArtifactTypeRef> refs) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (ArtifactTypeRef ref : refs) {
      if (ref == null || ref.type() == null || ref.type().isBlank()) {
        continue;
      }
      normalized.add(normalizeArtifactName(ref.type()));
    }
    return normalized;
  }

  private static boolean producesAny(CompilerPipelineNode node, Set<String> requiredTerminals) {
    for (String produced : node.produces()) {
      if (requiredTerminals.contains(normalizeArtifactName(produced))) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeArtifactName(String raw) {
    return raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
  }
}
