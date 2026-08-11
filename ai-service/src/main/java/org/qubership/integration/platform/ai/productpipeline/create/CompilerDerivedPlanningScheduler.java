package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillRunStatus;
import org.qubership.integration.platform.ai.skill.executor.StreamingSkillExecutor;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.orchestration.SkillSubgraph;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Deterministic scheduler over a pinned compiler DAG for planning. */
public final class CompilerDerivedPlanningScheduler {

  private static final int MAX_INVOCATIONS = 64;
  private static final int MAX_GRAPH_REVISIONS = 32;
  private static final String REQUIREMENT_ANALYZER_SKILL = "cip-requirement-analyzer";
  private static final String STATE_ARGUMENT = "state";
  private static final Comparator<ResolvedCompilerNode> NODE_ORDER =
      Comparator.comparingInt(ResolvedCompilerNode::topologicalLevel)
          .thenComparingInt(ResolvedCompilerNode::stableTieBreaker)
          .thenComparing(node -> node.skillId());

  private final SkillExecutorRegistry skillRegistry;
  private final CompilerNodeExecutionAdapterRegistry javaAdapterRegistry;

  public CompilerDerivedPlanningScheduler(
      SkillExecutorRegistry skillRegistry, CompilerNodeExecutionAdapterRegistry javaAdapterRegistry) {
    this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
    this.javaAdapterRegistry = Objects.requireNonNull(javaAdapterRegistry, "javaAdapterRegistry");
  }

  public Optional<ResolvedCompilerNode> next(PlanningSchedulerState state) {
    Objects.requireNonNull(state, STATE_ARGUMENT);
    List<ResolvedCompilerNode> runnable = runnableNodes(state);
    if (!runnable.isEmpty()) {
      return Optional.of(runnable.get(0));
    }
    if (allNodesCompleted(state)) {
      return Optional.empty();
    }
    throw stalledFailure(state);
  }

  public PlanningSchedulerState executeNext(PlanningSchedulerState state) {
    Objects.requireNonNull(state, STATE_ARGUMENT);
    ResolvedCompilerNode node = next(state).orElseThrow();
    return executeNode(state, node);
  }

  public PlanningSchedulerState completeVirtualNodes(PlanningSchedulerState state) {
    Objects.requireNonNull(state, STATE_ARGUMENT);
    PlanningSchedulerState current = state;
    while (true) {
      Optional<ResolvedCompilerNode> next = next(current);
      if (next.isEmpty()) {
        return current;
      }
      CompilerNodeExecutionMode mode = next.get().executionMode();
      if (mode != CompilerNodeExecutionMode.VIRTUAL_ORCHESTRATOR) {
        return current;
      }
      current = executeNode(current, next.get());
    }
  }

  public PlanningSchedulerState recordInvocation(PlanningSchedulerState state, String invocationKey) {
    Objects.requireNonNull(state, STATE_ARGUMENT);
    if (invocationKey == null || invocationKey.isBlank()) {
      throw new IllegalStateException("contract failure: invocation key must not be blank");
    }
    if (state.invocationCount() >= MAX_INVOCATIONS) {
      throw new IllegalStateException(
          "contract failure: planning invocation limit reached (" + MAX_INVOCATIONS + ")");
    }
    if (state.invocationKeys().contains(invocationKey)) {
      throw new IllegalStateException("contract failure: duplicate invocation key " + invocationKey);
    }
    return state.addInvocationKey(invocationKey).withInvocationCount(state.invocationCount() + 1);
  }

  public PlanningSchedulerState bumpGraphRevision(PlanningSchedulerState state) {
    Objects.requireNonNull(state, STATE_ARGUMENT);
    if (state.graphRevisionCount() >= MAX_GRAPH_REVISIONS) {
      throw new IllegalStateException(
          "contract failure: graph revision limit reached (" + MAX_GRAPH_REVISIONS + ")");
    }
    return state.withGraphRevisionCount(state.graphRevisionCount() + 1);
  }

  public static String normalizeArtifactType(String artifactType) {
    if (artifactType == null || artifactType.isBlank()) {
      return "";
    }
    return artifactType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
  }

  private PlanningSchedulerState executeNode(PlanningSchedulerState state, ResolvedCompilerNode node) {
    PlanningSchedulerState current = state;
    switch (node.executionMode()) {
      case PRE_SATISFIED -> {
        ensurePreSatisfiedOutputsPresent(current, node);
        return completeNode(current, node, List.of(), "pre-satisfied");
      }
      case VIRTUAL_ORCHESTRATOR -> {
        return completeNode(current, node, List.of(), "virtual");
      }
      case LLM_SKILL -> {
        if (node.captureTool() == null || node.captureTool().isBlank()) {
          throw new IllegalStateException(
              "contract failure: missing capture tool for pinned LLM skill " + node.skillId());
        }
        SkillExecutor executor = skillRegistry.require(node.skillId());
        SkillExecutionResult result = runSkill(executor, node);
        if (result.status() == SkillRunStatus.FAILED || result.status() == SkillRunStatus.HITL_PENDING) {
          throw new IllegalStateException(
              "contract failure: LLM skill did not complete " + node.skillId() + " status=" + result.status());
        }
        return completeNode(current, node, result.outputs(), node.skillId() + ":" + current.graphRevisionCount());
      }
      case JAVA_ADAPTER -> {
        if (node.adapterId() == null || node.adapterId().isBlank()) {
          throw new IllegalStateException(
              "contract failure: missing adapter id for JAVA_ADAPTER node " + node.skillId());
        }
        CompilerNodeExecutionAdapter adapter = javaAdapterRegistry.require(node.adapterId());
        CompilerNodeExecutionResult result = adapter.execute(node, current);
        return completeNode(
            current, node, result.workspaceOutputs(), node.adapterId() + ":" + current.graphRevisionCount());
      }
      default -> throw new IllegalStateException("contract failure: unsupported execution mode for " + node.skillId());
    }
  }

