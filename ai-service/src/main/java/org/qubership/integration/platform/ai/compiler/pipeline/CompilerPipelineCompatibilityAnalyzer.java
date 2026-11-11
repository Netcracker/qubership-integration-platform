package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/**
 * Compares a previously certified compiler pipeline index with a candidate index and classifies
 * the semantic diff for activation decisions.
 */
public final class CompilerPipelineCompatibilityAnalyzer {

  public static final String PROFILE_CREATE_CHAIN_V1 = "create-chain@1";

  public static final String PROFILE_CREATE_CHAIN_V2 = "create-chain@2";

  private static final List<String> DEFAULT_COMPATIBLE_PROFILES =
      List.of(PROFILE_CREATE_CHAIN_V1, PROFILE_CREATE_CHAIN_V2);

  public PipelineCompatibilityReport compare(
      CompilerPipelineIndex previous, CompilerPipelineIndex candidate) {
    Objects.requireNonNull(candidate, "candidate");
    CompilerPipelineIndex normalizedCandidate = normalize(candidate);
    String candidateDigest = digestOf(normalizedCandidate);

    if (previous == null) {
      return bootstrapReport(candidateDigest);
    }

    CompilerPipelineIndex normalizedPrevious = normalize(previous);
    String previousDigest = digestOf(normalizedPrevious);

    List<String> blockingFindings = new ArrayList<>();
    if (!isSupportedSchema(normalizedCandidate.schemaVersion())) {
      blockingFindings.add(
          "Unsupported candidate pipeline index schema: " + normalizedCandidate.schemaVersion());
    }
    if (hasDependencyCycle(normalizedCandidate)) {
      blockingFindings.add("Dependency cycle detected in candidate pipeline index");
    }
    blockingFindings.addAll(missingMandatoryProducerFindings(normalizedPrevious, normalizedCandidate));

    List<String> changedNodes = changedNodeIds(normalizedPrevious, normalizedCandidate);
    List<String> changedDependencies =
        changedDependencyKeys(normalizedPrevious, normalizedCandidate);
    List<String> changedPhases = changedPhaseEntries(normalizedPrevious, normalizedCandidate);
    List<String> changedArtifactContracts =
        changedArtifactContractEntries(normalizedPrevious, normalizedCandidate);

    if (!blockingFindings.isEmpty()) {
      return new PipelineCompatibilityReport(
          PipelineCompatibilityReport.SCHEMA_VERSION,
          previousDigest,
          candidateDigest,
          PipelineChangeClass.BREAKING,
          changedNodes,
          changedDependencies,
          changedPhases,
          changedArtifactContracts,
          List.of(),
          List.of(),
          false,
          blockingFindings);
    }

    if (!changedPhases.isEmpty() || !changedArtifactContracts.isEmpty()) {
      return new PipelineCompatibilityReport(
          PipelineCompatibilityReport.SCHEMA_VERSION,
          previousDigest,
          candidateDigest,
          PipelineChangeClass.REQUIRES_PROFILE_BUMP,
          changedNodes,
          changedDependencies,
          changedPhases,
          changedArtifactContracts,
          List.of(),
          List.of("NEW_PRODUCT_PROFILE_VERSION"),
          false,
          List.of(
              "Phase or required artifact-contract changes require a new product profile version"));
    }

    if (!changedDependencies.isEmpty() || hasTopologyChange(normalizedPrevious, normalizedCandidate)) {
      return new PipelineCompatibilityReport(
          PipelineCompatibilityReport.SCHEMA_VERSION,
          previousDigest,
          candidateDigest,
          PipelineChangeClass.TOPOLOGY_OR_CONTRACT,
          changedNodes,
          changedDependencies,
          changedPhases,
          changedArtifactContracts,
          DEFAULT_COMPATIBLE_PROFILES,
          List.of("COMPILER_TESTS", "PRODUCT_QUALITY_GATE"),
          true,
          List.of());
    }

    return new PipelineCompatibilityReport(
        PipelineCompatibilityReport.SCHEMA_VERSION,
        previousDigest,
        candidateDigest,
        PipelineChangeClass.CONTENT_ONLY,
        changedNodes,
        changedDependencies,
        changedPhases,
        changedArtifactContracts,
        DEFAULT_COMPATIBLE_PROFILES,
        List.of(),
        true,
        List.of());
  }

