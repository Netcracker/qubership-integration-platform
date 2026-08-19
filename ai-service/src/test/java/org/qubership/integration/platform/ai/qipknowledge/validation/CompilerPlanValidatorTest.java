package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.addon.MaterializationRequirementsLoader;
import org.qubership.integration.platform.ai.qipknowledge.validation.MaterializationRequirementsValidator;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemQipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;

class CompilerPlanValidatorTest {

  private CompilerPlanValidator validator;

  @BeforeEach
  void setUp(@TempDir Path outputDir) throws Exception {
    QipKnowledgePackTestSupport.configureAddonPackRoot();
    QipKnowledgePackBuildGenerator.generate(QipKnowledgePackFixturePaths.packRoot(), outputDir);
    FilesystemQipKnowledgePackRepository repository =
        new FilesystemQipKnowledgePackRepository(
            outputDir, QipKnowledgePackFixturePaths.packVersion());
    CompilerSkillAddonRepository addonRepository =
        CompilerSkillAddonRepository.forFilesystem(
            outputDir, QipKnowledgePackFixturePaths.packVersion(), getClass().getClassLoader());
    DeterministicElementSchemaService schemaService = mock(DeterministicElementSchemaService.class);
    when(schemaService.allowedPatchPropertyKeys(any())).thenReturn(Set.of());
    validator =
        new CompilerPlanValidator(
            new ChainPlanGraphValidator(schemaService),
            new SchemaResourceLoader(),
            new ChainElementCatalog(new ObjectMapper()),
            new MaterializationRequirementsValidator(
                new MaterializationRequirementsLoader(addonRepository)));
  }

