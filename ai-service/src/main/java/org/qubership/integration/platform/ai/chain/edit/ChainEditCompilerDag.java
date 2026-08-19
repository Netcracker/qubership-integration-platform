package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;

/**
 * The pinned compiler DAG cut at the generation boundary.
 *
 * <p>An edit starts from a chain that exists, so CREATE nodes that discover requirements, select a
 * pattern, name nodes, or configure triggers have nothing left to decide. Property-only and simple
 * additions skip structure as well. A compound structural addition first runs a separate
 * structure-only cut against the imported graph, then runs its configuration owners through the
 * ordinary edit cut.
 *
 * <p>What survives is the owning generators the edit needs, the assembler, and the mandatory
 * validators. Their declared inputs are narrowed the same way: a generator that consumed a
 * requirement brief in CREATE consumes the imported graph and the reader's request here, because
 * those are what the run actually holds.
 */
public final class ChainEditCompilerDag {

  /** Adapter ids of the nodes an edit always runs after its generators. */
  private static final String ASSEMBLY_SKILL = "cip-chain-assembler";
  private static final String VALIDATION_PHASE = "Validation";

  private ChainEditCompilerDag() {}

  /**
   * The subgraph an edit runs: the named generators, the assembler, and every mandatory validator.
   *
   * @param seedArtifactTypes artifact type names the seed already holds, in scheduler spelling
   */
  public static ResolvedCompilerDag cut(
      ResolvedCompilerDag full, Set<String> generatorSkillIds, Set<String> seedArtifactTypes) {
    return cut(full, generatorSkillIds, seedArtifactTypes, true);
  }

  /** The structure-only prefix used before an edit can derive its configuration owners. */
  public static ResolvedCompilerDag structureOnly(
      ResolvedCompilerDag full, Set<String> seedArtifactTypes) {
    return cut(full, Set.of("cip-structure-generator"), seedArtifactTypes, false);
  }

  private static ResolvedCompilerDag cut(
      ResolvedCompilerDag full,
      Set<String> generatorSkillIds,
      Set<String> seedArtifactTypes,
      boolean includeTerminalNodes) {
    Objects.requireNonNull(full, "full");
    Objects.requireNonNull(generatorSkillIds, "generatorSkillIds");
    Set<String> seeded = seedArtifactTypes == null ? Set.of() : seedArtifactTypes;

    LinkedHashSet<String> keptIds = new LinkedHashSet<>();
    for (ResolvedCompilerNode node : full.nodes()) {
      if (generatorSkillIds.contains(node.skillId())
          || (includeTerminalNodes
              && (ASSEMBLY_SKILL.equals(node.skillId())
                  || VALIDATION_PHASE.equals(node.compilerPhase())))) {
        keptIds.add(node.skillId());
      }
    }
    for (String requested : generatorSkillIds) {
      if (!keptIds.contains(requested)) {
        throw new IllegalArgumentException(
            "compiler DAG has no generator node " + requested);
      }
    }

    LinkedHashSet<String> producible = new LinkedHashSet<>(seeded);
    for (ResolvedCompilerNode node : full.nodes()) {
      if (!keptIds.contains(node.skillId())) {
        continue;
      }
      for (String produced : node.produces()) {
        producible.add(normalize(produced));
      }
    }

    List<ResolvedCompilerNode> nodes = new ArrayList<>();
    for (ResolvedCompilerNode node : full.nodes()) {
      if (!keptIds.contains(node.skillId())) {
        continue;
      }
      nodes.add(
          new ResolvedCompilerNode(
              node.skillId(),
              node.compilerPhase(),
              node.generatorId(),
              retainAvailable(node.consumes(), producible),
              node.produces(),
              node.dependsOn().stream().filter(keptIds::contains).toList(),
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
              node.ownership()));
    }
    return new ResolvedCompilerDag(
        nodes,
        full.dependencies().stream()
            .filter(
                edge ->
                    keptIds.contains(edge.producerSkillId())
                        && keptIds.contains(edge.consumerSkillId()))
            .toList(),
        full.digest() + ":edit:" + String.join(",", keptIds));
  }

  /**
   * The CREATE run manifest re-pinned to this edit: same compiler package, skill content, addons,
   * knowledge package, language version, and artifact schemas, with the cut DAG in place of the
   * full one so the proposal names the subgraph it actually ran.
   */
  public static RunManifest pinnedManifest(
      RunManifest source, String editRunId, ResolvedCompilerDag cutDag) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(cutDag, "cutDag");
    CompilerRunPin pin = source.compilerRunPin();
    if (pin == null) {
      throw new IllegalStateException("contract failure: compiler run pin is required for an edit");
    }
    CompilerRunPin editPin =
        new CompilerRunPin(
            pin.compilerPackageId(),
            pin.compilerPackageVersion(),
            pin.compilerPackageDigest(),
            pin.pipelineIndexSchemaVersion(),
            pin.pipelineIndexVersion(),
            pin.pipelineIndexDigest(),
            cutDag,
            cutDag.nodes().stream().map(ResolvedCompilerNode::skillId).toList(),
            pin.skillSha256ById(),
            pin.addonSha256ById(),
            pin.runtimeArtifactSchemas());
    return new RunManifest(
        editRunId,
        source.runId(),
        source.sourceReferences(),
        source.runtimeSelection(),
        source.profileId(),
        source.profileVersion(),
        source.profileDigest(),
        source.referenceBaselineId(),
        source.referenceBaselineDigest(),
        source.dependencyClosure(),
        source.dependencyClosureDigest(),
        source.knowledgePackage(),
        source.languageVersion(),
        source.artifactSchemaVersions(),
        editPin);
  }

  private static List<String> retainAvailable(List<String> consumes, Set<String> available) {
    List<String> retained = new ArrayList<>();
    for (String consumed : consumes) {
      if (available.contains(normalize(consumed))) {
        retained.add(consumed);
      }
    }
    return retained;
  }

  private static String normalize(String artifactType) {
    if (artifactType == null || artifactType.isBlank()) {
      return "";
    }
    return artifactType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
  }
}