  private static PipelineCompatibilityReport bootstrapReport(String candidateDigest) {
    return new PipelineCompatibilityReport(
        PipelineCompatibilityReport.SCHEMA_VERSION,
        null,
        candidateDigest,
        PipelineChangeClass.BOOTSTRAP,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        DEFAULT_COMPATIBLE_PROFILES,
        List.of(),
        true,
        List.of());
  }

  static CompilerPipelineIndex normalize(CompilerPipelineIndex index) {
    Objects.requireNonNull(index, "index");
    List<CompilerPipelineNode> nodes =
        index.nodes().stream()
            .sorted(
                Comparator.comparingInt(CompilerPipelineNode::topologicalLevel)
                    .thenComparingInt(CompilerPipelineNode::stableTieBreaker)
                    .thenComparing(CompilerPipelineNode::skillId))
            .map(CompilerPipelineCompatibilityAnalyzer::normalizeNode)
            .toList();
    List<CompilerPipelineDependency> dependencies =
        index.dependencies().stream()
            .sorted(
                Comparator.comparing(CompilerPipelineDependency::producerSkillId)
                    .thenComparing(CompilerPipelineDependency::consumerSkillId))
            .map(
                edge ->
                    new CompilerPipelineDependency(
                        edge.producerSkillId(),
                        edge.consumerSkillId(),
                        sortedCopy(edge.artifactTypes())))
            .toList();
    Map<String, String> sourceDigests = new TreeMap<>(index.sourceDigests());
    return new CompilerPipelineIndex(
        index.schemaVersion(),
        index.packVersion(),
        index.sources(),
        index.entries(),
        index.packageIdentity(),
        sourceDigests,
        nodes,
        dependencies);
  }

  private static CompilerPipelineNode normalizeNode(CompilerPipelineNode node) {
    return new CompilerPipelineNode(
        node.skillId(),
        node.compilerPhase(),
        node.generatorId(),
        sortedCopy(node.consumes()),
        sortedCopy(node.produces()),
        sortedCopy(node.dependsOn()),
        node.captureTool(),
        sortedCopy(node.applicabilitySignals()),
        sortedCopy(node.readinessSignals()),
        node.runtimeReady(),
        sortedCopy(node.runtimeReadinessFindings()),
        node.skillSha256(),
        node.addonSha256(),
        node.topologicalLevel(),
        node.stableTieBreaker(),
        node.mandatory(),
        node.executionMode(),
        node.adapterId(),
        normalizeOwnership(node.ownership()));
  }

  private static List<String> sortedCopy(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream().sorted().toList();
  }

  private static String digestOf(CompilerPipelineIndex index) {
    if (index.packageIdentity() != null
        && index.packageIdentity().packageDigest() != null
        && !index.packageIdentity().packageDigest().isBlank()) {
      return index.packageIdentity().packageDigest();
    }
    return "";
  }

  private static boolean isSupportedSchema(int schemaVersion) {
    return schemaVersion == CompilerPipelineIndexBuilder.SCHEMA_VERSION
        || schemaVersion == CompilerPipelineIndexBuilder.SCHEMA_VERSION_V1;
  }

