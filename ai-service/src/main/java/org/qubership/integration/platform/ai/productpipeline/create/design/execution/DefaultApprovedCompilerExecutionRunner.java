package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Maps an approval-bound owning-skill closure and resolved catalog bindings into the shared
 * {@link CompilerDagExecutionEngine} request.
 */
@ApplicationScoped
public class DefaultApprovedCompilerExecutionRunner implements ApprovedCompilerExecutionRunner {

  private final CompilerDagExecutionEngine engine;
  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;

  @Inject
  public DefaultApprovedCompilerExecutionRunner(
      CompilerDagExecutionEngine engine,
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
  }

  @Override
  public CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      NormalizedDesignFlow flow,
      List<CatalogBindingResolution> bindings,
      RunManifest runManifest,
      String attemptId,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph,
      BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(approvedPlan, "approvedPlan");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(runManifest, "runManifest");
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;
    CompilerRunPin pin = requirePin(runManifest);
    List<String> approvedOwningSkillIds = orderedOwningSkillIds(approvedPlan);
    ResolvedCompilerDag executionDag = scopeDag(pin.resolvedDag(), approvedOwningSkillIds);
    String conversationId = resolveConversationId(runManifest.runId());
    List<CatalogBindingResolution> resolvedBindings =
        bindings == null ? List.of() : List.copyOf(bindings);
    RequirementBrief brief =
        DesignExecutionBriefFactory.build(
            loadStoredBrief(runManifest.runId()), flow, resolvedBindings, repairEvidence, priorGraph);
    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            runManifest.runId(),
            conversationId,
            runManifest,
            brief,
            flow,
            executionDag,
            approvedOwningSkillIds,
            resolvedBindings,
            List.of());
    return engine.execute(request, attemptId, progress).await().indefinitely();
  }

  private RequirementBrief loadStoredBrief(String runId) {
    Optional<RequirementBrief> brief =
        artifactStore
            .latest(runId, Kind.REQUIREMENT_BRIEF)
            .map(revision -> artifactStore.payload(revision, RequirementBrief.class));
    return brief.orElse(null);
  }

  private String resolveConversationId(String runId) {
    ProductPipelineRunDocument document =
        runStore
            .load(runId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "contract failure: missing product pipeline run for runId " + runId));
    String conversationId = document.run().conversationId();
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalStateException(
          "contract failure: product pipeline run is missing conversationId for runId " + runId);
    }
    return conversationId;
  }

  static List<String> orderedOwningSkillIds(DesignExecutionPlan plan) {
    LinkedHashSet<String> ordered = new LinkedHashSet<>();
    for (DesignExecutionPlan.Step step : plan.steps()) {
      if (step.ownerKind() != DesignExecutionPlan.OwnerKind.SKILL) {
        continue;
      }
      ordered.addAll(step.owningSkillIds());
    }
    return List.copyOf(ordered);
  }

  /**
   * Approved owning skills plus transitive mandatory {@code dependsOn} nodes from the pinned DAG.
   */
  static Set<String> skillClosureIds(
      DesignExecutionPlan plan, ResolvedCompilerDag fullDag) {
    return Set.copyOf(scopeDag(fullDag, orderedOwningSkillIds(plan)).nodes().stream()
        .map(ResolvedCompilerNode::skillId)
        .filter(Objects::nonNull)
        .toList());
  }

  static ResolvedCompilerDag scopeDag(
      ResolvedCompilerDag fullDag, List<String> approvedOwningSkillIds) {
    Objects.requireNonNull(fullDag, "fullDag");
    LinkedHashMap<String, ResolvedCompilerNode> byId = new LinkedHashMap<>();
    for (ResolvedCompilerNode node : fullDag.nodes()) {
      if (node != null && node.skillId() != null) {
        byId.put(node.skillId(), node);
      }
    }
    LinkedHashSet<String> closure = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>(approvedOwningSkillIds);
    while (!queue.isEmpty()) {
      String skillId = queue.removeFirst();
      if (!closure.add(skillId)) {
        continue;
      }
      ResolvedCompilerNode node = byId.get(skillId);
      if (node == null) {
        throw new IllegalStateException(
            "approved owning skill is outside the pinned compiler DAG: " + skillId);
      }
      for (String dependency : node.dependsOn()) {
        if (dependency != null && !dependency.isBlank()) {
          queue.addLast(dependency.trim());
        }
      }
    }
    List<ResolvedCompilerNode> nodes =
        closure.stream().map(byId::get).filter(Objects::nonNull).toList();
    Set<String> allowed = Set.copyOf(closure);
    List<CompilerPipelineDependency> dependencies =
        fullDag.dependencies().stream()
            .filter(
                edge ->
                    edge != null
                        && allowed.contains(edge.producerSkillId())
                        && allowed.contains(edge.consumerSkillId()))
            .toList();
    return new ResolvedCompilerDag(nodes, dependencies, fullDag.digest());
  }

  private static CompilerRunPin requirePin(RunManifest runManifest) {
    if (runManifest.compilerRunPin() == null || runManifest.compilerRunPin().resolvedDag() == null) {
      throw new IllegalStateException("compiler run pin with resolved DAG is required");
    }
    return runManifest.compilerRunPin();
  }
}
