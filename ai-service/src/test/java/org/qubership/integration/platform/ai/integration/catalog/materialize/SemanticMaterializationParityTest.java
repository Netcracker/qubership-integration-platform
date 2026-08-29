package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorDto;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticContainment;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

/**
 * Semantic graphs must not materialize unless every node and edge has an explicit owner.
 */
class SemanticMaterializationParityTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final ChainSemanticGraphCompiler compiler =
      new DefaultChainSemanticGraphCompiler(
          new DefaultChainSemanticRevisionValidator(),
          new CatalogBindingMatcher(mock(CatalogSystemReadTool.class)));

  private CatalogGraphMaterializerTestHarness harness;

  @BeforeEach
  void setUp() {
    harness = new CatalogGraphMaterializerTestHarness(permissiveLibrary());
  }

  @Test
  void rejectsGraphWhenSemanticNodeHasNoOwner() {
    ChainPlanGraph graph = graphWithUnsupportedOwnedNode("node-x");
    MaterializationMap map =
        new MaterializationMap(
            harness.chainId(), Map.of("trigger", "el-trigger"), Map.of(), Map.of());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> harness.materializer().apply(harness.chainId(), graph, graph, map));

    assertTrue(
        error.getMessage().contains("No materialization owner for semantic node: node-x"),
        error.getMessage());
  }

  @Test
  void rejectsGraphWhenSemanticEdgeHasNoOwner() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("parity-chain", "Parity"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("edge-x", "trigger", "missing-node", null)));
    MaterializationMap map =
        new MaterializationMap(
            harness.chainId(),
            Map.of("trigger", "el-trigger", "script-1", "el-script"),
            Map.of(),
            Map.of());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> harness.materializer().apply(harness.chainId(), graph, graph, map));

    assertTrue(
        error.getMessage().contains("No materialization owner for semantic edge: edge-x"),
        error.getMessage());
  }

  @Test
  void materializesConditionReconvergenceWithOwners() {
    ChainPlanGraph graph = compiler.compile(conditionRevision(), CONTRACT, List.of());
    CatalogGraphMaterializeResult result = harness.create(graph);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(graph, result.materializationMap());
  }

  @Test
  void materializesLoopScopeWithOwners() {
    ChainPlanGraph graph = compiler.compile(loopRevision(), CONTRACT, List.of());
    CatalogGraphMaterializeResult result = harness.create(graph);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(graph, result.materializationMap());
  }

  @Test
  void materializesRetryWithOwners() {
    ChainPlanGraph graph =
        compiler.compile(retryRevision(), CONTRACT, List.of(binding("call-1", "op-shared")));
    CatalogGraphMaterializeResult result = harness.create(graph);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(graph, result.materializationMap());
  }

  @Test
  void materializesTwoIdenticalOperationsWithDistinctServiceCallIds() {
    ChainPlanGraph graph =
        compiler.compile(
            twoIdenticalOperationsRevision(),
            CONTRACT,
            List.of(binding("call-a", "op-shared"), binding("call-b", "op-shared")));
    CatalogGraphMaterializeResult result = harness.create(graph);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(graph, result.materializationMap());

    ChainPlanNode first = node(graph, "call-a");
    ChainPlanNode second = node(graph, "call-b");
    assertEquals("call-a", first.serviceCallId().orElseThrow());
    assertEquals("call-b", second.serviceCallId().orElseThrow());
    assertEquals("op-shared", property(first, "integrationOperationId"));
    assertEquals("op-shared", property(second, "integrationOperationId"));
    assertNotEquals(
        result.materializationMap().nodeIdToElementId().get("call-a"),
        result.materializationMap().nodeIdToElementId().get("call-b"));
  }

  @Test
  void pinsMappingSiteAndOwnsTheMappedEdge() {
    ChainPlanGraph graph =
        compiler.compile(mappedRevision(), CONTRACT, List.of(binding("call-1", "op-call-1")));

    assertEquals("map-body", MappingExecutionSite.mappingIntentId(node(graph, "op-shared")));
    assertEquals("map-body", MappingExecutionSite.mappingId(node(graph, "op-shared")));
    assertEquals("edge-call", MappingExecutionSite.semanticEdgeId(node(graph, "op-shared")));

    CatalogGraphMaterializeResult result = harness.create(graph);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(graph, result.materializationMap());
    assertEquals(
        "op-shared",
        result.materializationMap().mappingIntentExecutionNodeIds().get("map-body"));
  }

  private static ChainPlanGraph graphWithUnsupportedOwnedNode(String nodeId) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("parity-chain", "Parity"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(nodeId, "script", nodeId, null, null, List.of())),
        List.of());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(candidate -> nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing node " + nodeId));
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    return node.properties().stream()
        .filter(property -> key.equals(property.key()))
        .map(org.qubership.integration.platform.ai.plan.model.PlanProperty::value)
        .findFirst()
        .orElse(null);
  }

  private static ResolvedServiceCallBinding binding(String serviceCallId, String operationId) {
    return new ResolvedServiceCallBinding(
        serviceCallId,
        serviceCallId,
        "EXTERNAL",
        "sys-1",
        "sg-1",
        "spec-1",
        operationId,
        "http",
        "GET",
        "/orders/{id}",
        "getOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-" + serviceCallId,
        "");
  }

  private static Map<String, CatalogElementDescriptorDto> permissiveLibrary() {
    Map<String, CatalogElementDescriptorDto> library = new LinkedHashMap<>();
    Set<String> leaves =
        Set.of("http-trigger", "kafka-trigger-2", "script", "service-call", "mapper-2");
    for (String type :
        List.of(
            "http-trigger",
            "kafka-trigger-2",
            "script",
            "service-call",
            "condition",
            "loop-2",
            "mapper-2",
            "try-catch-finally-2",
            "try-2",
            "catch-2",
            "finally-2",
            "split-async-2",
            "async-split-element-2")) {
      CatalogElementDescriptorDto dto = new CatalogElementDescriptorDto();
      dto.name = type;
      dto.container = !leaves.contains(type);
      dto.allowedChildren = Map.of();
      dto.parentRestriction = List.of();
      dto.ordered = true;
      dto.priorityProperty = "priority";
      dto.allowedInContainers = true;
      library.put(type, dto);
    }
    return Map.copyOf(library);
  }

  private static ChainSemanticRevision conditionRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "condition-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("condition-1", "condition", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("script-true", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("script-false", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "script-common", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.Condition(
                "region-condition",
                "condition-1",
                List.of(
                    new SemanticBranch.Condition(
                        "true-branch",
                        ConditionBranchRole.IF,
                        "status == 'ok'",
                        1,
                        "script-true",
                        List.of("script-true")),
                    new SemanticBranch.Condition(
                        "false-branch",
                        ConditionBranchRole.ELSE,
                        null,
                        0,
                        "script-false",
                        List.of("script-false"))),
                "script-common")),
        List.of(
            sequence("edge-entry", "trigger-http", "condition-1", null),
            new SemanticExecutionEdge(
                "edge-true",
                "condition-1",
                "script-true",
                "region-condition",
                new SemanticRoute.ConditionBranch("true-branch"),
                null),
            new SemanticExecutionEdge(
                "edge-false",
                "condition-1",
                "script-false",
                "region-condition",
                new SemanticRoute.ConditionBranch("false-branch"),
                null),
            new SemanticExecutionEdge(
                "edge-true-join",
                "script-true",
                "script-common",
                "region-condition",
                new SemanticRoute.Reconverge(List.of("true-branch")),
                null),
            new SemanticExecutionEdge(
                "edge-false-join",
                "script-false",
                "script-common",
                "region-condition",
                new SemanticRoute.Reconverge(List.of("false-branch")),
                null)),
        List.of(
            new SemanticContainment("condition-1", "script-true", "if"),
            new SemanticContainment("condition-1", "script-false", "else")),
        List.of());
  }

  private static ChainSemanticRevision loopRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "loop-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("loop-1", "loop-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("body-script", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("after-loop", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.Loop(
                "loop-region",
                "loop-1",
                "body-script",
                List.of("body-script"),
                "after-loop",
                new LoopPolicy(LoopMode.COPY, "items", 1500))),
        List.of(
            sequence("edge-entry", "trigger-http", "loop-1", null),
            new SemanticExecutionEdge(
                "edge-body",
                "loop-1",
                "body-script",
                "loop-region",
                new SemanticRoute.LoopBody(),
                null),
            new SemanticExecutionEdge(
                "edge-exit",
                "body-script",
                "after-loop",
                "loop-region",
                new SemanticRoute.LoopExit(),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision retryRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "call-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-1", "call-1", "getOrder", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("after-retry", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.Retry(
                "retry-region",
                "call-1",
                "call-1",
                List.of("call-1"),
                "after-retry",
                new RetryPolicy(3, 5000))),
        List.of(
            new SemanticExecutionEdge(
                "edge-entry",
                "trigger-http",
                "call-1",
                "retry-region",
                new SemanticRoute.RetryAttempt(),
                null),
            new SemanticExecutionEdge(
                "edge-exhausted",
                "call-1",
                "after-retry",
                "retry-region",
                new SemanticRoute.RetryExhausted(),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision twoIdenticalOperationsRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "call-a")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-a", "call-a", "getOrder", new SemanticProvenance(List.of("fact-a"))),
            new SemanticNode.ServiceCall(
                "call-b", "call-b", "getOrder", new SemanticProvenance(List.of("fact-b")))),
        List.of(),
        List.of(
            sequence("edge-entry", "trigger-http", "call-a", null),
            sequence("edge-next", "call-a", "call-b", null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision mappedRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "op-shared")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-1", "call-1", "getOrder", new SemanticProvenance(List.of()))),
        List.of(),
        List.of(
            sequence("edge-entry", "trigger-http", "op-shared", null),
            new SemanticExecutionEdge(
                "edge-call",
                "op-shared",
                "call-1",
                null,
                new SemanticRoute.Sequence(),
                "map-body")),
        List.of(),
        List.of(
            new MappingIntent(
                "map-body",
                "edge-call",
                MappingPort.OUTPUT,
                "edge-call",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("id", "orderId", null)))));
  }

  private static SemanticEntryPoint entry(String id, String triggerNodeId, String targetNodeId) {
    return new SemanticEntryPoint(
        id, triggerNodeId, targetNodeId, 0, new SemanticProvenance(List.of()), null);
  }

  private static SemanticExecutionEdge sequence(
      String edgeId, String from, String to, String regionId) {
    return new SemanticExecutionEdge(
        edgeId, from, to, regionId, new SemanticRoute.Sequence(), null);
  }

  private static ChainSemanticRevision revision(
      List<SemanticEntryPoint> entryPoints,
      List<SemanticNode> nodes,
      List<SemanticRegion> regions,
      List<SemanticExecutionEdge> edges,
      List<SemanticContainment> containment,
      List<MappingIntent> mappings) {
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-1",
        "chain-greetings",
        CONTRACT.contractVersion(),
        entryPoints,
        nodes,
        regions,
        edges,
        containment,
        mappings,
        List.of(),
        List.of(),
        List.of());
  }
}
