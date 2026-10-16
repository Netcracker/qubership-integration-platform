package org.qubership.integration.platform.ai.qipknowledge.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class PlanningArtifactSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void roundTripsRequirementBrief() throws Exception {
    QipKnowledgePackVersion version = new QipKnowledgePackVersion("v1_0_1", "v1_0_1");
    QipKnowledgeCitation citation =
        new QipKnowledgeCitation(
            "D-001",
            QipKnowledgeRefType.DECISION_NODE,
            "knowledge/ai/CORPORATE_DECISION_TREE.yaml",
            version,
            "id: D-001");
    RequirementBrief original =
        new RequirementBrief(
            "Build order API chain",
            List.of("orderId"),
            List.of("RBAC required"),
            List.of("HTTP trigger"),
            List.of(citation),
            "Order API with RBAC");

    RequirementBrief restored =
        objectMapper.readValue(objectMapper.writeValueAsString(original), RequirementBrief.class);

    assertEquals(original.summary(), restored.summary());
    assertEquals(original.goal(), restored.goal());
    assertEquals("D-001", restored.citations().get(0).refId());
  }

  @Test
  void roundTripsDecisionTrace() throws Exception {
    DecisionTrace original =
        new DecisionTrace(
            List.of(
                new DecisionStep(
                    "D-001",
                    "Which trigger?",
                    "http-trigger",
                    "External API",
                    List.of("async-api-trigger"))),
            List.of(),
            "HTTP trigger selected");

    DecisionTrace restored =
        objectMapper.readValue(objectMapper.writeValueAsString(original), DecisionTrace.class);

    assertEquals(original.summary(), restored.summary());
    assertEquals("D-001", restored.steps().get(0).decisionId());
  }

  @Test
  void roundTripsSelectedPattern() throws Exception {
    QipKnowledgePackVersion version = new QipKnowledgePackVersion("v1_0_1", "v1_0_1");
    SelectedPattern original =
        new SelectedPattern(
            "GP-01",
            "Protected Request-Response",
            "Matches HTTP API with RBAC",
            new QipKnowledgeCitation(
                "GP-01",
                QipKnowledgeRefType.GOLDEN_PATTERN,
                "knowledge/ai/golden-reference-library.md",
                version,
                "GP-01"),
            List.of("cip-error-handling-generator"),
            "Use GP-01");

    SelectedPattern restored =
        objectMapper.readValue(objectMapper.writeValueAsString(original), SelectedPattern.class);

    assertEquals(original.summary(), restored.summary());
    assertEquals("GP-01", restored.patternId());
    assertEquals("GP-01", restored.citation().refId());
  }

  @Test
  void roundTripsIdsDocument() throws Exception {
    IdsDocument original =
        new IdsDocument(
            "1",
            IdsDocument.Mode.PROVIDED,
            "user-upload",
            "source-hash",
            "flow-hash",
            "n/a",
            "# Integration flow for CIP Chain - Orders\n");

    IdsDocument restored =
        objectMapper.readValue(objectMapper.writeValueAsString(original), IdsDocument.class);

    assertEquals(original, restored);
    assertEquals(IdsDocument.Mode.PROVIDED, restored.mode());
  }
}
