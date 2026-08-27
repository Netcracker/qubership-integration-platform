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
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContractRepository;
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
import org.qubership.integration.platform.ai.productpipeline.create.CompilerExecutionSeed;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
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
  private final ChainSemanticGraphCompiler graphCompiler;
  private final CompilerContractRepository contractRepository;

  @Inject
  public DefaultApprovedCompilerExecutionRunner(
      CompilerDagExecutionEngine engine,
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      ChainSemanticGraphCompiler graphCompiler,
      CompilerContractRepository contractRepository) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.graphCompiler = Objects.requireNonNull(graphCompiler, "graphCompiler");
    this.contractRepository = Objects.requireNonNull(contractRepository, "contractRepository");
  }

  @Override
  public CompilerDagExecutionResult execute(
      DesignExecutionPlan approvedPlan,
      ChainSemanticRevision revision,
      List<CatalogBindingResolution> bindings,
      RunManifest runManifest,
      String attemptId,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph,
      BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(approvedPlan, "approvedPlan");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(runManifest, "runManifest");
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;
    CompilerRunPin pin = requirePin(runManifest);
    List<String> approvedOwningSkillIds = orderedOwningSkillIds(approvedPlan);
    ResolvedCompilerDag executionDag = scopeDag(pin.resolvedDag(), approvedOwningSkillIds);
    String conversationId = resolveConversationId(runManifest.runId());
    List<CatalogBindingResolution> resolvedBindings =
        bindings == null ? List.of() : List.copyOf(bindings);
    CompilerContract contract =
        contractRepository.require(
            pin.compilerContractVersion() != null
                ? pin.compilerContractVersion()
                : CompilerContract.V1);
    ChainPlanGraph graph = graphCompiler.compile(revision, contract, resolvedBindings);
    RequirementBrief brief =
        DesignExecutionBriefFactory.build(
            loadStoredBrief(runManifest.runId()),
            revision,
            resolvedBindings,
            repairEvidence,
            priorGraph);
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forCreate(conversationId, brief, revision, graph);
    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            runManifest.runId(),
            conversationId,
            runManifest,
            brief,
            revision,
            executionDag,
            approvedOwningSkillIds,
            resolvedBindings,
            List.of(),
            seed);
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