  private static List<String> missingMandatoryProducerFindings(
      CompilerPipelineIndex previous, CompilerPipelineIndex candidate) {
    Map<String, Set<String>> previousProducers = mandatoryArtifactProducers(previous);
    Map<String, Set<String>> candidateProducers = mandatoryArtifactProducers(candidate);
    List<String> findings = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : previousProducers.entrySet()) {
      String artifact = entry.getKey();
      Set<String> remaining = candidateProducers.getOrDefault(artifact, Set.of());
      if (remaining.isEmpty()) {
        findings.add("Removed mandatory producer for artifact contract " + artifact);
      }
    }
    return findings;
  }

  private static Map<String, Set<String>> mandatoryArtifactProducers(CompilerPipelineIndex index) {
    Map<String, Set<String>> producers = new LinkedHashMap<>();
    for (CompilerPipelineNode node : index.nodes()) {
      if (!node.mandatory()) {
        continue;
      }
      for (String artifact : node.produces()) {
        producers.computeIfAbsent(artifact, ignored -> new LinkedHashSet<>()).add(node.skillId());
      }
    }
    return producers;
  }

  private static boolean hasDependencyCycle(CompilerPipelineIndex index) {
    Map<String, Set<String>> outgoing = new LinkedHashMap<>();
    Map<String, Integer> indegree = new LinkedHashMap<>();
    for (CompilerPipelineNode node : index.nodes()) {
      outgoing.put(node.skillId(), new LinkedHashSet<>());
      indegree.put(node.skillId(), 0);
    }
    for (CompilerPipelineDependency edge : index.dependencies()) {
      if (!outgoing.containsKey(edge.producerSkillId())
          || !indegree.containsKey(edge.consumerSkillId())) {
        continue;
      }
      if (outgoing.get(edge.producerSkillId()).add(edge.consumerSkillId())) {
        indegree.merge(edge.consumerSkillId(), 1, Integer::sum);
      }
    }
    List<String> queue =
        indegree.entrySet().stream()
            .filter(entry -> entry.getValue() == 0)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toCollection(ArrayList::new));
    int visited = 0;
    while (!queue.isEmpty()) {
      String current = queue.remove(0);
      visited++;
      for (String next : outgoing.getOrDefault(current, Set.of()).stream().sorted().toList()) {
        int remaining = indegree.merge(next, -1, Integer::sum);
        if (remaining == 0) {
          queue.add(next);
          queue.sort(String::compareTo);
        }
      }
    }
    return !index.nodes().isEmpty() && visited != index.nodes().size();
  }

  private static List<String> changedNodeIds(
      CompilerPipelineIndex previous, CompilerPipelineIndex candidate) {
    Map<String, CompilerPipelineNode> previousById = nodesById(previous);
    Map<String, CompilerPipelineNode> candidateById = nodesById(candidate);
    Set<String> ids = new LinkedHashSet<>();
    ids.addAll(previousById.keySet());
    ids.addAll(candidateById.keySet());
    List<String> changed = new ArrayList<>();
    for (String skillId : ids) {
      CompilerPipelineNode left = previousById.get(skillId);
      CompilerPipelineNode right = candidateById.get(skillId);
      if (left == null || right == null || !contentFingerprint(left).equals(contentFingerprint(right))) {
        changed.add(skillId);
      }
    }
    changed.sort(String::compareTo);
    return changed;
  }

  private static List<String> changedDependencyKeys(
      CompilerPipelineIndex previous, CompilerPipelineIndex candidate) {
    Set<String> previousKeys =
        previous.dependencies().stream()
            .map(CompilerPipelineCompatibilityAnalyzer::dependencyKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> candidateKeys =
        candidate.dependencies().stream()
            .map(CompilerPipelineCompatibilityAnalyzer::dependencyKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> changed = new LinkedHashSet<>();
    for (String key : previousKeys) {
      if (!candidateKeys.contains(key)) {
        changed.add(key);
      }
    }
    for (String key : candidateKeys) {
      if (!previousKeys.contains(key)) {
        changed.add(key);
      }
    }
    return changed.stream().sorted().toList();
  }

  private static List<String> changedPhaseEntries(
      CompilerPipelineIndex previous, CompilerPipelineIndex candidate) {
    Map<String, CompilerPipelineNode> previousById = nodesById(previous);
    Map<String, CompilerPipelineNode> candidateById = nodesById(candidate);
    Set<String> ids = new LinkedHashSet<>();
    ids.addAll(previousById.keySet());
    ids.addAll(candidateById.keySet());
    List<String> changed = new ArrayList<>();
    for (String skillId : ids) {
      CompilerPipelineNode left = previousById.get(skillId);
      CompilerPipelineNode right = candidateById.get(skillId);
      String leftPhase = left == null ? null : left.compilerPhase();
      String rightPhase = right == null ? null : right.compilerPhase();
      if (!Objects.equals(leftPhase, rightPhase)) {
        changed.add(skillId + ":" + nullToEmpty(leftPhase) + "->" + nullToEmpty(rightPhase));
      }
    }
    changed.sort(String::compareTo);
    return changed;
  }

  private static List<String> changedArtifactContractEntries(
      CompilerPipelineIndex previous, CompilerPipelineIndex candidate) {
    Map<String, CompilerPipelineNode> previousById = nodesById(previous);
    Map<String, CompilerPipelineNode> candidateById = nodesById(candidate);
    Set<String> ids = new LinkedHashSet<>();
    ids.addAll(previousById.keySet());
    ids.addAll(candidateById.keySet());
    List<String> changed = new ArrayList<>();
    for (String skillId : ids) {
      CompilerPipelineNode left = previousById.get(skillId);
      CompilerPipelineNode right = candidateById.get(skillId);
      if (left == null || right == null) {
        if (left != null) {
          for (String artifact : left.produces()) {
            changed.add(skillId + ":removed:" + artifact);
          }
          for (String artifact : left.consumes()) {
            changed.add(skillId + ":removed-consume:" + artifact);
          }
        }
        if (right != null) {
          for (String artifact : right.produces()) {
            changed.add(skillId + ":added:" + artifact);
          }
          for (String artifact : right.consumes()) {
            changed.add(skillId + ":added-consume:" + artifact);
          }
        }
        continue;
      }
      if (!left.produces().equals(right.produces()) || !left.consumes().equals(right.consumes())) {
        changed.add(skillId);
      }
    }
    changed.sort(String::compareTo);
    return changed;
  }

  private static boolean hasTopologyChange(
      CompilerPipelineIndex previous, CompilerPipelineIndex candidate) {
    Map<String, CompilerPipelineNode> previousById = nodesById(previous);
    Map<String, CompilerPipelineNode> candidateById = nodesById(candidate);
    if (!previousById.keySet().equals(candidateById.keySet())) {
      return true;
    }
    for (String skillId : previousById.keySet()) {
      CompilerPipelineNode left = previousById.get(skillId);
      CompilerPipelineNode right = candidateById.get(skillId);
      if (!left.dependsOn().equals(right.dependsOn())
          || left.topologicalLevel() != right.topologicalLevel()
          || left.stableTieBreaker() != right.stableTieBreaker()
          || left.mandatory() != right.mandatory()
          || left.executionMode() != right.executionMode()
          || !Objects.equals(left.adapterId(), right.adapterId())
          || !Objects.equals(left.generatorId(), right.generatorId())
          || !Objects.equals(left.captureTool(), right.captureTool())
          || !Objects.equals(left.ownership(), right.ownership())) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, CompilerPipelineNode> nodesById(CompilerPipelineIndex index) {
    Map<String, CompilerPipelineNode> byId = new LinkedHashMap<>();
    for (CompilerPipelineNode node : index.nodes()) {
      byId.put(node.skillId(), node);
    }
    return byId;
  }

  private static String dependencyKey(CompilerPipelineDependency edge) {
    return edge.producerSkillId()
        + "->"
        + edge.consumerSkillId()
        + ":"
        + String.join(",", edge.artifactTypes());
  }

  private static String contentFingerprint(CompilerPipelineNode node) {
    return String.join(
        "|",
        nullToEmpty(node.skillId()),
        nullToEmpty(node.compilerPhase()),
        nullToEmpty(node.generatorId()),
        String.join(",", node.consumes()),
        String.join(",", node.produces()),
        String.join(",", node.dependsOn()),
        nullToEmpty(node.captureTool()),
        String.join(",", node.applicabilitySignals()),
        String.join(",", node.readinessSignals()),
        Boolean.toString(node.runtimeReady()),
        String.join(",", node.runtimeReadinessFindings()),
        nullToEmpty(node.skillSha256()),
        nullToEmpty(node.addonSha256()),
        Integer.toString(node.topologicalLevel()),
        Integer.toString(node.stableTieBreaker()),
        Boolean.toString(node.mandatory()),
        node.executionMode() == null ? "" : node.executionMode().name(),
        nullToEmpty(node.adapterId()),
        ownershipFingerprint(node.ownership()));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static GraphPatchOwnershipPolicy normalizeOwnership(GraphPatchOwnershipPolicy policy) {
    GraphPatchOwnershipPolicy effective =
        policy == null ? GraphPatchOwnershipPolicy.denyAll() : policy;
    Map<String, Set<String>> properties = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : effective.properties().entrySet()) {
      properties.put(entry.getKey(), new LinkedHashSet<>(entry.getValue().stream().sorted().toList()));
    }
    return new GraphPatchOwnershipPolicy(
        effective.mayAddNodes(),
        effective.mayAddEdges(),
        new LinkedHashSet<>(effective.nodeTypes().stream().sorted().toList()),
        new LinkedHashSet<>(effective.chainFields().stream().sorted().toList()),
        properties);
  }

  private static String ownershipFingerprint(GraphPatchOwnershipPolicy policy) {
    GraphPatchOwnershipPolicy effective = normalizeOwnership(policy);
    StringBuilder builder = new StringBuilder();
    builder.append(effective.mayAddNodes()).append(';').append(effective.mayAddEdges()).append(';');
    builder.append(String.join(",", effective.nodeTypes())).append(';');
    builder.append(String.join(",", effective.chainFields())).append(';');
    for (Map.Entry<String, Set<String>> entry : new TreeMap<>(effective.properties()).entrySet()) {
      builder
          .append(entry.getKey())
          .append(':')
          .append(String.join(",", entry.getValue()))
          .append(';');
    }
    return builder.toString();
  }
}
