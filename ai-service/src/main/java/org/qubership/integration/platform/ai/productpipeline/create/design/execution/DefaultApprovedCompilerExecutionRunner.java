package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
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
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
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
      List<ResolvedServiceCallBinding> bindings,
      RunManifest runManifest,
      String attemptId,
      StageRepairEvidence repairEvidence,
      ChainPlanGraph priorGraph,
      BiConsumer<String, String> skillProgress) {
    Objects.requireNonNull(approvedPlan, "approvedPlan");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(runManifest, "runManifest");
    if (!Objects.equals(approvedPlan.semanticRevisionId(), revision.revisionId())) {
      throw new IllegalStateException(
          "Approved design plan does not match the approved semantic revision");
    }
    RequirementBrief storedBrief = loadStoredBrief(runManifest.runId());
    rejectLiveMappingIntentMismatch(storedBrief, revision);
    BiConsumer<String, String> progress =
        skillProgress == null ? (skillId, status) -> {} : skillProgress;
    CompilerRunPin pin = requirePin(runManifest);
    List<String> approvedOwningSkillIds = orderedOwningSkillIds(approvedPlan);
    ResolvedCompilerDag executionDag = scopeDag(pin.resolvedDag(), approvedOwningSkillIds);
    String conversationId = resolveConversationId(runManifest.runId());
    List<ResolvedServiceCallBinding> resolvedBindings =
        bindings == null ? List.of() : List.copyOf(bindings);
    CompilerContract contract =
        contractRepository.require(
            pin.compilerContractVersion() != null
                ? pin.compilerContractVersion()
                : CompilerContract.V1);
    ChainPlanGraph graph = graphCompiler.compile(revision, contract, resolvedBindings);
    RequirementBrief brief =
        DesignExecutionBriefFactory.build(
            storedBrief,
            revision,
            repairEvidence,
            priorGraph);
    CompilerExecutionSeed seed =
        CompilerExecutionSeed.forCreate(conversationId, brief, revision, graph, resolvedBindings);
    CompilerDagExecutionRequest request =
        new CompilerDagExecutionRequest(
            runManifest.runId(),
            conversationId,
            runManifest,
            brief,
            revision,
            executionDag,
            approvedOwningSkillIds,
            List.of(),
            seed);
    return engine.execute(request, attemptId, progress).await().indefinitely();
  }

  private static void rejectLiveMappingIntentMismatch(
      RequirementBrief storedBrief, ChainSemanticRevision revision) {
    if (storedBrief == null || storedBrief.mappingIntents().isEmpty()) {
      return;
    }
    if (!projectLiveMappingIntents(storedBrief.mappingIntents(), revision)
        .equals(revision.mappingIntents())) {
      throw new IllegalStateException(
          "Live mapping-intent collection differs from the approved semantic revision");
    }
  }

  private static List<MappingIntent> projectLiveMappingIntents(
      List<MappingIntent> liveIntents, ChainSemanticRevision revision) {
    LinkedHashMap<String, SemanticExecutionEdge> siteByIntent = new LinkedHashMap<>();
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      String mappingId = edge.mappingId();
      if (mappingId == null || mappingId.isBlank()) {
        continue;
      }
      siteByIntent.putIfAbsent(mappingId, edge);
    }
    List<MappingIntent> projected = new ArrayList<>(liveIntents.size());
    for (MappingIntent intent : liveIntents) {
      SemanticExecutionEdge site = siteByIntent.get(intent.mappingIntentId());
      projected.add(
          site == null
              ? intent
              : ChainSemanticCaptureAdapter.projectOntoCarryingEdge(intent, site));
    }
    return projected;
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
   * Approved owning generators plus their real prerequisites, then assembler and validators.
   *
   * <p>The pinned assembler lists every generation skill in {@code dependsOn} via the
   * {@code all-generation-skills} macro. Walking that list would run quartz, retry, and the rest
   * on a HealthProxy CREATE. Seed the walk from non-terminal owners only, then attach assembler
   * and Validation nodes and drop {@code dependsOn} edges that point outside the cut.
   */
  static Set<String> skillClosureIds(
      DesignExecutionPlan plan, ResolvedCompilerDag fullDag) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (ResolvedCompilerNode node : scopeDag(fullDag, orderedOwningSkillIds(plan)).nodes()) {
      if (node != null && node.skillId() != null) {
        ids.add(node.skillId());
      }
    }
    return Set.copyOf(ids);
  }

  static ResolvedCompilerDag scopeDag(
      ResolvedCompilerDag fullDag, List<String> approvedOwningSkillIds) {
    Objects.requireNonNull(fullDag, "fullDag");
    LinkedHashMap<String, ResolvedCompilerNode> byId = indexBySkillId(fullDag);
    LinkedHashSet<String> closure = generatorClosure(byId, approvedOwningSkillIds);
    attachExecutionTerminals(fullDag, closure);
    return new ResolvedCompilerDag(
        copyScopedNodes(fullDag, closure),
        copyScopedDependencies(fullDag, closure),
        fullDag.digest());
  }

  private static LinkedHashMap<String, ResolvedCompilerNode> indexBySkillId(
      ResolvedCompilerDag fullDag) {
    LinkedHashMap<String, ResolvedCompilerNode> byId = new LinkedHashMap<>();
    for (ResolvedCompilerNode node : fullDag.nodes()) {
      if (node != null && node.skillId() != null) {
        byId.put(node.skillId(), node);
      }
    }
    return byId;
  }

  private static LinkedHashSet<String> generatorClosure(
      LinkedHashMap<String, ResolvedCompilerNode> byId, List<String> approvedOwningSkillIds) {
    LinkedHashSet<String> closure = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    for (String skillId : approvedOwningSkillIds) {
      ResolvedCompilerNode node = byId.get(skillId);
      if (node == null) {
        throw new IllegalStateException(
            "approved owning skill is outside the pinned compiler DAG: " + skillId);
      }
      if (!isExecutionTerminal(node)) {
        queue.addLast(skillId);
      }
    }
    while (!queue.isEmpty()) {
      String skillId = queue.removeFirst();
      ResolvedCompilerNode node = byId.get(skillId);
      if (!closure.add(skillId) || node == null) {
        continue;
      }
      enqueueDependencies(queue, node);
    }
    return closure;
  }

  private static void enqueueDependencies(ArrayDeque<String> queue, ResolvedCompilerNode node) {
    for (String dependency : node.dependsOn()) {
      if (dependency != null && !dependency.isBlank()) {
        queue.addLast(dependency.trim());
      }
    }
  }

  private static void attachExecutionTerminals(
      ResolvedCompilerDag fullDag, LinkedHashSet<String> closure) {
    for (ResolvedCompilerNode node : fullDag.nodes()) {
      if (isExecutionTerminal(node)) {
        closure.add(node.skillId());
      }
    }
  }

  private static List<ResolvedCompilerNode> copyScopedNodes(
      ResolvedCompilerDag fullDag, LinkedHashSet<String> closure) {
    List<ResolvedCompilerNode> nodes = new ArrayList<>();
    for (ResolvedCompilerNode node : fullDag.nodes()) {
      if (node == null || !closure.contains(node.skillId())) {
        continue;
      }
      nodes.add(copyWithFilteredDependsOn(node, closure));
    }
    return nodes;
  }

  private static ResolvedCompilerNode copyWithFilteredDependsOn(
      ResolvedCompilerNode node, LinkedHashSet<String> closure) {
    return new ResolvedCompilerNode(
        node.skillId(),
        node.compilerPhase(),
        node.generatorId(),
        node.consumes(),
        node.produces(),
        node.dependsOn().stream().filter(closure::contains).toList(),
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

  private static List<CompilerPipelineDependency> copyScopedDependencies(
      ResolvedCompilerDag fullDag, LinkedHashSet<String> closure) {
    List<CompilerPipelineDependency> dependencies = new ArrayList<>();
    for (CompilerPipelineDependency edge : fullDag.dependencies()) {
      if (edge != null
          && closure.contains(edge.producerSkillId())
          && closure.contains(edge.consumerSkillId())) {
        dependencies.add(edge);
      }
    }
    return dependencies;
  }

  private static boolean isExecutionTerminal(ResolvedCompilerNode node) {
    if (node == null || node.skillId() == null) {
      return false;
    }
    if ("cip-chain-assembler".equals(node.skillId())) {
      return true;
    }
    String phase = node.compilerPhase();
    return phase != null && phase.equals("Validation");
  }

  private static CompilerRunPin requirePin(RunManifest runManifest) {
    if (runManifest.compilerRunPin() == null || runManifest.compilerRunPin().resolvedDag() == null) {
      throw new IllegalStateException("compiler run pin with resolved DAG is required");
    }
    return runManifest.compilerRunPin();
  }
}
