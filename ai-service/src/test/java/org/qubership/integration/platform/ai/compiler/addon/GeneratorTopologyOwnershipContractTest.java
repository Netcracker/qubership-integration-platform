package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchExecutionContext;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

class GeneratorTopologyOwnershipContractTest {

  private AddonRuntimeMetadataParser parser;
  private ValidatedGraphPatchApplier applier;

  @BeforeEach
  void setUp() {
    parser = new AddonRuntimeMetadataParser();
    applier =
        new ValidatedGraphPatchApplier(
            new GraphPatchOwnershipValidator(), new GraphPatchApplier());
  }

  @Test
  void chainAndStructureGeneratorsOwnTopologyCapture() {
    AddonRuntimeMetadata chain = metadata("cip-chain-generator");
    AddonRuntimeMetadata structure = metadata("cip-structure-generator");

    assertEquals(CaptureTool.CAPTURE_CHAIN_PLAN, chain.captureTool());
    assertEquals(CaptureTool.CAPTURE_CHAIN_STRUCTURE, structure.captureTool());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cip-script-generator",
        "cip-transformation-generator",
        "cip-service-call-generator"
      })
  void specializedGeneratorsCannotMutateTopology(String skillId) {
    GraphPatchOwnershipPolicy ownership = ownership(skillId);

    assertFalse(ownership.mayAddNodes(), skillId + " must not add nodes");
    assertFalse(ownership.mayAddEdges(), skillId + " must not add edges");
  }

  @Test
  void serviceCallCatalogIdentityPatchIsRejected() {
    GraphPatchOwnershipPolicy ownership = ownership("cip-service-call-generator");
    GraphPatch patch =
        new GraphPatch(
            "http-service-call-catalog-binding",
            "cip-service-call-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "call-1",
                    new PlanProperty("integrationSystemId", "sys-1"))),
            List.of(),
            List.of(),
            "Model must not copy catalog identity");

    GraphPatchApplyResult result =
        applier.apply(context(graphWithServiceCall(), ownership), patch);

    assertFalse(result.validationResult().valid());
  }

  @ParameterizedTest
  @Tag("certified-pack")
  @ValueSource(strings = {"integrationOperationQueryParameters", "integrationOperationPathParameters",
      "integrationAdditionalParameters", "integrationOperationAsyncProperties", "bodyMimeType", "bodyFormData"})
  void serviceCallTransportConfigurationCanBeCaptured(String key) throws Exception {
    Path runtime = CipKnowledgePackFixturePaths.addonRoot();
    GraphPatchOwnershipPolicy policy = parser.parseAddonFile(
        runtime.resolve("skills/cip-service-call-generator.json")).ownership();
    var envelope = new ObjectMapper(new YAMLFactory()).readTree(
        Files.readString(runtime.resolve("generator-packages.yaml")));
    boolean allowedByEnvelope = false;
    for (var entry : envelope.path("ownership-envelopes")) {
      if (entry.path("skill").asText().equals("cip-service-call-generator")) {
        for (var property : entry.path("properties").path("service-call")) {
          allowedByEnvelope |= key.equals(property.asText());
        }
      }
    }
    assertTrue(allowedByEnvelope, "Transport property missing from ownership envelope: " + key);
    Object value = key.equals("bodyMimeType") ? "application/json"
        : key.equals("bodyFormData") ? List.of(Map.of("name", "status", "value", "${header.status}", "mimeType", "text/plain"))
        : Map.of("status", "${header.status}");
    GraphPatch patch = new GraphPatch("transport", "cip-service-call-generator", List.of(), List.of(),
        List.of(new PropertyPatch(GraphPatchOperation.ADD, "call-1", new PlanProperty(key, value instanceof String text ? text : new ObjectMapper().writeValueAsString(value)))),
        List.of(), List.of(), "Forward the requested transport parameter");
    var result = applier.apply(context(graphWithServiceCall(), policy), patch);
    assertTrue(result.validationResult().valid(), result.validationResult().summary());
    assertEquals(patch.propertyPatches().getFirst().property(), result.graph().nodes().stream()
        .filter(node -> node.nodeId().equals("call-1")).findFirst().orElseThrow().properties().getFirst());
    GraphPatch identity = new GraphPatch("identity", "cip-service-call-generator", List.of(), List.of(),
        List.of(new PropertyPatch(GraphPatchOperation.ADD, "call-1", new PlanProperty("integrationSystemId", "invented"))),
        List.of(), List.of(), "Attempt to replace server-owned identity");
    assertFalse(applier.apply(context(graphWithServiceCall(), policy), identity).validationResult().valid(),
        "Catalog identity remains server-owned");
  }

  @Test
  void serviceCallPropagateContextPatchIsAccepted() {
    GraphPatchOwnershipPolicy ownership = ownership("cip-service-call-generator");
    GraphPatch patch =
        new GraphPatch(
            "http-service-call-runtime-options",
            "cip-service-call-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "call-1",
                    new PlanProperty("propagateContext", "true"))),
            List.of(),
            List.of(),
            "Propagate context for the service call");

    GraphPatchApplyResult result =
        applier.apply(context(graphWithServiceCall(), ownership), patch);

    assertTrue(result.validationResult().valid());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cip-script-generator",
        "cip-transformation-generator",
        "cip-service-call-generator"
      })
  void unauthorizedNodeAddIsRejectedWithOwnershipDiagnostic(String skillId) {
    GraphPatchOwnershipPolicy ownership = ownership(skillId);
    ChainPlanGraph before = graphWithServiceCall();
    GraphPatch patch =
        new GraphPatch(
            "unauthorized-node",
            skillId,
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("extra-1", "service-call", "Extra", null, null, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Add a node the specialized generator does not own");

    GraphPatchApplyResult result = applier.apply(context(before, ownership), patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
    assertEquals(
        GraphPatchOwnershipValidator.OWNERSHIP_VIOLATION_ISSUE_ID,
        result.validationResult().issues().get(0).issueId());
    assertTrue(
        result.validationResult().summary().contains("ownership violation: node ADD is not allowed"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cip-script-generator",
        "cip-transformation-generator",
        "cip-service-call-generator"
      })
  void unauthorizedEdgeAddIsRejectedWithOwnershipDiagnostic(String skillId) {
    GraphPatchOwnershipPolicy ownership = ownership(skillId);
    ChainPlanGraph before = graphWithServiceCall();
    GraphPatch patch =
        new GraphPatch(
            "unauthorized-edge",
            skillId,
            List.of(),
            List.of(
                new EdgePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanEdge("edge-1", "http-trigger-1", "call-1", null),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            "Add an edge the specialized generator does not own");

    GraphPatchApplyResult result = applier.apply(context(before, ownership), patch);

    assertFalse(result.validationResult().valid());
    assertSame(before, result.graph());
    assertEquals(
        GraphPatchOwnershipValidator.OWNERSHIP_VIOLATION_ISSUE_ID,
        result.validationResult().issues().get(0).issueId());
    assertTrue(
        result.validationResult().summary().contains("ownership violation: edge ADD is not allowed"));
  }

  private GraphPatchOwnershipPolicy ownership(String skillId) {
    AddonRuntimeMetadata metadata = metadata(skillId);
    assertNotNull(metadata.ownership(), skillId + " must declare ownership");
    return metadata.ownership();
  }

  private AddonRuntimeMetadata metadata(String skillId) {
    Path addon =
        CipKnowledgePackFixturePaths.addonRoot()
            .resolve("skills")
            .resolve(skillId + ".addon.md");
    AddonRuntimeMetadata metadata = parser.parseAddonFile(addon);
    assertNotNull(metadata, "Missing runtime metadata for " + skillId);
    return metadata;
  }

  private static GraphPatchExecutionContext context(
      ChainPlanGraph inputGraph, GraphPatchOwnershipPolicy ownership) {
    return new GraphPatchExecutionContext(
        "run-1",
        "cip-service-call-generator",
        "req",
        "input",
        "compiler",
        "24.4",
        new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary"),
        List.of(),
        inputGraph,
        ownership,
        "");
  }

  private static ChainPlanGraph graphWithServiceCall() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "demo"),
        List.of(
            new ChainPlanNode("http-trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of());
  }
}
