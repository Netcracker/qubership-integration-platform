package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

class CompilerDerivedPlanningSchedulerTest {

  private static final String REQUIREMENT_BRIEF = SkillArtifactType.REQUIREMENT_BRIEF.name();
  private static final String SELECTED_PATTERN = SkillArtifactType.SELECTED_PATTERN.name();
  private static final String ELEMENT_SKELETON = SkillArtifactType.ELEMENT_SKELETON.name();
  private static final String NAMING_MANIFEST = SkillArtifactType.NAMING_MANIFEST.name();
  private static final String CONFIGURED_TRIGGER_SET = SkillArtifactType.CONFIGURED_TRIGGER_SET.name();
  private static final String GRAPH_ASSEMBLY_RESULT = SkillArtifactType.GRAPH_ASSEMBLY_RESULT.name();
  private static final String PRE_BUILD_VALIDATION = SkillArtifactType.PRE_BUILD_VALIDATION.name();

  private SkillExecutorRegistry skillRegistry;
  private CompilerNodeExecutionAdapterRegistry javaAdapterRegistry;
  private CompilerDerivedPlanningScheduler scheduler;

  @BeforeEach
  void setUp() {
    skillRegistry = mock(SkillExecutorRegistry.class);
    javaAdapterRegistry = mock(CompilerNodeExecutionAdapterRegistry.class);
    scheduler = new CompilerDerivedPlanningScheduler(skillRegistry, javaAdapterRegistry);
  }

  @Test
  void schedulesTypedPlanningPrefixFromPinnedDag() {
    PlanningSchedulerState state = seededWithRequirementBrief(dagForPrefixFlow());

    assertEquals("cip-pattern-selector", scheduler.next(state).orElseThrow().skillId());
    state = state.complete("cip-pattern-selector", SELECTED_PATTERN, ELEMENT_SKELETON);
    assertEquals("cip-naming-generator", scheduler.next(state).orElseThrow().skillId());
    state = state.complete("cip-naming-generator", NAMING_MANIFEST);
    assertEquals("cip-trigger-generator", scheduler.next(state).orElseThrow().skillId());
  }

  @Test
  void skipsVirtualChainGeneratorWithoutInvokingRegistry() {
    PlanningSchedulerState state = stateReadyForVirtualOrchestrator();

    PlanningSchedulerState next = scheduler.completeVirtualNodes(state);

    assertTrue(next.completedSkillIds().contains("cip-chain-generator"));
    verifyNoInteractions(skillRegistry, javaAdapterRegistry);
  }

  @Test
  void dispatchesAssemblerThroughPinnedJavaAdapter() {
    PlanningSchedulerState state = stateReadyForAssembler();
    CompilerNodeExecutionAdapter adapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(adapter);
    when(adapter.execute(node(state, "cip-chain-assembler"), state))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    scheduler.executeNext(state);

    verify(javaAdapterRegistry).require("graph-assembly");
    verify(adapter).execute(node(state, "cip-chain-assembler"), state);
    verifyNoInteractions(skillRegistry);
  }

