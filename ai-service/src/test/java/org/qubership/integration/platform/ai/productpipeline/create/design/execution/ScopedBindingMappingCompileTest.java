package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ErrorHandler;
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
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ScopedBindingMappingCompileTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);
  private static final List<MappingIntent> TWO_MAPPINGS =
      List.of(mapping("map-a", "edge-map-a"), mapping("map-b", "edge-map-b"));

  private final ChainSemanticGraphCompiler compiler =
      new DefaultChainSemanticGraphCompiler(
          new DefaultChainSemanticRevisionValidator(),
          DeterministicElementSchemaService.createForUnitTests(new ObjectMapper()));

  @Test
  void compilesMappingsAndDistinctBindingsInsideScopedTopologies() {
    for (Scenario scenario : scenarios()) {
      ChainPlanGraph graph =
          compiler.compile(scenario.revision(), CONTRACT, bindings(false), brief(TWO_MAPPINGS));

      assertEquals(
          "map-a",
          MappingExecutionSite.mappingIntentId(node(graph, "map-a-shell")),
          scenario.id());
      assertEquals(
          "map-b",
          MappingExecutionSite.mappingIntentId(node(graph, "map-b-shell")),
          scenario.id());
      assertEquals("op-occurrence-a", property(node(graph, "call-a"), "integrationOperationId"));
      assertEquals("op-occurrence-b", property(node(graph, "call-b"), "integrationOperationId"));
    }
  }

  @Test
  void rejectsSwappedBindingOwnersInsideScopedTopologies() {
    for (Scenario scenario : scenarios()) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  compiler.compile(
                      scenario.revision(), CONTRACT, bindings(true), brief(TWO_MAPPINGS)),
              scenario.id());

      assertTrue(error.getMessage().contains("semantic owner is call-a"), error.getMessage());
    }
  }

  @Test
  void onlyExactBindingOwnerPermutationCompilesForThreeMappedOccurrences() {
    ChainSemanticRevision revision = threeOccurrenceRevision();
    List<MappingIntent> mappings =
        List.of(
            mapping("map-a", "edge-map-a"),
            mapping("map-b", "edge-map-b"),
            mapping("map-c", "edge-map-c"));
    List<List<String>> targetPermutations =
        List.of(
            List.of("call-a", "call-b", "call-c"),
            List.of("call-a", "call-c", "call-b"),
            List.of("call-b", "call-a", "call-c"),
            List.of("call-b", "call-c", "call-a"),
            List.of("call-c", "call-a", "call-b"),
            List.of("call-c", "call-b", "call-a"));

    for (List<String> targets : targetPermutations) {
      String scenario = String.join(",", targets);
      if (targets.equals(List.of("call-a", "call-b", "call-c"))) {
        assertDoesNotThrow(
            () -> compiler.compile(revision, CONTRACT, bindings(targets), brief(mappings)), scenario);
      } else {
        assertThrows(
            IllegalArgumentException.class,
            () -> compiler.compile(revision, CONTRACT, bindings(targets), brief(mappings)),
            scenario);
      }
    }
  }

  private static List<Scenario> scenarios() {
    return List.of(
        new Scenario("condition", conditionRevision()),
        new Scenario("retry", retryRevision()),
        new Scenario("try-catch-finally-2", errorScopeRevision()));
  }

  private static ChainSemanticRevision conditionRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "condition-1")),
        List.of(
            trigger(),
            operation("condition-1", "condition"),
            operation("map-a-shell", "script"),
            call("call-a", "occurrence-a"),
            operation("map-b-shell", "script"),
            call("call-b", "occurrence-b"),
            operation("after-condition", "script")),
        List.of(
            new SemanticRegion.Condition(
                "condition-region",
                "condition-1",
                List.of(
                    new SemanticBranch.Condition(
                        "accepted",
                        ConditionBranchRole.IF,
                        "approved == true",
                        1,
                        "map-a-shell",
                        List.of("call-a")),
                    new SemanticBranch.Condition(
                        "rejected",
                        ConditionBranchRole.ELSE,
                        null,
                        0,
                        "map-b-shell",
                        List.of("call-b"))),
                "after-condition")),
        List.of(
            sequence("edge-entry", "trigger-http", "condition-1", null, null),
            edge(
                "edge-branch-a",
                "condition-1",
                "map-a-shell",
                "condition-region",
                new SemanticRoute.ConditionBranch("accepted"),
                null),
            sequence("edge-map-a", "map-a-shell", "call-a", "condition-region", "map-a"),
            edge(
                "edge-join-a",
                "call-a",
                "after-condition",
                "condition-region",
                new SemanticRoute.Reconverge(List.of("accepted")),
                null),
            edge(
                "edge-branch-b",
                "condition-1",
                "map-b-shell",
                "condition-region",
                new SemanticRoute.ConditionBranch("rejected"),
                null),
            sequence("edge-map-b", "map-b-shell", "call-b", "condition-region", "map-b"),
            edge(
                "edge-join-b",
                "call-b",
                "after-condition",
                "condition-region",
                new SemanticRoute.Reconverge(List.of("rejected")),
                null)),
        List.of(
            new SemanticContainment("condition-1", "map-a-shell", "if"),
            new SemanticContainment("condition-1", "call-a", "if"),
            new SemanticContainment("condition-1", "map-b-shell", "else"),
            new SemanticContainment("condition-1", "call-b", "else")));
  }

  private static ChainSemanticRevision retryRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "map-a-shell")),
        List.of(
            trigger(),
            operation("map-a-shell", "script"),
            call("call-a", "occurrence-a"),
            operation("map-b-shell", "script"),
            call("call-b", "occurrence-b")),
        List.of(
            new SemanticRegion.Retry(
                "retry-region",
                "call-a",
                "map-a-shell",
                List.of("map-b-shell"),
                "call-b",
                new RetryPolicy(3, 5000))),
        List.of(
            edge(
                "edge-attempt",
                "trigger-http",
                "map-a-shell",
                "retry-region",
                new SemanticRoute.RetryAttempt(),
                null),
            sequence("edge-map-a", "map-a-shell", "call-a", "retry-region", "map-a"),
            sequence("edge-map-b", "call-a", "map-b-shell", "retry-region", "map-b"),
            edge(
                "edge-exhausted",
                "map-b-shell",
                "call-b",
                "retry-region",
                new SemanticRoute.RetryExhausted(),
                null)),
        List.of());
  }

  private static ChainSemanticRevision errorScopeRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "try-catch")),
        List.of(
            trigger(),
            operation("try-catch", "try-catch-finally-2"),
            operation("map-a-shell", "script"),
            call("call-a", "occurrence-a"),
            operation("map-b-shell", "script"),
            call("call-b", "occurrence-b"),
            operation("finally-script", "script")),
        List.of(
            new SemanticRegion.ErrorScope(
                "error-region",
                "try-catch",
                "map-a-shell",
                List.of(
                    new ErrorHandler(
                        "catch-all", "java.lang.Exception", "map-b-shell", List.of("call-b"))),
                "finally-script",
                List.of("call-a", "finally-script"))),
        List.of(
            sequence("edge-entry", "trigger-http", "try-catch", null, null),
            edge(
                "edge-try",
                "try-catch",
                "map-a-shell",
                "error-region",
                new SemanticRoute.TryPath(),
                null),
            sequence("edge-map-a", "map-a-shell", "call-a", "error-region", "map-a"),
            edge(
                "edge-catch",
                "try-catch",
                "map-b-shell",
                "error-region",
                new SemanticRoute.CatchPath("catch-all"),
                null),
            sequence("edge-map-b", "map-b-shell", "call-b", "error-region", "map-b"),
            edge(
                "edge-finally",
                "try-catch",
                "finally-script",
                "error-region",
                new SemanticRoute.FinallyPath(),
                null)),
        List.of());
  }

  private static ChainSemanticRevision threeOccurrenceRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "map-a-shell")),
        List.of(
            trigger(),
            operation("map-a-shell", "script"),
            call("call-a", "occurrence-a"),
            operation("map-b-shell", "script"),
            call("call-b", "occurrence-b"),
            operation("map-c-shell", "script"),
            call("call-c", "occurrence-c")),
        List.of(),
        List.of(
            sequence("edge-entry", "trigger-http", "map-a-shell", null, null),
            sequence("edge-map-a", "map-a-shell", "call-a", null, "map-a"),
            sequence("edge-map-b", "call-a", "map-b-shell", null, "map-b"),
            sequence("edge-call-b", "map-b-shell", "call-b", null, null),
            sequence("edge-map-c", "call-b", "map-c-shell", null, "map-c"),
            sequence("edge-call-c", "map-c-shell", "call-c", null, null)),
        List.of());
  }

  private static ChainSemanticRevision revision(
      List<SemanticEntryPoint> entryPoints,
      List<SemanticNode> nodes,
      List<SemanticRegion> regions,
      List<SemanticExecutionEdge> edges,
      List<SemanticContainment> containment) {
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-scoped-binding-mapping",
        "scoped-binding-mapping",
        CONTRACT.contractVersion(),
        entryPoints,
        nodes,
        regions,
        edges,
        containment,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static RequirementBrief brief(List<MappingIntent> mappings) {
    return new RequirementBrief(
            "Scoped mappings", List.of(), List.of(), List.of(), List.of(), "summary")
        .withMappingIntents(mappings);
  }

  private static MappingIntent mapping(String mappingId, String edgeId) {
    return new MappingIntent(
        mappingId,
        edgeId,
        MappingPort.OUTPUT,
        edgeId,
        MappingPort.REQUEST,
        List.of(new MappingIntentRule("source", "target", null)));
  }

  private static List<ResolvedServiceCallBinding> bindings(boolean swapped) {
    return bindings(
        swapped ? List.of("call-b", "call-a") : List.of("call-a", "call-b"));
  }

  private static List<ResolvedServiceCallBinding> bindings(List<String> targets) {
    return java.util.stream.IntStream.range(0, targets.size())
        .mapToObj(
            index ->
                binding(targets.get(index), "occurrence-" + (char) ('a' + index)))
        .toList();
  }

  private static ResolvedServiceCallBinding binding(String targetNodeId, String serviceCallId) {
    return new ResolvedServiceCallBinding(
        targetNodeId,
        serviceCallId,
        "INTEGRATION",
        "system",
        "group",
        "specification",
        "op-" + serviceCallId,
        "http",
        "POST",
        "/orders",
        serviceCallId,
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-" + serviceCallId,
        "");
  }

  private static SemanticNode.Trigger trigger() {
    return new SemanticNode.Trigger(
        "trigger-http", "http-trigger", new SemanticProvenance(List.of()));
  }

  private static SemanticNode.Operation operation(String nodeId, String elementType) {
    return new SemanticNode.Operation(nodeId, elementType, new SemanticProvenance(List.of()));
  }

  private static SemanticNode.ServiceCall call(String nodeId, String serviceCallId) {
    return new SemanticNode.ServiceCall(
        nodeId, serviceCallId, serviceCallId, new SemanticProvenance(List.of()));
  }

  private static SemanticEntryPoint entry(String id, String triggerNodeId, String targetNodeId) {
    return new SemanticEntryPoint(
        id, triggerNodeId, targetNodeId, 0, new SemanticProvenance(List.of()), null);
  }

  private static SemanticExecutionEdge sequence(
      String edgeId, String source, String target, String regionId, String mappingId) {
    return edge(
        edgeId, source, target, regionId, new SemanticRoute.Sequence(), mappingId);
  }

  private static SemanticExecutionEdge edge(
      String edgeId,
      String source,
      String target,
      String regionId,
      SemanticRoute route,
      String mappingId) {
    return new SemanticExecutionEdge(edgeId, source, target, regionId, route, mappingId);
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing node " + nodeId));
  }

  private static String property(ChainPlanNode node, String key) {
    return node.properties().stream()
        .filter(property -> key.equals(property.key()))
        .map(property -> property.value())
        .findFirst()
        .orElse(null);
  }

  private record Scenario(String id, ChainSemanticRevision revision) {}
}
