package org.qubership.integration.platform.ai.productpipeline.create;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.CompilerPipelinePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/**
 * Resolves and verifies the compiler DAG pin for product profiles that declare a compiler-pipeline
 * policy. For {@code create-chain@2}, also pins design-process skills that sit outside the compiler
 * DAG ({@code cip-design-planner}, {@code cip-design-executor}, {@code cip-design-generator}).
 */
public final class CompilerRunPinResolver {

  /** Design skills required on create-chain@2 run pins but absent from the compiler DAG. */
  public static final List<String> CREATE_CHAIN_V2_DESIGN_SKILLS =
      List.of("cip-design-planner", "cip-design-executor");

  /** Supplies content hashes for design skills loaded outside the compiler pipeline index. */
  @FunctionalInterface
  public interface DesignSkillHashLookup {
    String skillSha256(String skillId);
  }

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private final CompilerPipelineIndex activeIndex;
  private final DesignSkillHashLookup designSkillHashLookup;

  public CompilerRunPinResolver(CompilerPipelineIndex activeIndex) {
    this(activeIndex, null);
  }

  public CompilerRunPinResolver(
      CompilerPipelineIndex activeIndex, DesignSkillHashLookup designSkillHashLookup) {
    this.activeIndex = Objects.requireNonNull(activeIndex, "activeIndex");
    this.designSkillHashLookup = designSkillHashLookup;
  }

  public CompilerRunPin resolve(ProductPipelineProfile profile, KnowledgeQueryContext knowledge) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(knowledge, "knowledge");
    CompilerPipelinePolicy policy = profile.compilerPipeline();
    if (policy == null) {
      throw new IllegalStateException(
          "profile " + profile.profileId() + " has no compilerPipeline policy");
    }
    if (!policy.supportedIndexSchemas().contains(activeIndex.schemaVersion())) {
      throw new IllegalStateException(
          "active compiler pipeline schema "
              + activeIndex.schemaVersion()
              + " is not supported by profile "
              + profile.profileId()
              + " (supported="
              + policy.supportedIndexSchemas()
              + ")");
    }
    if (activeIndex.packageIdentity() == null) {
      throw new IllegalStateException("active compiler pipeline index has no package identity");
    }

    Set<String> allowedPhases = new LinkedHashSet<>(policy.allowedPhases());
    Set<String> requiredTerminals = normalizeArtifacts(policy.requiredTerminalArtifacts());
    Map<String, CompilerPipelineNode> byId = indexBySkillId(activeIndex.nodes());

    LinkedHashSet<String> closureIds = resolveClosure(byId, allowedPhases, requiredTerminals);
    List<CompilerPipelineNode> closureNodes =
        closureIds.stream()
            .map(byId::get)
            .sorted(
                Comparator.comparingInt(CompilerPipelineNode::topologicalLevel)
                    .thenComparingInt(CompilerPipelineNode::stableTieBreaker)
                    .thenComparing(CompilerPipelineNode::skillId))
            .toList();

    for (CompilerPipelineNode node : closureNodes) {
      if (!node.runtimeReady()) {
        throw new IllegalStateException(
            "required compiler node "
                + node.skillId()
                + " is not runtimeReady"
                + (node.runtimeReadinessFindings().isEmpty()
                    ? ""
                    : ": " + String.join(", ", node.runtimeReadinessFindings())));
      }
    }

    List<ResolvedCompilerNode> resolvedNodes =
        closureNodes.stream().map(CompilerRunPinResolver::toResolved).toList();
    List<CompilerPipelineDependency> resolvedDependencies =
        activeIndex.dependencies().stream()
            .filter(
                edge ->
                    closureIds.contains(edge.producerSkillId())
                        && closureIds.contains(edge.consumerSkillId()))
            .toList();

    Map<String, String> skillHashes = new LinkedHashMap<>();
    Map<String, String> addonHashes = new LinkedHashMap<>();
    for (CompilerPipelineNode node : closureNodes) {
      if (node.skillSha256() != null && !node.skillSha256().isBlank()) {
        skillHashes.put(node.skillId(), node.skillSha256());
      }
      if (node.addonSha256() != null && !node.addonSha256().isBlank()) {
        addonHashes.put(node.skillId(), node.addonSha256());
      }
    }
    pinCreateChainV2DesignSkills(profile, skillHashes);

    List<ArtifactTypeRef> runtimeSchemas = new ArrayList<>();
    runtimeSchemas.addAll(policy.preSatisfiedArtifacts());
    for (ArtifactTypeRef terminal : policy.requiredTerminalArtifacts()) {
      if (!runtimeSchemas.contains(terminal)) {
        runtimeSchemas.add(terminal);
      }
    }

    ResolvedCompilerDag dag =
        new ResolvedCompilerDag(
            resolvedNodes, resolvedDependencies, digestDag(resolvedNodes, resolvedDependencies));

