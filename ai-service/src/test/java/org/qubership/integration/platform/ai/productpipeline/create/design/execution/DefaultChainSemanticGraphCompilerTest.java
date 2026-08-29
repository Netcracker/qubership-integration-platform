package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ErrorHandler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticContainment;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class DefaultChainSemanticGraphCompilerTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final ChainSemanticGraphCompiler compiler =
      new DefaultChainSemanticGraphCompiler(
          new DefaultChainSemanticRevisionValidator(),
          new CatalogBindingMatcher(mock(CatalogSystemReadTool.class)));

  @Test
  void compilesConditionReconvergenceAsIndependentInvocations() {
    ChainPlanGraph graph = compiler.compile(conditionRevision(), CONTRACT, List.of());

    assertEquals(
        Set.of("edge-entry", "edge-true", "edge-false", "edge-true-join", "edge-false-join"),
        graph.edges().stream().map(ChainPlanEdge::edgeId).collect(Collectors.toSet()));
    assertEquals(
        1,
        graph.nodes().stream().filter(node -> "script-common".equals(node.nodeId())).count());
    assertEquals("condition-1", node(graph, "script-true").parentNodeId());
    assertEquals("condition-1", node(graph, "script-false").parentNodeId());
    assertNull(node(graph, "script-common").parentNodeId());
    assertEquals("condition-1", edge(graph, "edge-true").scopeNodeId());
    assertEquals("condition-1", edge(graph, "edge-true-join").scopeNodeId());
    assertEquals("revision-1", graph.chain().semanticRevisionId());
    assertEquals(CONTRACT.contractVersion(), graph.chain().compilerContractVersion());
  }

  @Test
  void compilesEveryEntryPointWithoutPickingTheFirst() {
    ChainPlanGraph graph = compiler.compile(twoEntryRevision(), CONTRACT, List.of());

    assertEquals(
        Set.of("trigger-http", "trigger-kafka", "op-shared"),
        graph.nodes().stream().map(ChainPlanNode::nodeId).collect(Collectors.toSet()));
    assertEquals(
        Set.of("edge-http-in", "edge-kafka-in"),
        graph.edges().stream().map(ChainPlanEdge::edgeId).collect(Collectors.toSet()));
    assertEquals("http-trigger", node(graph, "trigger-http").type());
    assertEquals("kafka-trigger-2", node(graph, "trigger-kafka").type());
  }

  @Test
  void compilesOneBranchAsyncSplitWithRegionScope() {
    ChainPlanGraph graph =
        compiler.compile(asyncSplitOneBranchRevision(), CONTRACT, List.of(binding("call-notify")));

    assertEquals("split-async-2", node(graph, "split-async-1").type());
    assertEquals("split-async-1", edge(graph, "edge-notify").scopeNodeId());
    assertEquals(1, graph.nodes().stream().filter(n -> "call-notify".equals(n.nodeId())).count());
  }

  @Test
  void compilesLoopScopeWithoutBackEdge() {
    ChainPlanGraph graph = compiler.compile(loopRevision(LoopMode.COPY), CONTRACT, List.of());

    assertEquals("loop-2", node(graph, "loop-1").type());
    assertEquals("items", property(node(graph, "loop-1"), "expression"));
    assertEquals("true", property(node(graph, "loop-1"), "copy"));
    assertNull(property(node(graph, "loop-1"), "doWhile"));
    assertEquals("loop-1", edge(graph, "edge-body").scopeNodeId());
    assertEquals("loop-1", edge(graph, "edge-exit").scopeNodeId());
    assertTrue(
        graph.edges().stream()
            .noneMatch(
                edge ->
                    "loop-1".equals(edge.toNodeId())
                        && !"trigger-http".equals(edge.fromNodeId())));
  }

  @Test
  void writesExclusiveDoWhileLoopMode() {
    ChainPlanGraph graph = compiler.compile(loopRevision(LoopMode.DO_WHILE), CONTRACT, List.of());

    assertEquals("true", property(node(graph, "loop-1"), "doWhile"));
    assertNull(property(node(graph, "loop-1"), "copy"));
  }

  @Test
  void writesRetryPropertiesOnTheOwner() {
    ChainPlanGraph graph =
        compiler.compile(retryRevision(), CONTRACT, List.of(binding("call-1")));

    ChainPlanNode owner = node(graph, "call-1");
    assertEquals("3", property(owner, "retryCount"));
    assertEquals("5000", property(owner, "retryDelay"));
    assertEquals("call-1", edge(graph, "edge-entry").scopeNodeId());
  }

  @Test
  void compilesErrorHandlersWithCatchContainment() {
    ChainPlanGraph graph = compiler.compile(errorScopeRevision(), CONTRACT, List.of());

    assertEquals("try-catch-finally-2", node(graph, "try-catch-1").type());
    assertEquals("try-catch-1", node(graph, "try-body").parentNodeId());
    assertEquals("try-catch-1", node(graph, "catch-body").parentNodeId());
    assertEquals("try-catch-1", node(graph, "finally-script").parentNodeId());
    assertEquals("java.lang.Exception", property(node(graph, "catch-body"), "exception"));
    assertEquals("try-catch-1", edge(graph, "edge-catch").scopeNodeId());
  }

  @Test
  void writesReservedIdentityOnServiceCallNodes() {
    ChainPlanGraph graph =
        compiler.compile(linearMappedRevision(), CONTRACT, List.of(binding("call-1")));

    ChainPlanNode call = node(graph, "call-1");
    assertEquals("call-1", call.serviceCallId().orElseThrow());
    assertEquals("call-1", call.semanticNodeId().orElseThrow());
    assertEquals("revision-1", call.semanticRevisionId().orElseThrow());
    assertEquals("INTEGRATION", property(call, "systemType"));
    assertEquals("sys-1", property(call, "integrationSystemId"));
    assertEquals("sg-1", property(call, "integrationSpecificationGroupId"));
    assertEquals("spec-1", property(call, "integrationSpecificationId"));
    assertEquals("http", property(call, "integrationOperationProtocolType"));
    assertEquals("op-call-1", property(call, "integrationOperationId"));
    assertEquals("GET", property(call, "integrationOperationMethod"));
    assertEquals("/orders/{id}", property(call, "integrationOperationPath"));
  }

  @Test
  void pinsMappingIdentityOnTheTransformSite() {
    ChainPlanGraph graph =
        compiler.compile(linearMappedRevision(), CONTRACT, List.of(binding("call-1")));

    assertEquals("map-body", MappingExecutionSite.mappingIntentId(node(graph, "op-shared")));
    assertEquals("map-body", MappingExecutionSite.mappingId(node(graph, "op-shared")));
    assertEquals("edge-call", MappingExecutionSite.semanticEdgeId(node(graph, "op-shared")));
    assertNull(MappingExecutionSite.mappingIntentId(node(graph, "call-1")));
  }

  @Test
  void rejectsMissingCatalogBinding() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> compiler.compile(linearMappedRevision(), CONTRACT, List.of()));
    assertEquals("missing catalog binding for serviceCallId=call-1", error.getMessage());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing node " + nodeId));
  }

  private static ChainPlanEdge edge(ChainPlanGraph graph, String edgeId) {
    return graph.edges().stream()
        .filter(edge -> edgeId.equals(edge.edgeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing edge " + edgeId));
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }

  private static ResolvedServiceCallBinding binding(String serviceCallId) {
    return new ResolvedServiceCallBinding(
        serviceCallId,
        serviceCallId,
        "INTEGRATION",
        "sys-1",
        "sg-1",
        "spec-1",
        "op-" + serviceCallId,
        "http",
        "GET",
        "/orders/{id}",
        "getOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-" + serviceCallId,
        "");
  }

  private static ChainSemanticRevision conditionRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode condition =
        new SemanticNode.Operation("condition-1", "condition", new SemanticProvenance(List.of()));
    SemanticNode trueBranch =
        new SemanticNode.Operation("script-true", "script", new SemanticProvenance(List.of()));
    SemanticNode falseBranch =
        new SemanticNode.Operation("script-false", "script", new SemanticProvenance(List.of()));
    SemanticNode join =
        new SemanticNode.Operation("script-common", "script", new SemanticProvenance(List.of()));
    return revision(
        List.of(entry("http-in", "trigger-http", "condition-1")),
        List.of(trigger, condition, trueBranch, falseBranch, join),
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

  private static ChainSemanticRevision twoEntryRevision() {
    return revision(
        List.of(
            SemanticFixtures.entry("http-in", "trigger-http"),
            SemanticFixtures.entry("kafka-in", "trigger-kafka")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Trigger(
                "trigger-kafka", "kafka-trigger-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of()))),
        List.of(),
        List.of(
            sequence("edge-http-in", "trigger-http", "op-shared", null),
            sequence("edge-kafka-in", "trigger-kafka", "op-shared", null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision asyncSplitOneBranchRevision() {
    SemanticRegion.Split region = SemanticFixtures.asyncSplitOneBranch();
    return revision(
        List.of(entry("http-in", "trigger-http", "split-async-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "split-async-1", "split-async-2", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-notify", "call-notify", "notify", new SemanticProvenance(List.of()))),
        List.of(region),
        List.of(
            sequence("edge-entry", "trigger-http", "split-async-1", null),
            new SemanticExecutionEdge(
                "edge-notify",
                "split-async-1",
                "call-notify",
                region.regionId(),
                new SemanticRoute.SplitBranch("notify"),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision loopRevision(LoopMode mode) {
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
                new LoopPolicy(mode, "items", 1500))),
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

  private static ChainSemanticRevision errorScopeRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "try-catch-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "try-catch-1", "try-catch-finally-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("try-body", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("catch-body", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "finally-script", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.ErrorScope(
                "error-region",
                "try-catch-1",
                "try-body",
                List.of(
                    new ErrorHandler(
                        "catch-all", "java.lang.Exception", "catch-body", List.of("catch-body"))),
                "finally-script",
                List.of("finally-script"))),
        List.of(
            sequence("edge-entry", "trigger-http", "try-catch-1", null),
            new SemanticExecutionEdge(
                "edge-try",
                "try-catch-1",
                "try-body",
                "error-region",
                new SemanticRoute.TryPath(),
                null),
            new SemanticExecutionEdge(
                "edge-catch",
                "try-catch-1",
                "catch-body",
                "error-region",
                new SemanticRoute.CatchPath("catch-all"),
                null),
            new SemanticExecutionEdge(
                "edge-finally",
                "try-catch-1",
                "finally-script",
                "error-region",
                new SemanticRoute.FinallyPath(),
                null)),
        List.of(
            new SemanticContainment("try-catch-1", "try-body", "try-2"),
            new SemanticContainment("try-catch-1", "catch-body", "catch-2"),
            new SemanticContainment("try-catch-1", "finally-script", "finally-2")),
        List.of());
  }

  private static ChainSemanticRevision linearMappedRevision() {
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
