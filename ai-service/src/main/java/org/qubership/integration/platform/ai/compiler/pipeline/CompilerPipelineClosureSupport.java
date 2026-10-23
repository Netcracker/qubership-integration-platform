package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.CompilerPipelinePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;

/** Shared compiler DAG closure logic for run pins and profile compatibility checks. */
public final class CompilerPipelineClosureSupport {

  private CompilerPipelineClosureSupport() {}

  public static boolean requiredTerminalsProducible(
      ProductPipelineProfile profile, CompilerPipelineIndex activeIndex) {
    return terminalCoverageProblem(profile, activeIndex).isEmpty();
  }

  /** Human-readable reason when required terminals cannot be produced; empty when producible. */
  public static Optional<String> terminalCoverageProblem(
      ProductPipelineProfile profile, CompilerPipelineIndex activeIndex) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(activeIndex, "activeIndex");
    CompilerPipelinePolicy policy = profile.compilerPipeline();
    if (policy == null) {
      return Optional.of("profile has no compilerPipeline policy");
    }
    if (!policy.supportedIndexSchemas().contains(activeIndex.schemaVersion())) {
      return Optional.of(
          "compiler index schema "
              + activeIndex.schemaVersion()
              + " is not in supportedIndexSchemas "
              + policy.supportedIndexSchemas());
    }
    if (activeIndex.packageIdentity() == null) {
      return Optional.of("active compiler index has no package identity");
    }
    try {
      resolveClosure(
          indexBySkillId(activeIndex.nodes()),
          new LinkedHashSet<>(policy.allowedPhases()),
          normalizeArtifacts(policy.requiredTerminalArtifacts()));
      return Optional.empty();
    } catch (IllegalStateException ex) {
      return Optional.of(ex.getMessage());
    }
  }

  public static LinkedHashSet<String> resolveClosure(
      Map<String, CompilerPipelineNode> byId,
      Set<String> allowedPhases,
      Set<String> requiredTerminals) {
    LinkedHashSet<String> closure = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    LinkedHashSet<String> uncovered = new LinkedHashSet<>(requiredTerminals);
    for (CompilerPipelineNode node : byId.values()) {
      if (!allowedPhases.isEmpty() && !allowedPhases.contains(node.compilerPhase())) {
        continue;
      }
      if (producesMatching(node, requiredTerminals).isEmpty()) {
        continue;
      }
      queue.add(node.skillId());
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
      uncovered.removeAll(producesMatching(node, requiredTerminals));
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
    if (!uncovered.isEmpty()) {
      throw new IllegalStateException(
          "no compiler nodes produce required terminal artifacts " + uncovered);
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

  private static LinkedHashSet<String> producesMatching(
      CompilerPipelineNode node, Set<String> requiredTerminals) {
    LinkedHashSet<String> matched = new LinkedHashSet<>();
    for (String produced : node.produces()) {
      String normalized = normalizeArtifactName(produced);
      if (requiredTerminals.contains(normalized)) {
        matched.add(normalized);
      }
    }
    return matched;
  }

  private static String normalizeArtifactName(String raw) {
    return raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
  }
}