  private SkillExecutionResult runSkill(SkillExecutor executor, ResolvedCompilerNode node) {
    SkillWorkspace workspace = new InMemorySkillWorkspace("compiler-derived-planning");
    SkillRunContext context =
        new SkillRunContext(
            "compiler-derived-planning",
            node.skillId(),
            "pinned",
            SkillSubgraph.BUILD_CHAIN,
            0,
            false,
            "");
    return invokeSkill(executor, context, workspace).await().indefinitely();
  }

  private static Uni<SkillExecutionResult> invokeSkill(
      SkillExecutor executor, SkillRunContext context, SkillWorkspace workspace) {
    if (executor instanceof StreamingSkillExecutor streaming) {
      return streaming
          .runStreaming(context, workspace)
          .collect()
          .asList()
          .onItem()
          .transformToUni(ignored -> streaming.run(context, workspace));
    }
    return executor.run(context, workspace);
  }

  private PlanningSchedulerState completeNode(
      PlanningSchedulerState state,
      ResolvedCompilerNode node,
      List<SkillArtifact> producedArtifacts,
      String invocationKey) {
    PlanningSchedulerState invoked = recordInvocation(state, invocationKey);
    LinkedHashSet<String> produced = new LinkedHashSet<>();
    for (String declared : node.produces()) {
      String normalized = normalizeArtifactType(declared);
      if (!normalized.isBlank()) {
        produced.add(normalized);
      }
    }
    for (SkillArtifact artifact : producedArtifacts) {
      if (artifact == null || artifact.type() == null) {
        continue;
      }
      produced.add(artifact.type().name());
    }
    PlanningSchedulerState completed =
        invoked.complete(node.skillId(), produced.toArray(String[]::new));
    if (produced.contains(SkillArtifactType.CHAIN_PLAN_GRAPH.name())
        || produced.contains(SkillArtifactType.GRAPH_PATCH.name())
        || produced.contains(SkillArtifactType.GRAPH_PATCH_ARTIFACT.name())
        || produced.contains(SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name())) {
      completed = bumpGraphRevision(completed);
    }
    return completed;
  }

  private void ensurePreSatisfiedOutputsPresent(PlanningSchedulerState state, ResolvedCompilerNode node) {
    for (String output : node.produces()) {
      String normalized = normalizeArtifactType(output);
      if (!state.presentArtifactTypes().contains(normalized)) {
        throw new IllegalStateException(
            "contract failure: pre-satisfied output is missing for "
                + node.skillId()
                + " output="
                + normalized);
      }
    }
  }

  private boolean allNodesCompleted(PlanningSchedulerState state) {
    for (ResolvedCompilerNode node : state.dag().nodes()) {
      if (!state.completedSkillIds().contains(node.skillId())) {
        return false;
      }
    }
    return true;
  }

  private List<ResolvedCompilerNode> runnableNodes(PlanningSchedulerState state) {
    List<ResolvedCompilerNode> runnable = new ArrayList<>();
    for (ResolvedCompilerNode node : state.dag().nodes()) {
      boolean completed = state.completedSkillIds().contains(node.skillId());
      if (!completed && isRunnable(state, node)) {
        runnable.add(node);
      }
    }
    runnable.sort(NODE_ORDER);
    return runnable;
  }

  private boolean isRunnable(PlanningSchedulerState state, ResolvedCompilerNode node) {
    if (!state.completedSkillIds().containsAll(node.dependsOn())) {
      return false;
    }
    for (String consumed : node.consumes()) {
      String normalized = normalizeArtifactType(consumed);
      if (!normalized.isBlank() && !state.presentArtifactTypes().contains(normalized)) {
        return false;
      }
    }
    return true;
  }

  private IllegalStateException stalledFailure(PlanningSchedulerState state) {
    for (ResolvedCompilerNode node : state.dag().nodes()) {
      boolean incomplete = !state.completedSkillIds().contains(node.skillId());
      boolean dependenciesSatisfied = state.completedSkillIds().containsAll(node.dependsOn());
      if (incomplete && dependenciesSatisfied) {
        for (String consumed : node.consumes()) {
          String normalized = normalizeArtifactType(consumed);
          if (!state.presentArtifactTypes().contains(normalized) && !hasProducer(state, normalized)) {
            return new IllegalStateException(
                "contract failure: missing producer for mandatory artifact " + normalized);
          }
        }
      }
    }
    return new IllegalStateException("contract failure: unresolved cycle or blocked dependencies");
  }

  private boolean hasProducer(PlanningSchedulerState state, String artifactType) {
    if (artifactType == null || artifactType.isBlank()) {
      return true;
    }
    if (SkillArtifactType.REQUIREMENT_BRIEF.name().equals(artifactType)
        || SkillArtifactType.RAW_USER_REQUEST.name().equals(artifactType)) {
      return true;
    }
    for (ResolvedCompilerNode node : state.dag().nodes()) {
      for (String produced : node.produces()) {
        if (normalizeArtifactType(produced).equals(artifactType)) {
          return true;
        }
      }
    }
    return false;
  }

  public static PlanningSchedulerState seededState(org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag dag) {
    Set<String> present =
        Set.of(SkillArtifactType.RAW_USER_REQUEST.name(), SkillArtifactType.REQUIREMENT_BRIEF.name());
    Set<String> completed = Set.of(REQUIREMENT_ANALYZER_SKILL);
    return new PlanningSchedulerState(dag, present, completed, Set.of(), java.util.Map.of(), 0, 0);
  }
}
