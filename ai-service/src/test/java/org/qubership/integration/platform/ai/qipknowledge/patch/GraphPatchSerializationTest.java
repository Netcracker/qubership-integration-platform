package org.qubership.integration.platform.ai.qipknowledge.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class GraphPatchSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void roundTripsNodeAddPatch() throws Exception {
    GraphPatch original =
        new GraphPatch(
            "patch-1",
            "cip-error-handling-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "try-1", "try-2", "Try", null, null, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Add try-catch container");

    GraphPatch restored = objectMapper.readValue(objectMapper.writeValueAsString(original), GraphPatch.class);

    assertEquals("patch-1", restored.patchId());
    assertEquals(GraphPatchOperation.ADD, restored.nodePatches().get(0).operation());
    assertEquals("try-1", restored.nodePatches().get(0).node().nodeId());
  }

  @Test
  void roundTripsEdgeAddPatch() throws Exception {
    GraphPatch original =
        new GraphPatch(
            "patch-2",
            "cip-routing-generator",
            List.of(),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("edge-1", "n1", "n2", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Connect nodes");

    GraphPatch restored = objectMapper.readValue(objectMapper.writeValueAsString(original), GraphPatch.class);

    assertEquals("edge-1", restored.edgePatches().get(0).edge().edgeId());
  }

  @Test
  void roundTripsPropertyPatchWithScript() throws Exception {
    QipKnowledgePackVersion version = new QipKnowledgePackVersion("v1_0_1", "v1_0_1");
    QipKnowledgeCitation citation =
        new QipKnowledgeCitation(
            "GEN-04",
            QipKnowledgeRefType.GENERATOR_CONTRACT,
            "knowledge/ai/GENERATOR_CONTRACTS.md",
            version,
            "GEN-04");
    GraphPatch original =
        new GraphPatch(
            "patch-3",
            "cip-error-handling-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.UPDATE,
                    "script-1",
                    new PlanProperty("script", "exchange.setProperty('ok', true)"))),
            List.of(),
            List.of(citation),
            "Set error response script");

    GraphPatch restored = objectMapper.readValue(objectMapper.writeValueAsString(original), GraphPatch.class);

    assertEquals("script", restored.propertyPatches().get(0).property().key());
    assertEquals("GEN-04", restored.usedKnowledgeRefs().get(0).refId());
  }
}
