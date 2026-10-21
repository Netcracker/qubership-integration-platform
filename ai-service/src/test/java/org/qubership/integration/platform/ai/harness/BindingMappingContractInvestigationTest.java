package org.qubership.integration.platform.ai.harness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Characterizes binding and mapping boundary behavior without invoking an LLM. */
class BindingMappingContractInvestigationTest {

  private static final Path INPUTS = Path.of("e2e", "executor-eval", "inputs");
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private final DefaultChainSemanticGraphCompiler compiler =
      new DefaultChainSemanticGraphCompiler(
          new DefaultChainSemanticRevisionValidator(),
          DeterministicElementSchemaService.createForUnitTests(JSON));
  private final ClasspathCompilerContractRepository contracts =
      new ClasspathCompilerContractRepository();

  @Test
  @DisplayName("B01: distinct occurrences may use the same catalog operation")
  void repeatedOperationWithDistinctOwnersIsAccepted() throws Exception {
    ObjectNode input = input("two-service-calls");
    ArrayNode bindings = input.withArray("bindings");
    ((ObjectNode) bindings.get(1))
        .put("operationId", bindings.get(0).get("operationId").asText());

    assertDoesNotThrow(() -> compile(input));
  }

  @Test
  @DisplayName("B02: a missing binding is rejected before generator execution")
  void missingBindingIsRejected() throws Exception {
    ObjectNode input = input("two-service-calls");
    input.withArray("bindings").remove(1);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertEquals("missing catalog binding for serviceCallId=inventory-reserve", error.getMessage());
  }

  @Test
  @DisplayName("B03: a duplicate serviceCallId binding is rejected before generator execution")
  void duplicateBindingIsRejected() throws Exception {
    ObjectNode input = input("two-service-calls");
    ((ObjectNode) input.withArray("bindings").get(1)).put("serviceCallId", "orders-create");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertEquals("duplicate catalog binding for serviceCallId=orders-create", error.getMessage());
  }

