package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphOwnershipFact;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;

class GraphAssemblyServiceTest {

  @Test
  void replayProducesRecordedFinalDigest() {
    CanonicalGraphDigest digest = new CanonicalGraphDigest(new ObjectMapper());
    GraphAssemblyService assembler = new GraphAssemblyService(digest);
    ChainStructure structure = new ChainStructure(graph(), List.of(), List.of());

    GraphAssemblyResult result = assembler.assemble(structure, List.of());

    assertEquals(digest.sha256(result.graph()), result.graphDigest());
    assertEquals(List.of(), result.orderedPatchReferences());
    assertTrue(result.rejectedPatches().isEmpty());
  }

  @Test
  void assembleFromGraphAcceptsOrderedLedger() {
    CanonicalGraphDigest digest = new CanonicalGraphDigest(new ObjectMapper());
    GraphAssemblyService assembler = new GraphAssemblyService(digest);
    CompilationArtifacts.Reference ref =
        new CompilationArtifacts.Reference(
            CompilationArtifacts.Kind.GRAPH_PATCH_ARTIFACT, "art-1", "hash-1");
    GraphOwnershipFact fact =
        new GraphOwnershipFact(
            "cip-script-generator",
            "APPLY_GRAPH_PATCH",
            "patch-1",
            "compiler-node-ownership:cip-script-generator");

    GraphAssemblyResult result = assembler.assembleFromGraph(graph(), List.of(ref), List.of(fact));

    assertEquals(digest.sha256(result.graph()), result.graphDigest());
    assertEquals(List.of(ref), result.orderedPatchReferences());
    assertEquals(List.of(fact), result.ownershipFacts());
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("greetings", "Greetings"),
        List.of(
            new ChainPlanNode(
                "trigger",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/greetings"),
                    new PlanProperty("httpMethodRestrict", "GET"))),
            new ChainPlanNode(
                "script",
                "script",
                "Script",
                null,
                null,
                List.of(new PlanProperty("script", "return \"ok\"")))),
        List.of());
  }
}
