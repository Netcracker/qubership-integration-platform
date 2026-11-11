package org.qubership.integration.platform.ai.qipknowledge.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;

class GraphPatchArtifactFactoryTest {

  private GraphPatchArtifactFactory factory;

  @BeforeEach
  void setUp() {
    factory = new GraphPatchArtifactFactory(new CanonicalGraphDigest(new ObjectMapper()));
  }

  @Test
  void persistsEmptyPatchWithStableDigest() {
    ChainPlanGraph graph = graph();
    GraphPatchExecutionContext context = TestContexts.context(graph);

    var artifact = factory.create(context, emptyPatch("cip-routing-generator"), graph);

    assertEquals(PatchApplicability.NOT_APPLICABLE, artifact.applicability());
    assertEquals(artifact.baseGraphDigest(), artifact.resultGraphDigest());
  }

  @Test
  void differentAttemptIdsProduceDifferentInvocationKeys() {
    ChainPlanGraph graph = graph();
    GraphPatch empty = emptyPatch("cip-quartz-scheduler-generator");
    GraphPatchExecutionContext a = contextWithAttempt(graph, "attempt-1");
    GraphPatchExecutionContext b = contextWithAttempt(graph, "attempt-2");
    var artA = factory.create(a, empty, graph);
    var artB = factory.create(b, empty, graph);
    assertNotEquals(artA.invocationKey(), artB.invocationKey());
  }

  @Test
  void sameAttemptIdKeepsStableInvocationKeyRegardlessOfPatchPayload() {
    ChainPlanGraph graph = graph();
    GraphPatchExecutionContext ctx = contextWithAttempt(graph, "attempt-1");
    var emptyArt = factory.create(ctx, emptyPatch("cip-quartz-scheduler-generator"), graph);
    var nonEmptyArt =
        factory.create(ctx, nonEmptyPatch("cip-quartz-scheduler-generator"), graph);
    assertEquals(emptyArt.invocationKey(), nonEmptyArt.invocationKey());
  }

  @Test
  void marksApplicableWhenDigestChanges() {
    ChainPlanGraph before = graph();
    ChainPlanGraph after =
        new ChainPlanGraph(
            "1.0",
            before.chain(),
            List.of(
                before.nodes().getFirst(),
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return 200")))),
            List.of(new ChainPlanEdge("edge-1", "http-trigger-1", "script-1", null)));
    GraphPatchExecutionContext context = TestContexts.context(before);

    var artifact = factory.create(context, nonEmptyPatch("cip-script-generator"), after);

    assertEquals(PatchApplicability.APPLICABLE, artifact.applicability());
    assertNotEquals(artifact.baseGraphDigest(), artifact.resultGraphDigest());
  }

  private static GraphPatch emptyPatch(String owner) {
    return new GraphPatch("empty", owner, List.of(), List.of(), List.of(), List.of(), List.of(), "No changes");
  }

  private static GraphPatch nonEmptyPatch(String owner) {
    return new GraphPatch(
        "non-empty",
        owner,
        List.of(
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of()),
                null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Add script");
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }

  private static GraphPatchExecutionContext contextWithAttempt(
      ChainPlanGraph inputGraph, String attemptId) {
    return new GraphPatchExecutionContext(
        "run-1",
        "cip-script-generator",
        "req-1",
        "graph-1",
        "compiler-1",
        "24.4",
        new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
            "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
        List.of(),
        inputGraph,
        GraphPatchOwnershipPolicy.denyAll(),
        attemptId);
  }

  private static final class TestContexts {
    private TestContexts() {}

    private static GraphPatchExecutionContext context(ChainPlanGraph inputGraph) {
      return new GraphPatchExecutionContext(
          "run-1",
          "cip-script-generator",
          "req-1",
          "graph-1",
          "compiler-1",
          "24.4",
          new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
              "goal", List.of(), List.of(), List.of(), List.of(), "summary"),
          List.of(),
          inputGraph,
          GraphPatchOwnershipPolicy.denyAll(),
          "");
    }
  }
}