    return new CompilerRunPin(
        activeIndex.packageIdentity().packageId(),
        activeIndex.packageIdentity().packageVersion(),
        activeIndex.packageIdentity().packageDigest(),
        activeIndex.schemaVersion(),
        activeIndex.packVersion() == null ? "" : activeIndex.packVersion().normalized(),
        digestIndex(activeIndex),
        dag,
        List.copyOf(closureIds),
        skillHashes,
        addonHashes,
        runtimeSchemas);
  }

  public void verifyAvailable(RunManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    CompilerRunPin pin = manifest.compilerRunPin();
    if (pin == null) {
      return;
    }
    if (activeIndex.packageIdentity() == null) {
      throw new IllegalStateException(
          "pinned compiler package is unavailable: active index has no package identity");
    }
    String activeDigest = activeIndex.packageIdentity().packageDigest();
    if (!Objects.equals(pin.compilerPackageDigest(), activeDigest)) {
      throw new IllegalStateException(
          "pinned compiler package is unavailable"
              + " (pinned="
              + pin.compilerPackageDigest()
              + ", active="
              + activeDigest
              + ")");
    }

    Map<String, CompilerPipelineNode> byId = indexBySkillId(activeIndex.nodes());
    for (Map.Entry<String, String> entry : pin.skillSha256ById().entrySet()) {
      CompilerPipelineNode node = byId.get(entry.getKey());
      if (node != null) {
        if (!Objects.equals(entry.getValue(), node.skillSha256())) {
          throw new IllegalStateException(
              "pinned skill content is unavailable or mismatched: " + entry.getKey());
        }
        continue;
      }
      if (designSkillHashLookup == null
          || !CREATE_CHAIN_V2_DESIGN_SKILLS.contains(entry.getKey())) {
        throw new IllegalStateException(
            "pinned skill content is unavailable or mismatched: " + entry.getKey());
      }
      String actual = designSkillHashLookup.skillSha256(entry.getKey());
      if (!Objects.equals(entry.getValue(), actual)) {
        throw new IllegalStateException(
            "pinned skill content is unavailable or mismatched: " + entry.getKey());
      }
    }
    for (Map.Entry<String, String> entry : pin.addonSha256ById().entrySet()) {
      CompilerPipelineNode node = byId.get(entry.getKey());
      if (node == null || !Objects.equals(entry.getValue(), node.addonSha256())) {
        throw new IllegalStateException(
            "pinned addon content is unavailable or mismatched: " + entry.getKey());
      }
    }
  }

  private void pinCreateChainV2DesignSkills(
      ProductPipelineProfile profile, Map<String, String> skillHashes) {
    if (!"create-chain".equals(profile.profileId()) || !"2".equals(profile.profileVersion())) {
      return;
    }
    if (designSkillHashLookup == null) {
      throw new IllegalStateException(
          "create-chain@2 requires a DesignSkillHashLookup for design-process skill pins");
    }
    for (String skillId : CREATE_CHAIN_V2_DESIGN_SKILLS) {
      String hash = designSkillHashLookup.skillSha256(skillId);
      if (hash == null || hash.isBlank()) {
        throw new IllegalStateException(
            "create-chain@2 missing pinned skill hash for " + skillId);
      }
      skillHashes.put(skillId, hash);
    }
  }

  private static LinkedHashSet<String> resolveClosure(
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

  private static boolean producesAny(CompilerPipelineNode node, Set<String> requiredTerminals) {
    for (String produced : node.produces()) {
      if (requiredTerminals.contains(normalizeArtifactName(produced))) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> normalizeArtifacts(List<ArtifactTypeRef> refs) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (ArtifactTypeRef ref : refs) {
      if (ref == null || ref.type() == null || ref.type().isBlank()) {
        continue;
      }
      normalized.add(normalizeArtifactName(ref.type()));
    }
    return normalized;
  }

  private static String normalizeArtifactName(String raw) {
    return raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
  }

  private static Map<String, CompilerPipelineNode> indexBySkillId(List<CompilerPipelineNode> nodes) {
    Map<String, CompilerPipelineNode> byId = new LinkedHashMap<>();
    for (CompilerPipelineNode node : nodes) {
      byId.put(node.skillId(), node);
    }
    return byId;
  }

  private static ResolvedCompilerNode toResolved(CompilerPipelineNode node) {
    return new ResolvedCompilerNode(
        node.skillId(),
        node.compilerPhase(),
        node.generatorId(),
        node.consumes(),
        node.produces(),
        node.dependsOn(),
        node.captureTool(),
        node.applicabilitySignals(),
        node.readinessSignals(),
        node.runtimeReady(),
        node.runtimeReadinessFindings(),
        node.topologicalLevel(),
        node.stableTieBreaker(),
        node.mandatory(),
        node.executionMode(),
        node.adapterId(),
        node.ownership());
  }

  private static String digestIndex(CompilerPipelineIndex index) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("schemaVersion", index.schemaVersion());
    canonical.put(
        "packVersion",
        index.packVersion() == null ? "" : index.packVersion().normalized());
    if (index.packageIdentity() != null) {
      Map<String, Object> identity = new TreeMap<>();
      identity.put("packageId", index.packageIdentity().packageId());
      identity.put("packageVersion", index.packageIdentity().packageVersion());
      identity.put("packageDigest", index.packageIdentity().packageDigest());
      canonical.put("packageIdentity", identity);
    }
    canonical.put("sourceDigests", new TreeMap<>(index.sourceDigests()));
    canonical.put("nodes", canonicalNodes(index.nodes()));
    canonical.put("dependencies", canonicalDependencies(index.dependencies()));
    return sha256Json(canonical);
  }

  private static String digestDag(
      List<ResolvedCompilerNode> nodes, List<CompilerPipelineDependency> dependencies) {
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put(
        "nodes",
        nodes.stream()
            .map(
                node -> {
                  Map<String, Object> map = new TreeMap<>();
                  map.put("skillId", node.skillId());
                  map.put("compilerPhase", node.compilerPhase());
                  map.put("generatorId", node.generatorId());
                  map.put("consumes", node.consumes());
                  map.put("produces", node.produces());
                  map.put("dependsOn", node.dependsOn());
                  map.put("captureTool", node.captureTool());
                  map.put("applicabilitySignals", node.applicabilitySignals());
                  map.put("readinessSignals", node.readinessSignals());
                  map.put("runtimeReady", node.runtimeReady());
                  map.put("runtimeReadinessFindings", node.runtimeReadinessFindings());
                  map.put("topologicalLevel", node.topologicalLevel());
                  map.put("stableTieBreaker", node.stableTieBreaker());
                  map.put("mandatory", node.mandatory());
                  map.put(
                      "executionMode",
                      node.executionMode() == null ? null : node.executionMode().name());
                  map.put("adapterId", node.adapterId());
                  map.put("ownership", canonicalOwnership(node.ownership()));
                  return map;
                })
            .toList());
    canonical.put("dependencies", canonicalDependencies(dependencies));
    return sha256Json(canonical);
  }

  private static List<Map<String, Object>> canonicalNodes(List<CompilerPipelineNode> nodes) {
    return nodes.stream()
        .map(
            node -> {
              Map<String, Object> map = new TreeMap<>();
              map.put("skillId", node.skillId());
              map.put("compilerPhase", node.compilerPhase());
              map.put("generatorId", node.generatorId());
              map.put("consumes", node.consumes());
              map.put("produces", node.produces());
              map.put("dependsOn", node.dependsOn());
              map.put("captureTool", node.captureTool());
              map.put("applicabilitySignals", node.applicabilitySignals());
              map.put("readinessSignals", node.readinessSignals());
              map.put("runtimeReady", node.runtimeReady());
              map.put("runtimeReadinessFindings", node.runtimeReadinessFindings());
              map.put("skillSha256", node.skillSha256());
              map.put("addonSha256", node.addonSha256());
              map.put("topologicalLevel", node.topologicalLevel());
              map.put("stableTieBreaker", node.stableTieBreaker());
              map.put("mandatory", node.mandatory());
              map.put(
                  "executionMode",
                  node.executionMode() == null ? null : node.executionMode().name());
              map.put("adapterId", node.adapterId());
              map.put("ownership", canonicalOwnership(node.ownership()));
              return map;
            })
        .toList();
  }

  private static List<Map<String, Object>> canonicalDependencies(
      List<CompilerPipelineDependency> dependencies) {
    return dependencies.stream()
        .map(
            edge -> {
              Map<String, Object> map = new TreeMap<>();
              map.put("producerSkillId", edge.producerSkillId());
              map.put("consumerSkillId", edge.consumerSkillId());
              map.put("artifactTypes", edge.artifactTypes());
              return map;
            })
        .toList();
  }

  private static String sha256Json(Map<String, Object> canonical) {
    try {
      return sha256(JSON.writeValueAsString(canonical));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize compiler pin digest payload", e);
    }
  }

  private static String sha256(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static Map<String, Object> canonicalOwnership(GraphPatchOwnershipPolicy policy) {
    GraphPatchOwnershipPolicy effective =
        policy == null ? GraphPatchOwnershipPolicy.denyAll() : policy;
    Map<String, Object> map = new TreeMap<>();
    map.put("mayAddNodes", effective.mayAddNodes());
    map.put("mayAddEdges", effective.mayAddEdges());
    map.put("nodeTypes", effective.nodeTypes().stream().sorted().toList());
    map.put("chainFields", effective.chainFields().stream().sorted().toList());
    Map<String, Object> properties = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : new TreeMap<>(effective.properties()).entrySet()) {
      properties.put(entry.getKey(), entry.getValue().stream().sorted().toList());
    }
    map.put("properties", properties);
    return map;
  }
}
