package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class GraphPatchPreviewValidatorTest {

  private GraphPatchPreviewValidator validator;
  private CanonicalGraphDigest digest;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper();
    DeterministicElementSchemaService schemaService =
        DeterministicElementSchemaService.createForUnitTests(mapper);
    digest = new CanonicalGraphDigest(mapper);
    validator =
        new GraphPatchPreviewValidator(
            new ValidatedGraphPatchApplier(new GraphPatchOwnershipValidator(), new GraphPatchApplier()),
            new GraphPatchApplier(),
            new ChainPlanGraphValidator(schemaService),
            new GeneratorReadinessEvaluator(schemaService, mapper),
            digest);
  }

  @Test
  void captureRejectsIncompleteScriptBodiesWhenReadinessDeclaresSignal() {
    ChainPlanGraph base = graphWithScriptMissingBody();
    GraphPatchExecutionContext context = context(base);
    GraphPatch patch =
        new GraphPatch(
            "incomplete",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "script-1",
                    new PlanProperty("script", ""))),
            List.of(),
            List.of(),
            "empty script");

    GraphPatchPreviewValidator.GraphPatchPreviewResult result =
        validator.validate(base, patch, context, List.of("script_nodes_missing_body"));

    assertFalse(result.pass());
    assertTrue(result.readinessGaps().contains("script_nodes_missing_body"));
  }

  @Test
  void harvestAgreesWithCapturePass() {
    ChainPlanGraph base = graphWithScriptMissingBody();
    GraphPatchExecutionContext context = context(base);
    List<String> readiness = List.of("script_nodes_missing_body");
    GraphPatch patch =
        new GraphPatch(
            "complete",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "script-1",
                    new PlanProperty("script", "return 200"))),
            List.of(),
            List.of(),
            "fill script");

    GraphPatchPreviewValidator.GraphPatchPreviewResult capture =
        validator.validate(base, patch, context, readiness);
    GraphPatchPreviewValidator.GraphPatchPreviewResult harvest =
        validator.validate(base, patch, context, readiness);

    assertTrue(capture.ownershipResult().valid(), capture.ownershipResult().summary());
    assertTrue(
        capture.structuralValidation().isEmpty(),
        String.join("; ", capture.structuralValidation()));
    assertTrue(capture.pass());
    assertTrue(harvest.pass());
    assertEquals(capture.inputGraphDigest(), harvest.inputGraphDigest());
    assertEquals(context.inputGraphDigest(), harvest.inputGraphDigest());
  }

  @Test
  void harvestRejectsIncompleteScriptWhenReadinessMatchesCapture() {
    ChainPlanGraph base = graphWithScriptMissingBody();
    GraphPatchExecutionContext context = context(base);
    List<String> readiness = List.of("script_nodes_missing_body");
    GraphPatch patch =
        new GraphPatch(
            "incomplete",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "script-1",
                    new PlanProperty("script", ""))),
            List.of(),
            List.of(),
            "empty script");

    GraphPatchPreviewValidator.GraphPatchPreviewResult capture =
        validator.validate(base, patch, context, readiness);
    GraphPatchPreviewValidator.GraphPatchPreviewResult harvestWithSignals =
        validator.validate(base, patch, context, readiness);
    GraphPatchPreviewValidator.GraphPatchPreviewResult harvestWithoutSignals =
        validator.validate(base, patch, context, List.of());

    assertFalse(capture.pass());
    assertFalse(harvestWithSignals.pass());
    assertTrue(harvestWithoutSignals.pass(), "empty readiness must not mask completeness gaps");
    assertEquals(capture.readinessGaps(), harvestWithSignals.readinessGaps());
  }

  @Test
  void digestMismatchFailsPreviewPass() {
    ChainPlanGraph base = graphWithScriptMissingBody();
    GraphPatchExecutionContext mismatched =
        new GraphPatchExecutionContext(
            "run-1",
            "cip-script-generator",
            "req",
            "not-the-base-digest",
            "compiler",
            "24.4",
            new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary"),
            List.of(),
            base,
            ownership(),
            "");
    GraphPatchPreviewValidator.GraphPatchPreviewResult preview =
        validator.validate(
            base,
            new GraphPatch(
                "p",
                "cip-script-generator",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "noop"),
            mismatched,
            List.of());
    assertFalse(preview.pass());
    assertTrue(GraphPatchPreviewValidator.digestMismatch(mismatched, preview.inputGraphDigest()));
    assertFalse(ObjectsEquals(preview.inputGraphDigest(), mismatched.inputGraphDigest()));
  }

  @Test
  void structuralFailureFailsPreview() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "demo"),
            List.of(
                new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("e1", "http-trigger-1", "script-1", null)));
    GraphPatch patch =
        new GraphPatch(
            "p",
            "cip-script-generator",
            List.of(
                new org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "orphan", "script", "Orphan", "missing-parent", null, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "add orphan");
    GraphPatchPreviewValidator.GraphPatchPreviewResult result =
        validator.validate(base, patch, context(base), List.of());
    assertFalse(result.pass());
    assertFalse(result.structuralValidation().isEmpty());
  }

  private static boolean ObjectsEquals(String a, String b) {
    return java.util.Objects.equals(a, b);
  }

  private GraphPatchExecutionContext context(ChainPlanGraph base) {
    return new GraphPatchExecutionContext(
        "run-1",
        "cip-script-generator",
        "req",
        digest.sha256(base),
        "compiler",
        "24.4",
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary"),
        List.of(),
        base,
        ownership(),
        "");
  }

  private static GraphPatchOwnershipPolicy ownership() {
    return new GraphPatchOwnershipPolicy(
        true, true, Set.of("script"), Set.of(), Map.of("script", Set.of("script")));
  }

  private static ChainPlanGraph graphWithScriptMissingBody() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "http-trigger-1", "script-1", null)));
  }
}
