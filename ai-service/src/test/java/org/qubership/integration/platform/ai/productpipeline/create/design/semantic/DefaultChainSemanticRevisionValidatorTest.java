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
            "split-async-2 requires at least 1 branch"),
        Arguments.of(
            "unknown condition branch",
            revisionWithUnknownConditionBranch(),
            "branchId 'ghost' is missing from region"),
        Arguments.of(
            "unknown split branch",
            revisionWithUnknownSplitBranch(),
            "branchId 'ghost' is missing from region"),
        Arguments.of(
            "unknown catch handler",
            revisionWithUnknownCatchHandler(),
            "handlerId 'ghost' is missing from region"),
        Arguments.of(
            "unknown reconverge branch",
            revisionWithUnknownReconvergeBranch(),
            "branchId 'ghost' is missing from region"),
        Arguments.of(
            "unknown containment role",
            revisionWithUnknownContainmentRole(),
            "Containment role 'when' is not allowed on parent"),
        Arguments.of(
            "condition with zero IF",
            revisionWithNoIfBranch(),
            "condition requires at least 1 IF branch"),
        Arguments.of(
            "condition with two ELSE",
            revisionWithTwoElseBranches(),
            "condition allows at most 1 ELSE branch"),
        Arguments.of(
            "duplicate IF priority",
            revisionWithDuplicateIfPriority(),
            "requires unique IF priorities"),
        Arguments.of(
            "empty IF predicate",
            revisionWithEmptyIfPredicate(),
            "requires a non-empty predicate"),
        Arguments.of(
            "mapping on reconverge",
            revisionWithMappingOnReconverge(),
            "Unsupported topology: generic-aggregate"),
        Arguments.of(
            "unreachable node",
            revisionWithUnreachableNode(),
            "is not reachable from any entry point"),
        Arguments.of(
            "duplicate node id",
            revisionWithDuplicateNodeId(),
            "Duplicate node id: op-shared"),
        Arguments.of(
            "containment cycle",
            revisionWithContainmentCycle(),
            "containment relations must form a DAG"));
  }

  @Test
  void acceptsLinearSequenceAndSingleBranchAsyncSplit() {
    assertDoesNotThrow(() -> validator.validate(linearRevision(), CONTRACT));
    assertDoesNotThrow(() -> validator.validate(asyncSplitOneBranchRevision(), CONTRACT));
    assertDoesNotThrow(() -> validator.validate(typedLoopRevision(), CONTRACT));
  }

  @Test
  void acceptsTypedReconvergenceAndSyncSplit() {
    assertDoesNotThrow(() -> validator.validate(typedReconvergeRevision(), CONTRACT));
    assertDoesNotThrow(() -> validator.validate(syncSplitRevision(), CONTRACT));
  }

  @Test
  void acceptsRetryAndErrorScope() {
    assertDoesNotThrow(() -> validator.validate(retryRevision(), CONTRACT));
    assertDoesNotThrow(() -> validator.validate(errorScopeRevision("catch-all"), CONTRACT));
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

  @Test
  void rejectsCompilerContractVersionMismatch() {
    CompilerContract otherContract =
        new CompilerContract(
            "create-chain-compiler-contract/v0",
            CONTRACT.semanticSchemaVersion(),
            CONTRACT.elements(),
            CONTRACT.topology(),
            CONTRACT.requiredArtifacts(),
            CONTRACT.requiredAddons(),
            CONTRACT.requiredKnowledgeFragments(),
            CONTRACT.sha256());
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(linearRevision(), otherContract));
    assertTrue(error.getMessage().contains("compiler contract version"), error.getMessage());
    assertTrue(
        error.getMessage().contains("create-chain-compiler-contract/v0"), error.getMessage());
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

  private static ChainSemanticRevision typedReconvergeRevision() {
    return twoIfConditionRevision("approved", List.of("approved"), List.of("rejected"), null, "if");
  }

  private static ChainSemanticRevision revisionWithUnknownConditionBranch() {
    return twoIfConditionRevision("ghost", List.of("approved"), List.of("rejected"), null, "if");
  }

  private static ChainSemanticRevision revisionWithUnknownReconvergeBranch() {
    return twoIfConditionRevision("approved", List.of("ghost"), List.of("rejected"), null, "if");
  }

  private static ChainSemanticRevision revisionWithUnknownContainmentRole() {
    return twoIfConditionRevision("approved", List.of("approved"), List.of("rejected"), null, "when");
  }

  private static ChainSemanticRevision revisionWithMappingOnReconverge() {
    return twoIfConditionRevision(
        "approved", List.of("approved"), List.of("rejected"), "map-join", "if");
  }

  private static ChainSemanticRevision twoIfConditionRevision(
      String approvedRouteId,
      List<String> approvedJoinBranchIds,
      List<String> rejectedJoinBranchIds,
      String joinMappingId,
      String containmentRole) {
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
            "script-common");
    List<SemanticExecutionEdge> edges =
        List.of(
            sequence("edge-entry", "trigger-http", "condition-1", null, null),
            new SemanticExecutionEdge(
                "edge-approved",
                "condition-1",
                "call-a",
                "region-condition",
                new SemanticRoute.ConditionBranch(approvedRouteId),
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
                new SemanticRoute.Reconverge(approvedJoinBranchIds),
                joinMappingId),
            new SemanticExecutionEdge(
                "edge-join-b",
                "call-b",
                "script-common",
                "region-condition",
                new SemanticRoute.Reconverge(rejectedJoinBranchIds),
                null));
    List<MappingIntent> mappings =
        joinMappingId == null
            ? List.of()
            : List.of(mapping(joinMappingId, "edge-join-a"));
    return revision(
        List.of(entry("http-in", "trigger-http", "condition-1")),
        List.of(trigger, condition, callA, callB, join),
        List.of(region),
        edges,
        List.of(
            new SemanticContainment("condition-1", "call-a", containmentRole),
            new SemanticContainment("condition-1", "call-b", "if")),
        mappings);
  }

  private static ChainSemanticRevision revisionWithUnknownSplitBranch() {
    ChainSemanticRevision base = asyncSplitOneBranchRevision();
    List<SemanticExecutionEdge> edges = new ArrayList<>();
    for (SemanticExecutionEdge edge : base.executionEdges()) {
      if ("edge-notify".equals(edge.edgeId())) {
        edges.add(
            new SemanticExecutionEdge(
                edge.edgeId(),
                edge.sourceNodeId(),
                edge.targetNodeId(),
                edge.regionId(),
                new SemanticRoute.SplitBranch("ghost"),
                edge.mappingId()));
      } else {
        edges.add(edge);
      }
    }
    return copy(
        base,
        base.entryPoints(),
        base.nodes(),
        base.regions(),
        edges,
        base.containment(),
        base.mappingIntents());
  }

  private static ChainSemanticRevision revisionWithUnknownCatchHandler() {
    return errorScopeRevision("ghost");
  }

  private static ChainSemanticRevision revisionWithNoIfBranch() {
    return singleConditionRevision(
        List.of(
            new SemanticBranch.Condition(
                "fallback", ConditionBranchRole.ELSE, null, 0, "call-else", List.of("call-else"))),
        List.of(
            new SemanticNode.ServiceCall(
                "call-else", "call-else", "fallback", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticExecutionEdge(
                "edge-else",
                "condition-1",
                "call-else",
                "region-condition",
                new SemanticRoute.ConditionBranch("fallback"),
                null)));
  }

  private static ChainSemanticRevision revisionWithTwoElseBranches() {
    return singleConditionRevision(
        List.of(
            new SemanticBranch.Condition(
                "approved",
                ConditionBranchRole.IF,
                "status == 'ok'",
                1,
                "call-a",
                List.of("call-a")),
            new SemanticBranch.Condition(
                "else-a", ConditionBranchRole.ELSE, null, 0, "call-b", List.of("call-b")),
            new SemanticBranch.Condition(
                "else-b", ConditionBranchRole.ELSE, null, 0, "call-c", List.of("call-c"))),
        List.of(
            new SemanticNode.ServiceCall(
                "call-a", "call-a", "getOrder", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-b", "call-b", "getItem", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-c", "call-c", "getOther", new SemanticProvenance(List.of()))),
        List.of(
            branchEdge("edge-approved", "call-a", "approved"),
            branchEdge("edge-else-a", "call-b", "else-a"),
            branchEdge("edge-else-b", "call-c", "else-b")));
  }

  private static ChainSemanticRevision revisionWithDuplicateIfPriority() {
    return singleConditionRevision(
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
                1,
                "call-b",
                List.of("call-b"))),
        List.of(
            new SemanticNode.ServiceCall(
                "call-a", "call-a", "getOrder", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-b", "call-b", "getItem", new SemanticProvenance(List.of()))),
        List.of(
            branchEdge("edge-approved", "call-a", "approved"),
            branchEdge("edge-rejected", "call-b", "rejected")));
  }

  private static ChainSemanticRevision revisionWithEmptyIfPredicate() {
    return singleConditionRevision(
        List.of(
            new SemanticBranch.Condition(
                "approved", ConditionBranchRole.IF, "  ", 1, "call-a", List.of("call-a"))),
        List.of(
            new SemanticNode.ServiceCall(
                "call-a", "call-a", "getOrder", new SemanticProvenance(List.of()))),
        List.of(branchEdge("edge-approved", "call-a", "approved")));
  }

  private static ChainSemanticRevision singleConditionRevision(
      List<SemanticBranch.Condition> branches,
      List<SemanticNode> branchNodes,
      List<SemanticExecutionEdge> branchEdges) {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode condition =
        new SemanticNode.Operation("condition-1", "condition", new SemanticProvenance(List.of()));
    List<SemanticNode> nodes = new ArrayList<>();
    nodes.add(trigger);
    nodes.add(condition);
    nodes.addAll(branchNodes);
    List<SemanticExecutionEdge> edges = new ArrayList<>();
    edges.add(sequence("edge-entry", "trigger-http", "condition-1", null, null));
    edges.addAll(branchEdges);
    List<SemanticContainment> containment = new ArrayList<>();
    for (SemanticBranch.Condition branch : branches) {
      String role = branch.role() == ConditionBranchRole.IF ? "if" : "else";
      containment.add(new SemanticContainment("condition-1", branch.entryNodeId(), role));
    }
    return revision(
        List.of(entry("http-in", "trigger-http", "condition-1")),
        nodes,
        List.of(new SemanticRegion.Condition("region-condition", "condition-1", branches, null)),
        edges,
        containment,
        List.of());
  }

  private static ChainSemanticRevision syncSplitRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode split =
        new SemanticNode.Operation("split-1", "split-2", new SemanticProvenance(List.of()));
    SemanticNode left =
        new SemanticNode.ServiceCall(
            "call-left", "call-left", "getLeft", new SemanticProvenance(List.of()));
    SemanticNode right =
        new SemanticNode.ServiceCall(
            "call-right", "call-right", "getRight", new SemanticProvenance(List.of()));
    SemanticNode after =
        new SemanticNode.Operation("after-split", "script", new SemanticProvenance(List.of()));
    SemanticRegion.Split region =
        new SemanticRegion.Split(
            "region-sync-split",
            "split-1",
            SplitMode.SYNC,
            List.of(
                new SemanticBranch.Split("left", 0, "call-left", List.of("call-left")),
                new SemanticBranch.Split("right", 1, "call-right", List.of("call-right"))),
            "after-split");
    List<SemanticExecutionEdge> edges =
        List.of(
            sequence("edge-entry", "trigger-http", "split-1", null, null),
            new SemanticExecutionEdge(
                "edge-left",
                "split-1",
                "call-left",
                "region-sync-split",
                new SemanticRoute.SplitBranch("left"),
                null),
            new SemanticExecutionEdge(
                "edge-right",
                "split-1",
                "call-right",
                "region-sync-split",
                new SemanticRoute.SplitBranch("right"),
                null),
            new SemanticExecutionEdge(
                "edge-join-left",
                "call-left",
                "after-split",
                "region-sync-split",
                new SemanticRoute.Reconverge(List.of("left")),
                null),
            new SemanticExecutionEdge(
                "edge-join-right",
                "call-right",
                "after-split",
                "region-sync-split",
                new SemanticRoute.Reconverge(List.of("right")),
                null));
    return revision(
        List.of(entry("http-in", "trigger-http", "split-1")),
        List.of(trigger, split, left, right, after),
        List.of(region),
        edges,
        List.of(
            new SemanticContainment("split-1", "call-left", "split-element-2"),
            new SemanticContainment("split-1", "call-right", "split-element-2")),
        List.of());
  }

  private static ChainSemanticRevision retryRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode call =
        new SemanticNode.ServiceCall(
            "call-1", "call-1", "getOrder", new SemanticProvenance(List.of()));
    SemanticNode after =
        new SemanticNode.Operation("after-retry", "script", new SemanticProvenance(List.of()));
    SemanticRegion.Retry region =
        new SemanticRegion.Retry(
            "retry-region",
            "call-1",
            "call-1",
            List.of("call-1"),
            "after-retry",
            new RetryPolicy(3, 5000));
    List<SemanticExecutionEdge> edges =
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
                null));
    return revision(
        List.of(entry("http-in", "trigger-http", "call-1")),
        List.of(trigger, call, after),
        List.of(region),
        edges,
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision errorScopeRevision(String catchRouteId) {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode owner =
        new SemanticNode.Operation(
            "try-catch-1", "try-catch-finally-2", new SemanticProvenance(List.of()));
    SemanticNode tryBody =
        new SemanticNode.Operation("try-body", "script", new SemanticProvenance(List.of()));
    SemanticNode catchBody =
        new SemanticNode.Operation("catch-body", "script", new SemanticProvenance(List.of()));
    SemanticNode finallyBody =
        new SemanticNode.Operation("finally-script", "script", new SemanticProvenance(List.of()));
    SemanticRegion.ErrorScope scope =
        new SemanticRegion.ErrorScope(
            "error-region",
            "try-catch-1",
            "try-body",
            List.of(
                new ErrorHandler(
                    "catch-all", "java.lang.Exception", "catch-body", List.of("catch-body"))),
            "finally-script",
            List.of("finally-script"));
    List<SemanticExecutionEdge> edges =
        List.of(
            sequence("edge-entry", "trigger-http", "try-catch-1", null, null),
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
                new SemanticRoute.CatchPath(catchRouteId),
                null),
            new SemanticExecutionEdge(
                "edge-finally",
                "try-catch-1",
                "finally-script",
                "error-region",
                new SemanticRoute.FinallyPath(),
                null));
    return revision(
        List.of(entry("http-in", "trigger-http", "try-catch-1")),
        List.of(trigger, owner, tryBody, catchBody, finallyBody),
        List.of(scope),
        edges,
        List.of(
            new SemanticContainment("try-catch-1", "try-body", "try-2"),
            new SemanticContainment("try-catch-1", "catch-body", "catch-2"),
            new SemanticContainment("try-catch-1", "finally-script", "finally-2")),
        List.of());
  }

  private static ChainSemanticRevision revisionWithUnreachableNode() {
    ChainSemanticRevision linear = linearRevision();
    List<SemanticNode> nodes = new ArrayList<>(linear.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "orphan-script", "script", new SemanticProvenance(List.of())));
    return copy(
        linear,
        linear.entryPoints(),
        nodes,
        linear.regions(),
        linear.executionEdges(),
        linear.containment(),
        linear.mappingIntents());
  }

  private static ChainSemanticRevision revisionWithDuplicateNodeId() {
    ChainSemanticRevision linear = linearRevision();
    List<SemanticNode> nodes = new ArrayList<>(linear.nodes());
    nodes.add(
        new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of())));
    return copy(
        linear,
        linear.entryPoints(),
        nodes,
        linear.regions(),
        linear.executionEdges(),
        linear.containment(),
        linear.mappingIntents());
  }

  private static ChainSemanticRevision revisionWithContainmentCycle() {
    ChainSemanticRevision linear = linearRevision();
    return copy(
        linear,
        linear.entryPoints(),
        linear.nodes(),
        linear.regions(),
        linear.executionEdges(),
        List.of(
            new SemanticContainment("op-shared", "node-call", "if"),
            new SemanticContainment("node-call", "op-shared", "if")),
        linear.mappingIntents());
  }

  private static SemanticExecutionEdge branchEdge(String edgeId, String target, String branchId) {
    return new SemanticExecutionEdge(
        edgeId,
        "condition-1",
        target,
        "region-condition",
        new SemanticRoute.ConditionBranch(branchId),
        null);
  }

  private static MappingIntent mapping(String mappingId, String edgeId) {
    return new MappingIntent(
        mappingId,
        edgeId,
        MappingPort.OUTPUT,
        edgeId,
        MappingPort.REQUEST,
        List.of(new MappingIntentRule("id", "orderId", null)));
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
