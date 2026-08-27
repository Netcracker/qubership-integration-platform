package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class DefaultChainSemanticRevisionValidatorTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final ChainSemanticRevisionValidator validator =
      new DefaultChainSemanticRevisionValidator();

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractFailures")
  void rejectsInvalidRevisions(String unused, ChainSemanticRevision revision, String message) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> validator.validate(revision, CONTRACT));
    assertTrue(error.getMessage().startsWith("Invalid chain semantic revision:"));
    assertTrue(error.getMessage().contains(message), error.getMessage());
  }

  static Stream<Arguments> contractFailures() {
    return Stream.of(
        Arguments.of(
            "no entries",
            revisionWithNoEntries(),
            "entryPoints must contain at least one entry"),
        Arguments.of(
            "execution cycle",
            revisionWithCycle(),
            "execution edges must form a DAG"),
        Arguments.of(
            "async split with zero branches",
            asyncSplitWithZeroBranches(),
            "split-async-2 requires at least 1 branch"));
  }

  @Test
  void acceptsLinearSequenceAndSingleBranchAsyncSplit() {
    assertDoesNotThrow(() -> validator.validate(linearRevision(), CONTRACT));
    assertDoesNotThrow(() -> validator.validate(asyncSplitOneBranchRevision(), CONTRACT));
    assertDoesNotThrow(() -> validator.validate(typedLoopRevision(), CONTRACT));
  }

  @Test
  void rejectsDuplicateServiceCallId() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(revisionWithDuplicateServiceCallId(), CONTRACT));
    assertTrue(error.getMessage().contains("Duplicate serviceCallId: call-1"), error.getMessage());
  }

  @Test
  void rejectsOrphanMapping() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(revisionWithOrphanMapping(), CONTRACT));
    assertTrue(error.getMessage().contains("orphan mapping intent: map-orphan"), error.getMessage());
  }

  @Test
  void rejectsUnknownContractElement() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(revisionWithUnknownElement(), CONTRACT));
    assertTrue(error.getMessage().contains("Unknown contract element: choice"), error.getMessage());
  }

  @Test
  void rejectsHiddenJoin() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(revisionWithHiddenJoin(), CONTRACT));
    assertTrue(
        error.getMessage().contains("Unsupported topology: generic-barrier"), error.getMessage());
  }

  @Test
  void rejectsOldSemanticSchema() {
    CompilerContract oldSchema =
        new CompilerContract(
            CONTRACT.contractVersion(),
            "normalized-design-flow/v1",
            CONTRACT.elements(),
            CONTRACT.topology(),
            CONTRACT.requiredArtifacts(),
            CONTRACT.requiredAddons(),
            CONTRACT.requiredKnowledgeFragments(),
            CONTRACT.sha256());
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(linearRevision(), oldSchema));
    assertTrue(error.getMessage().contains("semantic schema version"), error.getMessage());
    assertTrue(error.getMessage().contains("normalized-design-flow/v1"), error.getMessage());
  }

  @Test
  void rejectsMissingExecutionRoute() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(revisionWithMissingRoute(), CONTRACT));
    assertTrue(error.getMessage().contains("missing a route"), error.getMessage());
  }

  @Test
  void mappingEndpointPredicateRejectsReconvergenceAndMultiIncoming() {
    assertTrue(BriefMappingValidator.isMappingEndpoint(1, false));
    assertFalse(BriefMappingValidator.isMappingEndpoint(1, true));
    assertFalse(BriefMappingValidator.isMappingEndpoint(2, false));
  }

  private static ChainSemanticRevision revisionWithNoEntries() {
    return copy(linearRevision(), List.of(), linearRevision().nodes(), linearRevision().regions(),
        linearRevision().executionEdges(), linearRevision().containment(),
        linearRevision().mappingIntents());
  }

  private static ChainSemanticRevision revisionWithCycle() {
    ChainSemanticRevision linear = linearRevision();
    List<SemanticExecutionEdge> edges = new ArrayList<>(linear.executionEdges());
    edges.add(
        new SemanticExecutionEdge(
            "edge-cycle",
            "node-call",
            "op-shared",
            null,
            new SemanticRoute.Sequence(),
            null));
    return copy(
        linear,
        linear.entryPoints(),
        linear.nodes(),
        linear.regions(),
        edges,
        linear.containment(),
        linear.mappingIntents());
  }

  private static ChainSemanticRevision asyncSplitWithZeroBranches() {
    ChainSemanticRevision linear = linearRevision();
    List<SemanticNode> nodes = new ArrayList<>(linear.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "split-async-1", "split-async-2", new SemanticProvenance(List.of())));
    SemanticRegion.Split split =
        new SemanticRegion.Split("region-async-split", "split-async-1", SplitMode.ASYNC, List.of(),
            null);
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
        List.of(split),
        edges,
        linear.containment(),
        linear.mappingIntents());
  }

  private static ChainSemanticRevision revisionWithDuplicateServiceCallId() {
    ChainSemanticRevision linear = linearRevision();
    List<SemanticNode> nodes = new ArrayList<>(linear.nodes());
    nodes.add(
        new SemanticNode.ServiceCall(
            "node-call-2", "call-1", "getOrder", new SemanticProvenance(List.of())));
    List<SemanticExecutionEdge> edges = new ArrayList<>(linear.executionEdges());
    edges.add(
        new SemanticExecutionEdge(
            "edge-call-2",
            "node-call",
            "node-call-2",
            null,
            new SemanticRoute.Sequence(),
            null));
    return copy(
        linear,
        linear.entryPoints(),
        nodes,
        linear.regions(),
        edges,
        linear.containment(),
        linear.mappingIntents());
  }

  private static ChainSemanticRevision revisionWithOrphanMapping() {
    ChainSemanticRevision linear = linearRevision();
    List<MappingIntent> mappings = new ArrayList<>(linear.mappingIntents());
    mappings.add(
        new MappingIntent(
            "map-orphan",
            "missing-edge",
            MappingPort.OUTPUT,
            "missing-edge",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("id", "orderId", null))));
    return copy(
        linear,
        linear.entryPoints(),
        linear.nodes(),
        linear.regions(),
        linear.executionEdges(),
        linear.containment(),
        mappings);
  }

  private static ChainSemanticRevision revisionWithUnknownElement() {
    ChainSemanticRevision linear = linearRevision();
    List<SemanticNode> nodes = new ArrayList<>(linear.nodes());
    nodes.add(new SemanticNode.Operation("choice-1", "choice", new SemanticProvenance(List.of())));
    List<SemanticExecutionEdge> edges = new ArrayList<>(linear.executionEdges());
    edges.add(
        new SemanticExecutionEdge(
            "edge-choice",
            "node-call",
            "choice-1",
            null,
            new SemanticRoute.Sequence(),
            null));
    return copy(
        linear,
        linear.entryPoints(),
        nodes,
        linear.regions(),
        edges,
        linear.containment(),
        linear.mappingIntents());
  }

  private static ChainSemanticRevision revisionWithHiddenJoin() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode condition =
        new SemanticNode.Operation("condition-1", "condition", new SemanticProvenance(List.of()));
    SemanticNode callA =
        new SemanticNode.ServiceCall("call-a", "call-a", "getOrder", new SemanticProvenance(List.of()));
    SemanticNode callB =
        new SemanticNode.ServiceCall("call-b", "call-b", "getItem", new SemanticProvenance(List.of()));
    SemanticNode join =
        new SemanticNode.Operation("script-common", "script", new SemanticProvenance(List.of()));
    SemanticRegion.Condition region =
        new SemanticRegion.Condition(
            "region-condition",
            "condition-1",
            List.of(
                new SemanticBranch.Condition(
                    "approved", ConditionBranchRole.IF, "status == 'ok'", 1, "call-a", List.of("call-a")),
                new SemanticBranch.Condition(
                    "rejected", ConditionBranchRole.IF, "status != 'ok'", 2, "call-b", List.of("call-b"))),
            "script-common");
    List<SemanticExecutionEdge> edges =
        List.of(
            sequence("edge-entry", "trigger-http", "condition-1", null, null),
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
            sequence("edge-join-a", "call-a", "script-common", "region-condition", null),
            sequence("edge-join-b", "call-b", "script-common", "region-condition", null));
    return revision(
        List.of(entry("http-in", "trigger-http", "condition-1")),
        List.of(trigger, condition, callA, callB, join),
        List.of(region),
        edges,
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision revisionWithMissingRoute() {
    ChainSemanticRevision linear = linearRevision();
    SemanticExecutionEdge first = linear.executionEdges().getFirst();
    SemanticExecutionEdge missingRoute =
        new SemanticExecutionEdge(
            first.edgeId(),
            first.sourceNodeId(),
            first.targetNodeId(),
            first.regionId(),
            null,
            first.mappingId());
    List<SemanticExecutionEdge> edges = new ArrayList<>(linear.executionEdges());
    edges.set(0, missingRoute);
    return copy(
        linear,
        linear.entryPoints(),
        linear.nodes(),
        linear.regions(),
        edges,
        linear.containment(),
        linear.mappingIntents());
  }

  private static ChainSemanticRevision linearRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode script =
        new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of()));
    SemanticNode call =
        new SemanticNode.ServiceCall(
            "node-call", "call-1", "getOrder", new SemanticProvenance(List.of()));
    List<SemanticExecutionEdge> edges =
        List.of(
            sequence("edge-entry", "trigger-http", "op-shared", null, null),
            sequence("edge-call", "op-shared", "node-call", null, "map-body"));
    MappingIntent mapping =
        new MappingIntent(
            "map-body",
            "edge-call",
            MappingPort.OUTPUT,
            "edge-call",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("id", "orderId", null)));
    return revision(
        List.of(entry("http-in", "trigger-http", "op-shared")),
        List.of(trigger, script, call),
        List.of(),
        edges,
        List.of(),
        List.of(mapping));
  }

  private static ChainSemanticRevision asyncSplitOneBranchRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode split =
        new SemanticNode.Operation(
            "split-async-1", "split-async-2", new SemanticProvenance(List.of()));
    SemanticNode notify =
        new SemanticNode.ServiceCall(
            "call-notify", "call-notify", "notify", new SemanticProvenance(List.of()));
    SemanticRegion.Split region =
        new SemanticRegion.Split(
            "region-async-split",
            "split-async-1",
            SplitMode.ASYNC,
            List.of(new SemanticBranch.Split("notify", 0, "call-notify", List.of("call-notify"))),
            null);
    List<SemanticExecutionEdge> edges =
        List.of(
            sequence("edge-entry", "trigger-http", "split-async-1", null, null),
            new SemanticExecutionEdge(
                "edge-notify",
                "split-async-1",
                "call-notify",
                "region-async-split",
                new SemanticRoute.SplitBranch("notify"),
                null));
    return revision(
        List.of(entry("http-in", "trigger-http", "split-async-1")),
        List.of(trigger, split, notify),
        List.of(region),
        edges,
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision typedLoopRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode loop =
        new SemanticNode.Operation("loop-1", "loop-2", new SemanticProvenance(List.of()));
    SemanticNode body =
        new SemanticNode.Operation("body-script", "script", new SemanticProvenance(List.of()));
    SemanticNode after =
        new SemanticNode.Operation("after-loop", "script", new SemanticProvenance(List.of()));
    SemanticRegion.Loop region =
        new SemanticRegion.Loop(
            "loop-region",
            "loop-1",
            "body-script",
            List.of("body-script"),
            "after-loop",
            new LoopPolicy(LoopMode.COPY, "items", 1500));
    List<SemanticExecutionEdge> edges =
        List.of(
            sequence("edge-entry", "trigger-http", "loop-1", null, null),
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
                null));
    return revision(
        List.of(entry("http-in", "trigger-http", "loop-1")),
        List.of(trigger, loop, body, after),
        List.of(region),
        edges,
        List.of(),
        List.of());
  }

  private static SemanticEntryPoint entry(String id, String triggerNodeId, String targetNodeId) {
    return new SemanticEntryPoint(
        id, triggerNodeId, targetNodeId, 0, new SemanticProvenance(List.of()), null);
  }

  private static SemanticExecutionEdge sequence(
      String edgeId, String from, String to, String regionId, String mappingId) {
    return new SemanticExecutionEdge(
        edgeId, from, to, regionId, new SemanticRoute.Sequence(), mappingId);
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

  private static ChainSemanticRevision copy(
      ChainSemanticRevision base,
      List<SemanticEntryPoint> entryPoints,
      List<SemanticNode> nodes,
      List<SemanticRegion> regions,
      List<SemanticExecutionEdge> edges,
      List<SemanticContainment> containment,
      List<MappingIntent> mappings) {
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        entryPoints,
        nodes,
        regions,
        edges,
        containment,
        mappings,
        base.constraints(),
        base.assumptions(),
        base.citations());
  }
}