  @Test
  void schedulerPersistsBundleOnlyAfterFinalMandatoryValidatorNode() {
    ResolvedCompilerDag dag = dagWithAssemblerAndValidators();
    PlanningSchedulerState state = seededWithRequirementBrief(dag);
    state = state.complete("cip-structure-generator", "CHAIN_STRUCTURE");

    CompilerNodeExecutionAdapter adapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-element-validator")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-structural-validator")).thenReturn(adapter);
    when(adapter.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()));

    state = scheduler.executeNext(state); // assembler
    assertFalse(state.presentArtifactTypes().contains("COMPILER_VALIDATION_BUNDLE"));
    state = scheduler.executeNext(state); // first validator
    assertFalse(state.presentArtifactTypes().contains("COMPILER_VALIDATION_BUNDLE"));
    state = scheduler.executeNext(state); // final validator
    assertTrue(state.presentArtifactTypes().contains("COMPILER_VALIDATION_BUNDLE"));
  }

  @Test
  void schedulerDoesNotPersistBundleWhenFinalValidatorFails() {
    ResolvedCompilerDag dag = dagWithAssemblerAndValidators();
    PlanningSchedulerState state = seededWithRequirementBrief(dag);
    state = state.complete("cip-structure-generator", "CHAIN_STRUCTURE");

    CompilerNodeExecutionAdapter adapter = mock(CompilerNodeExecutionAdapter.class);
    when(javaAdapterRegistry.require("graph-assembly")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-element-validator")).thenReturn(adapter);
    when(javaAdapterRegistry.require("cip-structural-validator")).thenReturn(adapter);
    when(adapter.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()))
        .thenReturn(new CompilerNodeExecutionResult(List.of(), List.of()))
        .thenThrow(new IllegalStateException("validator failed"));

    state = scheduler.executeNext(state);
    state = scheduler.executeNext(state);
    PlanningSchedulerState beforeFailure = state;
    assertThrows(IllegalStateException.class, () -> scheduler.executeNext(beforeFailure));
    assertFalse(state.presentArtifactTypes().contains("COMPILER_VALIDATION_BUNDLE"));
  }

  @Test
  void rejectsSameInvocationKeyTwice() {
    PlanningSchedulerState state = stateWithInvocationKey("same-key");
    assertThrows(IllegalStateException.class, () -> scheduler.recordInvocation(state, "same-key"));
  }

  @Test
  void picksStableOrderByTopologyTieAndSkillId() {
    PlanningSchedulerState state = seededWithRequirementBrief(dagForTieBreak());
    assertEquals("cip-a", scheduler.next(state).orElseThrow().skillId());
  }

  @Test
  void failsWhenMandatoryNodeHasNoDeclaredProducer() {
    PlanningSchedulerState state = seededWithRequirementBrief(dagWithUnproducibleMandatoryInput());
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> scheduler.next(state));
    assertTrue(ex.getMessage().contains("contract failure"));
  }

  @Test
  void failsClosedOnCatalogLabelMismatchForRequirementsDocument() {
    ResolvedCompilerNode analyzer =
        node(
            "cip-requirement-analyzer",
            List.of(),
            List.of("chain-requirements.yaml"),
            List.of(),
            0,
            0,
            true,
            CompilerNodeExecutionMode.PRE_SATISFIED,
            null);
    ResolvedCompilerNode pattern =
        node(
            "cip-pattern-selector",
            List.of("Chain Requirements Document"),
            List.of("pattern-selection.yaml"),
            List.of("cip-requirement-analyzer"),
            1,
            0,
            true,
            CompilerNodeExecutionMode.LLM_SKILL,
            null);
    PlanningSchedulerState state =
        seededWithRequirementBrief(new ResolvedCompilerDag(List.of(analyzer, pattern), List.of(), "mismatch"));

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> scheduler.next(state));
    assertTrue(ex.getMessage().contains("CHAIN REQUIREMENTS DOCUMENT"));
  }

  @Test
  void failsWhenDagContainsCycle() {
    PlanningSchedulerState state = seededWithRequirementBrief(dagWithCycle());
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> scheduler.next(state));
    assertTrue(ex.getMessage().contains("cycle"));
  }

  @Test
  void failsAfterSixtyFourInvocations() {
    PlanningSchedulerState state =
        new PlanningSchedulerState(
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            Set.of(REQUIREMENT_BRIEF),
            Set.of(),
            Set.of(),
            Map.of(),
            64,
            0);
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> scheduler.recordInvocation(state, "k-65"));
    assertTrue(ex.getMessage().contains("64"));
  }

  @Test
  void failsAfterThirtyTwoGraphRevisions() {
    PlanningSchedulerState state =
        new PlanningSchedulerState(
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            Set.of(REQUIREMENT_BRIEF),
            Set.of(),
            Set.of(),
            Map.of(),
            0,
            32);
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> scheduler.bumpGraphRevision(state));
    assertTrue(ex.getMessage().contains("32"));
  }

  @Test
  void dispatchesLlmSkillThroughSkillRegistry() {
    SkillExecutor executor = new FakeSkillExecutor("cip-pattern-selector");
    when(skillRegistry.require("cip-pattern-selector")).thenReturn(executor);
    PlanningSchedulerState state = seededWithRequirementBrief(dagWithSingleLlmNode());

    PlanningSchedulerState next = scheduler.executeNext(state);

    assertTrue(next.completedSkillIds().contains("cip-pattern-selector"));
    assertTrue(next.presentArtifactTypes().contains(SELECTED_PATTERN));
    verify(skillRegistry).require("cip-pattern-selector");
    verifyNoInteractions(javaAdapterRegistry);
  }

  private static PlanningSchedulerState seededWithRequirementBrief(ResolvedCompilerDag dag) {
    return new PlanningSchedulerState(
        dag,
        Set.of(REQUIREMENT_BRIEF),
        Set.of("cip-requirement-analyzer"),
        Set.of(),
        Map.of(),
        0,
        0);
  }

  private static PlanningSchedulerState stateReadyForVirtualOrchestrator() {
    ResolvedCompilerNode virtual =
        node(
            "cip-chain-generator",
            List.of(ELEMENT_SKELETON),
            List.of("CHAIN_PLAN_GRAPH"),
            List.of("cip-pattern-selector"),
            2,
            0,
            true,
            CompilerNodeExecutionMode.VIRTUAL_ORCHESTRATOR,
            null);
    ResolvedCompilerDag dag = new ResolvedCompilerDag(List.of(virtual), List.of(), "virtual");
    return new PlanningSchedulerState(
        dag,
        Set.of(REQUIREMENT_BRIEF, ELEMENT_SKELETON),
        Set.of("cip-requirement-analyzer", "cip-pattern-selector"),
        Set.of(),
        Map.of(),
        0,
        0);
  }

  private static PlanningSchedulerState stateReadyForAssembler() {
    ResolvedCompilerNode assembler =
        node(
            "cip-chain-assembler",
            List.of("CHAIN_STRUCTURE"),
            List.of(GRAPH_ASSEMBLY_RESULT),
            List.of("cip-structure-generator"),
            3,
            0,
            true,
            CompilerNodeExecutionMode.JAVA_ADAPTER,
            "graph-assembly");
    ResolvedCompilerDag dag = new ResolvedCompilerDag(List.of(assembler), List.of(), "assembler");
    return new PlanningSchedulerState(
        dag,
        Set.of(REQUIREMENT_BRIEF, "CHAIN_STRUCTURE"),
        Set.of("cip-requirement-analyzer", "cip-structure-generator"),
        Set.of(),
        Map.of(),
        0,
        0);
  }

  private PlanningSchedulerState stateWithInvocationKey(String key) {
    return new PlanningSchedulerState(
        new ResolvedCompilerDag(List.of(), List.of(), "invocation"),
        Set.of(REQUIREMENT_BRIEF),
        Set.of(),
        Set.of(key),
        Map.of(),
        1,
        0);
  }

  private static ResolvedCompilerDag dagForPrefixFlow() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-pattern-selector",
                List.of(REQUIREMENT_BRIEF),
                List.of(SELECTED_PATTERN, ELEMENT_SKELETON),
                List.of("cip-requirement-analyzer"),
                0,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null),
            node(
                "cip-naming-generator",
                List.of(ELEMENT_SKELETON),
                List.of(NAMING_MANIFEST),
                List.of("cip-pattern-selector"),
                1,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null),
            node(
                "cip-trigger-generator",
                List.of(NAMING_MANIFEST),
                List.of(CONFIGURED_TRIGGER_SET),
                List.of("cip-naming-generator"),
                2,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null)),
        List.of(),
        "prefix");
  }

  private static ResolvedCompilerDag dagForTieBreak() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-b",
                List.of(REQUIREMENT_BRIEF),
                List.of(SELECTED_PATTERN),
                List.of("cip-requirement-analyzer"),
                1,
                1,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null),
            node(
                "cip-a",
                List.of(REQUIREMENT_BRIEF),
                List.of(ELEMENT_SKELETON),
                List.of("cip-requirement-analyzer"),
                1,
                1,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null)),
        List.of(),
        "tie");
  }

  private static ResolvedCompilerDag dagWithUnproducibleMandatoryInput() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-mandatory-validator",
                List.of(PRE_BUILD_VALIDATION),
                List.of("COMPILER_VALIDATION_BUNDLE"),
                List.of(),
                0,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "validator")),
        List.of(),
        "blocked");
  }

  private static ResolvedCompilerDag dagWithCycle() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-a",
                List.of(REQUIREMENT_BRIEF),
                List.of(SELECTED_PATTERN),
                List.of("cip-b"),
                0,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null),
            node(
                "cip-b",
                List.of(SELECTED_PATTERN),
                List.of(ELEMENT_SKELETON),
                List.of("cip-a"),
                1,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null)),
        List.of(),
        "cycle");
  }

  private static ResolvedCompilerDag dagWithSingleLlmNode() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-pattern-selector",
                List.of(REQUIREMENT_BRIEF),
                List.of(SELECTED_PATTERN),
                List.of("cip-requirement-analyzer"),
                0,
                0,
                true,
                CompilerNodeExecutionMode.LLM_SKILL,
                null)),
        List.of(),
        "single-llm");
  }

  private static ResolvedCompilerDag dagWithAssemblerAndValidators() {
    return new ResolvedCompilerDag(
        List.of(
            node(
                "cip-chain-assembler",
                List.of("CHAIN_STRUCTURE"),
                List.of("GRAPH_ASSEMBLY_RESULT"),
                List.of("cip-structure-generator"),
                1,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "graph-assembly"),
            node(
                "cip-element-validator",
                List.of("GRAPH_ASSEMBLY_RESULT"),
                List.of("PRE_BUILD_VALIDATION"),
                List.of("cip-chain-assembler"),
                2,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "cip-element-validator"),
            node(
                "cip-structural-validator",
                List.of("GRAPH_ASSEMBLY_RESULT", "PRE_BUILD_VALIDATION"),
                List.of("COMPILER_VALIDATION_BUNDLE"),
                List.of("cip-element-validator"),
                3,
                0,
                true,
                CompilerNodeExecutionMode.JAVA_ADAPTER,
                "cip-structural-validator")),
        List.of(),
        "assembler-validators");
  }

  private static ResolvedCompilerNode node(
      String skillId,
      List<String> consumes,
      List<String> produces,
      List<String> dependsOn,
      int level,
      int tie,
      boolean mandatory,
      CompilerNodeExecutionMode mode,
      String adapterId) {
    return new ResolvedCompilerNode(
        skillId,
        "Planning",
        null,
        consumes,
        produces,
        dependsOn,
        "captureTool",
        List.of(),
        List.of(),
        true,
        List.of(),
        level,
        tie,
        mandatory,
        mode,
        adapterId);
  }

  private static ResolvedCompilerNode node(PlanningSchedulerState state, String skillId) {
    return state.dag().nodes().stream().filter(node -> node.skillId().equals(skillId)).findFirst().orElseThrow();
  }

  private static final class FakeSkillExecutor implements SkillExecutor {
    private final String skillId;

    private FakeSkillExecutor(String skillId) {
      this.skillId = skillId;
    }

    @Override
    public String skillId() {
      return skillId;
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public java.util.Set<SkillArtifactType> requiredInputs() {
      return java.util.Set.of(SkillArtifactType.REQUIREMENT_BRIEF);
    }

    @Override
    public java.util.Set<SkillArtifactType> outputTypes() {
      return java.util.Set.of(SkillArtifactType.SELECTED_PATTERN);
    }

    @Override
    public io.smallrye.mutiny.Uni<SkillExecutionResult> run(
        SkillRunContext context, SkillWorkspace workspace) {
      SkillArtifact artifact =
          SkillArtifact.of(
              SkillArtifactType.SELECTED_PATTERN,
              skillId,
              new SkillArtifactPayload.SelectedPatternPayload(
                  new org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern(
                      "GP-01", "Pattern", "reason", null, List.of(), "summary")));
      return io.smallrye.mutiny.Uni.createFrom()
          .item(SkillExecutionResult.completed(List.of(artifact), "ok"));
    }
  }
}
