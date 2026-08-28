package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphReadBackVerifier;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanRemovalsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * Fail-closed matrix for rejected semantic compiler states. Each row hits the owning boundary and
 * expects {@link IllegalArgumentException} or {@link IllegalStateException}.
 */
class CanonicalSemanticRejectionIT {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);
  private static final ChainSemanticRevisionValidator SEMANTIC_VALIDATOR =
      new DefaultChainSemanticRevisionValidator();

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedStates")
  void rejectsInvalidState(String scenarioId, Executable action, String errorSubstring) {
    RuntimeException error = assertThrows(RuntimeException.class, action);
    assertTrue(
        error instanceof IllegalArgumentException || error instanceof IllegalStateException,
        scenarioId + " threw " + error.getClass().getName() + ": " + error.getMessage());
    assertTrue(error.getMessage().contains(errorSubstring), error.getMessage());
  }

  static Stream<Arguments> rejectedStates() {
    return Stream.of(
        arguments(
            "old semantic schema",
            (Executable) CanonicalSemanticRejectionIT::rejectOldSemanticSchema,
            "Unsupported semantic schema version:"),
        arguments(
            "zero entries",
            (Executable) CanonicalSemanticRejectionIT::rejectZeroEntries,
            "entryPoints must contain at least one entry"),
        arguments(
            "execution cycle",
            (Executable) CanonicalSemanticRejectionIT::rejectExecutionCycle,
            "execution edges must form a DAG"),
        arguments(
            "generic barrier",
            (Executable) CanonicalSemanticRejectionIT::rejectGenericBarrier,
            "Unsupported topology: generic-barrier"),
        arguments(
            "aggregate",
            (Executable) CanonicalSemanticRejectionIT::rejectAggregate,
            "Unsupported topology: generic-aggregate"),
        arguments(
            "zero-branch async split",
            (Executable) CanonicalSemanticRejectionIT::rejectZeroBranchAsyncSplit,
            "split-async-2 requires at least 1 branch"),
        arguments(
            "stale semantic digest",
            (Executable) CanonicalSemanticRejectionIT::rejectStaleSemanticDigest,
            "Approved semantic revision digest does not match"),
        arguments(
            "stale contract digest",
            (Executable) CanonicalSemanticRejectionIT::rejectStaleContractDigest,
            "Approved compiler contract digest does not match"),
        arguments(
            "missing runtime descriptor",
            (Executable) CanonicalSemanticRejectionIT::rejectMissingRuntimeDescriptor,
            "Required runtime descriptor is missing:"),
        arguments(
            "missing materialization owner",
            (Executable) CanonicalSemanticRejectionIT::rejectMissingMaterializationOwner,
            "No materialization owner for semantic node:"));
  }

  private static void rejectOldSemanticSchema() {
    ChainSemanticRevision valid = SemanticFixtures.linearOrders();
    new ChainSemanticRevision(
        "normalized-design-flow/v1",
        valid.revisionId(),
        valid.chainIdentity(),
        valid.compilerContractVersion(),
        valid.entryPoints(),
        valid.nodes(),
        valid.regions(),
        valid.executionEdges(),
        valid.containment(),
        valid.mappingIntents(),
        valid.constraints(),
        valid.assumptions(),
        valid.citations());
  }

  private static void rejectZeroEntries() {
    SEMANTIC_VALIDATOR.validate(copy(SemanticFixtures.linearOrders(), List.of()), CONTRACT);
  }

  private static void rejectExecutionCycle() {
    SEMANTIC_VALIDATOR.validate(executionCycleRevision(), CONTRACT);
  }

  private static void rejectGenericBarrier() {
    SEMANTIC_VALIDATOR.validate(hiddenJoinRevision(), CONTRACT);
  }

  private static void rejectAggregate() {
    SEMANTIC_VALIDATOR.validate(aggregateRevision(), CONTRACT);
  }

  private static void rejectZeroBranchAsyncSplit() {
    SEMANTIC_VALIDATOR.validate(asyncSplitZeroBranchesRevision(), CONTRACT);
  }

  private static void rejectStaleSemanticDigest() {
    CompilerRunPinResolver resolver = new CompilerRunPinResolver(mock(CompilerPipelineIndex.class));
    ChainSemanticRevision approved = SemanticFixtures.linearOrders();
    CompilerRunPin pin = resolver.resolve("run-rejection-1", approved, CONTRACT);
    resolver.verifyPersistedPin(pin, SemanticFixtures.linearOrdersWithMapping());
  }

  private static void rejectStaleContractDigest() {
    CompilerRunPinResolver resolver = new CompilerRunPinResolver(mock(CompilerPipelineIndex.class));
    ChainSemanticRevision revision = SemanticFixtures.linearOrders();
    CompilerRunPin pin = resolver.resolve("run-rejection-1", revision, CONTRACT);
    CompilerContract other =
        new CompilerContract(
            CONTRACT.contractVersion(),
            CONTRACT.semanticSchemaVersion(),
            CONTRACT.elements(),
            CONTRACT.topology(),
            CONTRACT.requiredArtifacts(),
            CONTRACT.requiredAddons(),
            CONTRACT.requiredKnowledgeFragments(),
            "aa".repeat(32));
    resolver.verifyPersistedPin(pin, revision, other);
  }

  private static void rejectMissingRuntimeDescriptor() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    when(loader.load(anyString()))
        .thenThrow(new CatalogElementDescriptorException("http-trigger", "not found."));
    ChainPlanGraphValidator validator =
        new ChainPlanGraphValidator(mock(DeterministicElementSchemaService.class), loader);
    validator.validate(compiledLinearGraph(), CONTRACT, SemanticFixtures.linearOrders());
  }

  private static void rejectMissingMaterializationOwner() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(loader);
    CatalogGraphMaterializer materializer =
        new CatalogGraphMaterializer(
            mock(ChainPlanPropertiesMaterializer.class),
            mock(ChainPlanSkeletonMaterializer.class),
            mock(ChainPlanConnectionsMaterializer.class),
            mock(ChainPlanRemovalsMaterializer.class),
            mock(CatalogRestClient.class),
            loader,
            mock(CatalogGraphReadBackVerifier.class));
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("parity-chain", "Parity"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("node-x", "script", "node-x", null, null, List.of())),
            List.of());
    MaterializationMap map =
        new MaterializationMap(
            "parity-chain", Map.of("trigger", "el-trigger"), Map.of(), Map.of());
    materializer.apply("parity-chain", graph, graph, map);
  }

  private static ChainPlanGraph compiledLinearGraph() {
    ChainSemanticRevision revision = SemanticFixtures.linearOrders();
    return new ChainPlanGraph(
        "1.0",
        new ChainSection(
            revision.chainIdentity(),
            null,
            null,
            null,
            revision.revisionId(),
            revision.compilerContractVersion()),
        List.of(
            new ChainPlanNode(
                "trigger-http", "http-trigger", "Orders API", null, null, List.of()),
            new ChainPlanNode("node-call", "service-call", "createOrder", null, null, List.of())),
        List.of(new ChainPlanEdge("edge-1", "trigger-http", "node-call", null)));
  }

  private static ChainSemanticRevision executionCycleRevision() {
    ChainSemanticRevision linear = SemanticFixtures.linearOrders();
    List<SemanticExecutionEdge> edges = new ArrayList<>(linear.executionEdges());
    edges.add(
        new SemanticExecutionEdge(
            "edge-cycle",
            "node-call",
            "trigger-http",
            null,
            new SemanticRoute.Sequence(),
            null));
    return copy(linear, linear.entryPoints(), linear.nodes(), linear.regions(), edges);
  }

  private static ChainSemanticRevision asyncSplitZeroBranchesRevision() {
    ChainSemanticRevision linear = SemanticFixtures.linearOrders();
    List<SemanticNode> nodes = new ArrayList<>(linear.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "split-async-1", "split-async-2", new SemanticProvenance(List.of())));
    List<SemanticExecutionEdge> edges = new ArrayList<>(linear.executionEdges());
    edges.add(
        new SemanticExecutionEdge(
            "edge-to-split",
            "node-call",
            "split-async-1",
            "region-async-split",
            new SemanticRoute.Sequence(),
            null));
    return copy(
        linear,
        linear.entryPoints(),
        nodes,
        List.of(
            new SemanticRegion.Split(
                "region-async-split", "split-async-1", SplitMode.ASYNC, List.of(), null)),
        edges);
  }

  private static ChainSemanticRevision aggregateRevision() {
    ChainSemanticRevision base = SemanticFixtures.conditionReconvergence();
    List<SemanticExecutionEdge> edges = new ArrayList<>();
    for (SemanticExecutionEdge edge : base.executionEdges()) {
      if ("edge-true-join".equals(edge.edgeId())) {
        edges.add(
            new SemanticExecutionEdge(
                edge.edgeId(),
                edge.sourceNodeId(),
                edge.targetNodeId(),
                edge.regionId(),
                edge.route(),
                "map-join"));
      } else {
        edges.add(edge);
      }
    }
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        base.entryPoints(),
        base.nodes(),
        base.regions(),
        edges,
        base.containment(),
        List.of(
            new MappingIntent(
                "map-join",
                "edge-true-join",
                MappingPort.OUTPUT,
                "edge-true-join",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("id", "orderId", null)))),
        base.constraints(),
        base.assumptions(),
        base.citations());
  }

  private static ChainSemanticRevision hiddenJoinRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode condition =
        new SemanticNode.Operation("condition-1", "condition", new SemanticProvenance(List.of()));
    SemanticNode callA =
        new SemanticNode.ServiceCall(
            "call-a", "call-a", "getOrder", new SemanticProvenance(List.of()));
    SemanticNode callB =
        new SemanticNode.ServiceCall(
            "call-b", "call-b", "getItem", new SemanticProvenance(List.of()));
    SemanticNode join =
        new SemanticNode.Operation("script-common", "script", new SemanticProvenance(List.of()));
    List<SemanticExecutionEdge> edges =
        List.of(
            new SemanticExecutionEdge(
                "edge-entry",
                "trigger-http",
                "condition-1",
                null,
                new SemanticRoute.Sequence(),
                null),
            new SemanticExecutionEdge(
                "edge-approved",
                "condition-1",
                "call-a",
                "region-condition",
                new SemanticRoute.ConditionBranch("approved"),
                null),
            new SemanticExecutionEdge(
                "edge-rejected",
                "condition-1",
                "call-b",
                "region-condition",
                new SemanticRoute.ConditionBranch("rejected"),
                null),
            new SemanticExecutionEdge(
                "edge-join-a",
                "call-a",
                "script-common",
                "region-condition",
                new SemanticRoute.Sequence(),
                null),
            new SemanticExecutionEdge(
                "edge-join-b",
                "call-b",
                "script-common",
                "region-condition",
                new SemanticRoute.Sequence(),
                null));
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-1",
        "chain-greetings",
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "http-in",
                "trigger-http",
                "condition-1",
                0,
                new SemanticProvenance(List.of()),
                null)),
        List.of(trigger, condition, callA, callB, join),
        List.of(
            new SemanticRegion.Condition(
                "region-condition",
                "condition-1",
                List.of(
                    new SemanticBranch.Condition(
                        "approved",
                        ConditionBranchRole.IF,
                        "status == 'ok'",
                        1,
                        "call-a",
                        List.of("call-a")),
                    new SemanticBranch.Condition(
                        "rejected",
                        ConditionBranchRole.IF,
                        "status != 'ok'",
                        2,
                        "call-b",
                        List.of("call-b"))),
                "script-common")),
        edges,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision copy(ChainSemanticRevision base, List<SemanticEntryPoint> entries) {
    return copy(base, entries, base.nodes(), base.regions(), base.executionEdges());
  }

  private static ChainSemanticRevision copy(
      ChainSemanticRevision base,
      List<SemanticEntryPoint> entries,
      List<SemanticNode> nodes,
      List<SemanticRegion> regions,
      List<SemanticExecutionEdge> edges) {
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        entries,
        nodes,
        regions,
        edges,
        base.containment(),
        base.mappingIntents(),
        base.constraints(),
        base.assumptions(),
        base.citations());
  }
}