  @Test
  @DisplayName("B04: swapped service-call binding targets are rejected")
  void swappedBindingTargetsAreRejected() throws Exception {
    ObjectNode input = input("two-service-calls");
    ArrayNode bindings = input.withArray("bindings");
    ((ObjectNode) bindings.get(0)).put("targetNodeId", "inventory-call");
    ((ObjectNode) bindings.get(1)).put("targetNodeId", "orders-call");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("semantic owner is orders-call"), error.getMessage());
  }

  @Test
  @DisplayName("B05: an extra binding is rejected")
  void extraBindingIsRejected() throws Exception {
    ObjectNode input = input("two-service-calls");
    ObjectNode extra = input.withArray("bindings").get(0).deepCopy();
    extra.put("serviceCallId", "extra-occurrence");
    extra.put("targetNodeId", "init-script");
    input.withArray("bindings").add(extra);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertEquals("extra catalog binding for serviceCallId=extra-occurrence", error.getMessage());
  }

  @Test
  @DisplayName("B06: a required binding cannot target a script")
  void requiredBindingTargetingNonServiceNodeIsRejected() throws Exception {
    ObjectNode input = input("mapping");
    ((ObjectNode) input.withArray("bindings").get(0))
        .put("targetNodeId", "request-map-script");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("semantic owner is orders-call"), error.getMessage());
  }

  @Test
  @DisplayName("B07: every service-call binding rejects every non-owner node")
  void everyNonOwnerBindingTargetIsRejected() throws Exception {
    ObjectNode base = input("two-service-calls");
    List<String> targets =
        List.of(
            "trigger-http",
            "init-script",
            "orders-call",
            "inventory-map-script",
            "inventory-call",
            "response-script",
            "missing-node");

    for (int bindingIndex = 0; bindingIndex < base.withArray("bindings").size(); bindingIndex++) {
      String owner = base.withArray("bindings").get(bindingIndex).path("targetNodeId").asText();
      for (String target : targets) {
        if (owner.equals(target)) {
          continue;
        }
        ObjectNode input = base.deepCopy();
        ((ObjectNode) input.withArray("bindings").get(bindingIndex)).put("targetNodeId", target);

        assertThrows(
            IllegalArgumentException.class,
            () -> compile(input),
            "binding " + bindingIndex + " unexpectedly accepted target " + target);
      }
    }
  }

  @Test
  @DisplayName("M01: request and response mappings compile on separate transform shells")
  void requestAndResponseMappingsAreAccepted() throws Exception {
    assertDoesNotThrow(() -> compile(input("mapping")));
  }

  @Test
  @DisplayName("M02: an orphan mapping intent is rejected")
  void orphanMappingIsRejected() throws Exception {
    ObjectNode input = input("mapping");
    edge(input, "edge-request-map").remove("mappingId");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("orphan mapping intent: request-map"), error.getMessage());
  }

  @Test
  @DisplayName("M03: one mapping intent on two edges is rejected")
  void duplicateMappingSiteIsRejected() throws Exception {
    ObjectNode input = input("mapping");
    edge(input, "edge-response-map").put("mappingId", "request-map");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("does not resolve to a single-incoming site"), error.getMessage());
  }

  @Test
  @DisplayName("M04: an edge with an unknown mapping id is rejected")
  void unknownMappingIdIsRejected() throws Exception {
    ObjectNode input = input("mapping");
    edge(input, "edge-request-map").put("mappingId", "unknown-map");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("Unknown mapping id 'unknown-map'"), error.getMessage());
  }

  @Test
  @DisplayName("M05: two mappings cannot share one transform shell")
  void sharedTransformShellIsRejected() throws Exception {
    ObjectNode input = input("mapping");
    edge(input, "edge-entry").put("mappingId", "entry-map");
    ObjectNode second = input.at("/requirementBrief/mappingIntents/0").deepCopy();
    second.put("mappingIntentId", "entry-map");
    second.put("sourceRef", "edge-entry");
    second.put("targetRef", "edge-entry");
    ((ArrayNode) input.at("/requirementBrief/mappingIntents")).add(second);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("is bound to more than one mapping intent"), error.getMessage());
  }

  @Test
  @DisplayName("M06: missing mapping references are rejected when a brief is present")
  void missingMappingReferencesAreRejected() throws Exception {
    ObjectNode input = input("mapping");
    ObjectNode intent = (ObjectNode) input.at("/requirementBrief/mappingIntents/0");
    intent.put("sourceRef", "missing-source");
    intent.put("targetRef", "missing-target");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("sourceRef 'missing-source' is missing"));
  }

  @Test
  @DisplayName("M07: legacy direct compilation resolves references but does not infer flow parity")
  void legacyMappingReferencesResolveWithoutFlowParity() throws Exception {
    ObjectNode input = input("mapping");
    ObjectNode intent = (ObjectNode) input.at("/requirementBrief/mappingIntents/0");
    intent.put("sourceRef", "edge-response-map");
    intent.put("targetRef", "edge-response-map");

    assertDoesNotThrow(() -> compile(input));
  }

  @Test
  @DisplayName("M08: mapping without a transform shell is rejected before generator execution")
  void mappingWithoutTransformShellIsRejected() throws Exception {
    ObjectNode input = input("mapping");
    ArrayNode nodes = (ArrayNode) input.at("/semanticRevision/nodes");
    removeById(nodes, "nodeId", "request-map-script");
    ((ObjectNode) input.at("/semanticRevision/entryPoints/0"))
        .put("initialTargetNodeId", "orders-call");
    ObjectNode entry = edge(input, "edge-entry");
    entry.put("targetNodeId", "orders-call");
    entry.put("mappingId", "request-map");
    ObjectNode requestIntent = (ObjectNode) input.at("/requirementBrief/mappingIntents/0");
    requestIntent.put("sourceRef", "edge-entry");
    requestIntent.put("targetRef", "edge-entry");
    removeById(
        (ArrayNode) input.at("/semanticRevision/executionEdges"),
        "edgeId",
        "edge-request-map");

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> compile(input));

    assertTrue(error.getMessage().contains("has no mapper-2 or script execution site"));
  }

  private ChainPlanGraph compile(ObjectNode input) throws Exception {
    ExecutorHarnessRequest request = JSON.treeToValue(input, ExecutorHarnessRequest.class);
    return compiler.compile(
        request.semanticRevision(),
        contracts.require(request.semanticRevision().compilerContractVersion()),
        request.bindings(),
        request.requirementBrief());
  }

  private static ObjectNode input(String caseId) throws Exception {
    return (ObjectNode) JSON.readTree(Files.readString(INPUTS.resolve(caseId + ".json")));
  }

  private static ObjectNode edge(ObjectNode input, String edgeId) {
    for (JsonNode edge : input.at("/semanticRevision/executionEdges")) {
      if (edgeId.equals(edge.path("edgeId").asText())) {
        return (ObjectNode) edge;
      }
    }
    throw new IllegalArgumentException("edge not found: " + edgeId);
  }

  private static void removeById(ArrayNode nodes, String field, String id) {
    for (int index = 0; index < nodes.size(); index++) {
      if (id.equals(nodes.get(index).path(field).asText())) {
        nodes.remove(index);
        return;
      }
    }
    throw new IllegalArgumentException(field + " not found: " + id);
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .findFirst()
        .orElseThrow();
  }

  private static String property(ChainPlanNode node, String key) {
    return node.properties().stream()
        .filter(property -> key.equals(property.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElse(null);
  }
}
