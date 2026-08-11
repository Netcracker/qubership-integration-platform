package org.qubership.integration.platform.ai.skill.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

class InMemorySkillWorkspaceStoreTest {

  private static final String CONVERSATION_ID = "conv-1";

  private ChainPlanStore chainPlanStore;
  private InMemorySkillWorkspaceStore store;

  @BeforeEach
  void setUp() {
    chainPlanStore = new ChainPlanStore();
    store = new InMemorySkillWorkspaceStore(chainPlanStore);
  }

  @Test
  void seedImplementArtifactsFromBundleIsDisabledAfterCutover() {
    PlanCompilationTestSupport.Runtime runtime = PlanCompilationTestSupport.memory();
    PlanCompilationTestSupport.storeApprovedDesign(
        runtime, CONVERSATION_ID, "vision", "Approved design");
    PlanCompilationTestSupport.storeApprovedPlan(runtime, CONVERSATION_ID, "1. Analyze");
    PlanCompilationTestSupport.storeCurrentBundle(runtime, CONVERSATION_ID, sampleGraph());

    assertFalse(store.seedImplementArtifactsFromBundle(CONVERSATION_ID));
    assertTrue(store.getOrCreate(CONVERSATION_ID).get(SkillArtifactType.CHAIN_PLAN_GRAPH).isEmpty());
  }

  @Test
  void putAndGetArtifact() {
    SkillWorkspace ws = store.getOrCreate(CONVERSATION_ID);
    SkillArtifact artifact =
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "seed",
            new SkillArtifactPayload.RawUserRequestPayload("hello", List.of()));
    store.putArtifact(CONVERSATION_ID, artifact);

    assertTrue(ws.get(SkillArtifactType.RAW_USER_REQUEST).isPresent());
    assertEquals(
        "hello", ((SkillArtifactPayload.RawUserRequestPayload) artifact.payload()).effectiveText());
  }

  @Test
  void chainPlanGraphSyncsToChainPlanStore() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("greetings", "Greetings"),
            List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());

    store.putArtifact(
        CONVERSATION_ID,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "cip-chain-generator",
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));

    assertTrue(chainPlanStore.get(CONVERSATION_ID).isPresent());
    assertEquals("greetings", chainPlanStore.get(CONVERSATION_ID).get().chain().name());
  }

  @Test
  void canRunImplementSegmentRequiresExplicitWorkspaceGraph() {
    assertFalse(store.canRunImplementSegment(CONVERSATION_ID));

    store.putArtifact(
        CONVERSATION_ID,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "cip-chain-generator",
            new SkillArtifactPayload.ChainPlanGraphPayload(sampleGraph())));

    assertTrue(store.canRunImplementSegment(CONVERSATION_ID));
  }

  @Test
  void clearRemovesWorkspaceAndChainPlan() {
    store.putArtifact(
        CONVERSATION_ID,
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "cip-chain-generator",
            new SkillArtifactPayload.ChainPlanGraphPayload(
                new ChainPlanGraph("1.0", new ChainSection("x", "x"), List.of(), List.of()))));
    store.clear(CONVERSATION_ID);

    assertTrue(store.getOrCreate(CONVERSATION_ID).presentTypes().isEmpty());
    assertTrue(chainPlanStore.get(CONVERSATION_ID).isEmpty());
  }

  private static ChainPlanGraph sampleGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("greetings", "Greetings"),
        List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }
}
