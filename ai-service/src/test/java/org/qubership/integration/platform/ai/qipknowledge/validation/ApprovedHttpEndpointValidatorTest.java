package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;

class ApprovedHttpEndpointValidatorTest {
  private static final RequirementBrief BRIEF = new RequirementBrief(
      "HTTP", List.of(), List.of(), List.of(), List.of(), "HTTP", null, "", List.of(),
      List.of(entry("entry-a", "/approved/{id}"), entry("entry-b", "/second")),
      List.of(), List.of(), List.of());

  @Test
  void acceptsExactUrlsWithSemanticBindingsAndReversedGraphOrder() {
    assertTrue(validate(graph()).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"/approved/{id}\"", "/other", "/APPROVED/{id}", "/approved/{id}/"})
  void rejectsUrlDriftWithPreciseRepairEvidence(String path) {
    var issues = validate(graph().withNodeProperty("node-a", "contextPath", path));
    assertEquals(1, issues.size());
    var issue = issues.getFirst();
    assertEquals("HTTP_ENDPOINT_MISMATCH", issue.findingCode());
    assertEquals(List.of("node-a"), issue.affectedNodeIds());
    assertEquals("/properties/contextPath", issue.fieldPointer());
    assertEquals("cip-http-trigger-endpoint-generator", issue.ownerCapabilityId());
    assertTrue(issue.message().contains("source-entry-a"));
    assertTrue(issue.message().contains("generated=" + path));
    assertTrue(issue.suggestedFix().contains("/approved/{id}"));
  }

  @Test
  void rejectsMethodDriftAndMissingPath() {
    assertEquals(1, validate(graph().withNodeProperty("node-a", "httpMethodRestrict", "POST")).size());
    assertEquals(1, validate(graph().withNodeProperty("node-a", "contextPath", null)).size());
  }

  @Test
  void validatesCatalogOperationPathInsteadOfTheUnusedCustomPath() {
    var catalog = graph().withNodeProperty("node-a", "integrationOperationId", "operation-1")
        .withNodeProperty("node-a", "integrationOperationPath", "/approved/{id}")
        .withNodeProperty("node-a", "contextPath", "/unused");
    assertTrue(validate(catalog).isEmpty());
    var issue = validate(catalog.withNodeProperty("node-a", "integrationOperationPath", "/wrong")).getFirst();
    assertEquals("/properties/integrationOperationPath", issue.fieldPointer());
    assertEquals("cip-trigger-generator", issue.ownerCapabilityId());
  }

  @Test
  void doesNotGuessWhenTheSemanticBindingIsMissingOrAmbiguous() {
    var missing = SemanticFixtures.revision(List.of(SemanticFixtures.entry("entry-b", "node-b")));
    assertThrows(IllegalStateException.class, () -> ApprovedHttpEndpointValidator.validate(graph(), BRIEF, missing));
    var duplicate = SemanticFixtures.revision(List.of(
        SemanticFixtures.entry("entry-a", "node-a"), SemanticFixtures.entry("entry-a", "node-b")));
    assertThrows(IllegalStateException.class, () -> ApprovedHttpEndpointValidator.validate(graph(), BRIEF, duplicate));
  }

  private static List<ValidationIssue> validate(ChainPlanGraph graph) {
    return ApprovedHttpEndpointValidator.validate(graph, BRIEF, SemanticFixtures.revision(List.of(
        SemanticFixtures.entry("entry-a", "node-a"), SemanticFixtures.entry("entry-b", "node-b"))));
  }

  private static RequirementEntryPoint entry(String id, String path) {
    return new RequirementEntryPoint(id, "source-" + id, "http-trigger", "", "GET", path, "");
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph("1.0", new ChainSection("chain-1", "HTTP"), List.of(
        node("node-b", "/second"), node("node-a", "/approved/{id}")), List.of());
  }

  private static ChainPlanNode node(String id, String path) {
    return new ChainPlanNode(id, "http-trigger", "HTTP", null, null, List.of(
        new PlanProperty("contextPath", path), new PlanProperty("httpMethodRestrict", "GET")));
  }
}