  @Test
  void flagsDeprecatedTryCatchFinallyAsVrL001Blocker() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("demo-chain", "Demo"),
                    List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode(
                            "n2", "try-catch-finally", "Error Handling", null, null, List.of())),
                    List.of(new ChainPlanEdge("e1", "n1", "n2", null)))));

    assertFalse(result.valid());
    assertTrue(result.hasBlockingIssues());
    ValidationIssue issue =
        result.issues().stream()
            .filter(i -> "VR-L-001".equals(i.ruleRefs().get(0).refId()))
            .findFirst()
            .orElseThrow();
    assertEquals("n2", issue.affectedNodeIds().get(0));
    assertEquals(ValidationSeverity.BLOCKER, issue.severity());
  }

  @Test
  void passesGraphWithHttpTriggerAndReachableBody() {
    ValidationResult result =
        validator.validate(new PlanGraphValidationInput(validHttpGraph()));

    assertTrue(result.valid());
    assertFalse(result.hasBlockingIssues());
    assertEquals("Plan validation passed", result.summary());
  }

  @Test
  void failsWhenNoTrigger() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("demo-chain", "Demo"),
                    List.of(new ChainPlanNode("n2", "script", "Script", null, null, List.of())),
                    List.of())));

    assertFalse(result.valid());
    assertEquals("VR-G-001", result.issues().get(0).ruleRefs().get(0).refId());
  }

  @Test
  void parentedTryCatchWrapKeepsScriptReachableWithoutInnerEdges() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(wrappedHttpGraph("n2", "try-shell")));

    assertTrue(
        result.issues().stream()
            .noneMatch(issue -> issue.message().contains("is not reachable from any trigger")),
        result.issues().toString());
  }

  @Test
  void retargetOnlyWrapLeavesScriptUnreachableUntilParented() {
    ValidationResult result =
        validator.validate(new PlanGraphValidationInput(wrappedHttpGraph("n2", null)));

    assertTrue(
        result.issues().stream()
            .anyMatch(
                issue -> issue.message().equals("Node 'n2' is not reachable from any trigger")),
        result.issues().toString());
  }

  @Test
  void failsOrphanNodeNotReachableFromTrigger() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("demo-chain", "Demo"),
                    List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode("n2", "script", "Script", null, null, List.of()),
                        new ChainPlanNode("n3", "script", "Orphan", null, null, List.of())),
                    List.of(new ChainPlanEdge("e1", "n1", "n2", null)))));

    assertFalse(result.valid());
  }

  @Test
  void structuralDuplicateNodeIdProducesBlockerWithoutVrRef() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("demo-chain", "Demo"),
                    List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode("n1", "script", "Duplicate", null, null, List.of())),
                    List.of())));

    assertFalse(result.valid());
    assertTrue(result.issues().stream().anyMatch(i -> i.ruleRefs().isEmpty()));
    assertTrue(
        result.issues().stream().anyMatch(i -> i.message().contains("duplicate nodeId")));
  }

  @Test
  void flagsInvalidElementTypeSuffix() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("demo-chain", "Demo"),
                    List.of(
                        new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of()),
                        new ChainPlanNode(
                            "n2", "service-call+", "Backend", null, null, List.of())),
                    List.of(new ChainPlanEdge("e1", "n1", "n2", null)))));

    assertFalse(result.valid());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.message().contains("service-call+")));
  }

  @Test
  void flagsMissingV2AliasWhenCatalogTypeDoesNotExist() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("demo-chain", "Demo"),
                    List.of(
                        new ChainPlanNode("n1", "http-trigger-2", "Trigger", null, null, List.of()),
                        new ChainPlanNode("n2", "script", "Script", null, null, List.of())),
                    List.of(new ChainPlanEdge("e1", "n1", "n2", null)))));

    assertFalse(result.valid());
    assertTrue(
        result.issues().stream()
            .anyMatch(i -> i.message().contains("Unknown element type 'http-trigger-2'")));
  }

  @Test
  void failsWhenIfNodeMissingCondition() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("fortune", "Fortune"),
                    List.of(
                        new ChainPlanNode(
                            "n1",
                            "http-trigger",
                            "Trigger",
                            null,
                            null,
                            List.of(
                                new PlanProperty("contextPath", "/fortune"),
                                new PlanProperty("httpMethodRestrict", "GET"))),
                        new ChainPlanNode("n2", "if", "If RU", null, null, List.of())),
                    List.of())));

    assertFalse(result.valid());
    assertTrue(
        result.issues().stream().anyMatch(i -> i.message().contains("missing required materialization property 'condition'")));
  }

  @Test
  void failsWhenScriptNodeMissingBody() {
    ValidationResult result =
        validator.validate(
            new PlanGraphValidationInput(
                new ChainPlanGraph(
                    "1.0",
                    new ChainSection("demo-chain", "Demo"),
                    List.of(
                        new ChainPlanNode(
                            "n1",
                            "http-trigger",
                            "Trigger",
                            null,
                            null,
                            List.of(
                                new PlanProperty("contextPath", "/demo"),
                                new PlanProperty("httpMethodRestrict", "GET"))),
                        new ChainPlanNode("n2", "script", "Script", null, null, List.of())),
                    List.of(new ChainPlanEdge("e1", "n1", "n2", null)))));

    assertFalse(result.valid());
    assertTrue(result.hasBlockingIssues());
    assertTrue(
        result.issues().stream()
            .anyMatch(
                i ->
                    i.message().contains("missing required materialization property 'script'")
                        && "cip-script-generator".equals(i.ownerCapabilityId())));
  }

  private static ChainPlanGraph validHttpGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(
            new ChainPlanNode(
                "n1",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/demo"),
                    new PlanProperty("httpMethodRestrict", "GET"))),
            new ChainPlanNode(
                "n2",
                "script",
                "Script",
                null,
                null,
                List.of(new PlanProperty("script", "return 'ok';")))),
        List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
  }

  private static ChainPlanGraph wrappedHttpGraph(String scriptId, String scriptParentId) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo-chain", "Demo"),
        List.of(
            new ChainPlanNode(
                "n1",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(
                    new PlanProperty("contextPath", "/demo"),
                    new PlanProperty("httpMethodRestrict", "GET"))),
            new ChainPlanNode(
                "eh-wrap", "try-catch-finally-2", "Error handling", null, null, List.of()),
            new ChainPlanNode("try-shell", "try-2", "Try", "eh-wrap", null, List.of()),
            new ChainPlanNode(
                "catch-shell",
                "catch-2",
                "Catch",
                "eh-wrap",
                null,
                List.of(
                    new PlanProperty("exception", "java.lang.Exception"),
                    new PlanProperty("priority", "0"))),
            new ChainPlanNode(
                scriptId,
                "script",
                "Script",
                scriptParentId,
                null,
                List.of(new PlanProperty("script", "return 'ok';")))),
        List.of(new ChainPlanEdge("e1", "n1", "eh-wrap", null)));
  }

  private static ChainPlanNode findNode(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> node.nodeId().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }
}
