package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedOperation;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedTrigger;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegionKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRouteKind;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class ChainSemanticCaptureAdapterTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final ChainSemanticCaptureAdapter adapter =
      new ChainSemanticCaptureAdapter(new ChainSemanticCanonicalizer());

  @Test
  void projectsALinearFlowAndFillsServerOwnedStateFromTheContractAndBrief() {
    ChainSemanticRevision revision = adapt(ChainSemanticCaptureFixtures.linearCapture());

    assertEquals(CONTRACT.semanticSchemaVersion(), revision.schemaVersion());
    assertEquals(CONTRACT.contractVersion(), revision.compilerContractVersion());
    assertEquals("chain-orders", revision.chainIdentity());
    assertTrue(revision.revisionId().startsWith("semantic-"), revision.revisionId());

    SemanticNode.Trigger trigger = node(revision, SemanticNode.Trigger.class);
    assertEquals("http-trigger", trigger.capabilityKey());
    SemanticNode.ServiceCall call = node(revision, SemanticNode.ServiceCall.class);
    assertEquals("getOrder", call.operation());
    assertEquals(List.of("trigger-1"), trigger.provenance().sourceFactIds());

    assertEquals(
        ChainSemanticCaptureFixtures.approvedBrief().constraints(), revision.constraints());
    assertEquals(
        ChainSemanticCaptureFixtures.approvedBrief().assumptions(), revision.assumptions());
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      assertTrue(edge.edgeId().startsWith("edge-"), edge.edgeId());
      assertEquals(SemanticRouteKind.SEQUENCE, edge.route().kind());
    }
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT);
  }

  @Test
  void doesNotCreateServiceCallNodeForCatalogBoundAsyncApiTrigger() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.catalogBoundAsyncApiTriggerBrief();
    ChainSemanticCapture capture =
        new ChainSemanticCapture(
            "chain-om",
            List.of(
                new CapturedEntryPoint(
                    "async-in",
                    "trigger-async",
                    "op-shared",
                    0,
                    List.of("fact-consume"),
                    "Consume OM",
                    null)),
            List.of(new CapturedTrigger("trigger-async", List.of("fact-consume"))),
            List.of(new CapturedOperation("op-shared", "script", List.of())),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new CapturedEdge("trigger-async", "op-shared", null, null, null, null, null, null)),
            List.of());

    ChainSemanticRevision revision = adapt(capture, brief);

    assertEquals(
        0, revision.nodes().stream().filter(SemanticNode.ServiceCall.class::isInstance).count());
    SemanticNode.Trigger trigger = node(revision, SemanticNode.Trigger.class);
    assertEquals("async-api-trigger", trigger.capabilityKey());
    assertEquals("trigger-async", trigger.nodeId());
  }

  @Test
  void rewritesMappingRefsFromFactIdsOntoTheCarryingEdge() {
    ChainSemanticRevision revision =
        adapt(
            ChainSemanticCaptureFixtures.mappedCapture(),
            ChainSemanticCaptureFixtures.briefWithMapping());

    MappingIntent projected = revision.mappingIntents().getFirst();
    SemanticExecutionEdge site =
        revision.executionEdges().stream()
            .filter(edge -> ChainSemanticCaptureFixtures.MAPPING_INTENT_ID.equals(edge.mappingId()))
            .findFirst()
            .orElseThrow();
    assertEquals(site.edgeId(), projected.sourceRef());
    assertEquals(site.edgeId(), projected.targetRef());
    assertEquals(MappingPort.OUTPUT, projected.sourcePort());
    assertEquals(MappingPort.REQUEST, projected.targetPort());
    assertEquals(List.of(new MappingIntentRule("id", "orderId", null)), projected.rules());
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT);
  }

  @Test
  void keepsIdentifiersStableWhenTheModelReordersTheInputLists() {
    ChainSemanticRevision straight =
        adapt(
            ChainSemanticCaptureFixtures.mappedCapture(),
            ChainSemanticCaptureFixtures.briefWithMapping());
    ChainSemanticRevision reordered =
        adapt(
            reverseEdges(ChainSemanticCaptureFixtures.mappedCapture()),
            ChainSemanticCaptureFixtures.briefWithMapping());

    assertEquals(edgeIds(straight), edgeIds(reordered));
    assertEquals(straight.revisionId(), reordered.revisionId());
  }

  @Test
  void repeatsTheSameRevisionIdForTheSameRunAndChangesItWhenTheDesignChanges() {
    ChainSemanticRevision first = adapt(ChainSemanticCaptureFixtures.linearCapture());
    ChainSemanticRevision restarted = adapt(ChainSemanticCaptureFixtures.linearCapture());
    assertEquals(first.revisionId(), restarted.revisionId());

    ChainSemanticRevision otherRun =
        adapter.adapt(
            ChainSemanticCaptureFixtures.linearCapture(),
            "run-2",
            ChainSemanticCaptureFixtures.approvedBrief(),
            CONTRACT);
    assertNotEquals(first.revisionId(), otherRun.revisionId());

    ChainSemanticRevision changedTopology =
        adapt(
            ChainSemanticCaptureFixtures.mappedCapture(),
            ChainSemanticCaptureFixtures.briefWithMapping());
    assertNotEquals(first.revisionId(), changedTopology.revisionId());

    RequirementBrief reopenedBrief =
        ChainSemanticCaptureFixtures.approvedBrief().withApprovedDraftText("reviewed draft");
    ChainSemanticRevision changedBrief =
        adapt(ChainSemanticCaptureFixtures.linearCapture(), amend(reopenedBrief));
    assertNotEquals(first.revisionId(), changedBrief.revisionId());
  }

  /** A reopened brief with one more constraint. The revision copies them, so its id moves. */
  private static RequirementBrief amend(RequirementBrief brief) {
    List<String> constraints = new ArrayList<>(brief.constraints());
    constraints.add("Reject an order without a delivery address");
    return new RequirementBrief(
        brief.goal(),
        brief.inputs(),
        constraints,
        brief.assumptions(),
        brief.citations(),
        brief.summary(),
        brief.approvedDraftReference(),
        brief.approvedDraftText(),
        brief.facts(),
        brief.dataMappings(),
        brief.entryPoints(),
        brief.serviceCalls(),
        brief.requirements(),
        brief.mappingIntents());
  }

  @Test
  void buildsControlFlowRegionsFromTheirOwnCaptureLists() {
    ChainSemanticRevision revision =
        adapt(conditionCapture(), ChainSemanticCaptureFixtures.approvedBrief());

    assertEquals(
        SemanticRegionKind.CONDITION, revision.regions().getFirst().kind());
    assertEquals(
        List.of(
            SemanticRouteKind.SEQUENCE,
            SemanticRouteKind.CONDITION_BRANCH,
            SemanticRouteKind.CONDITION_BRANCH),
        revision.executionEdges().stream()
            .map(edge -> edge.route().kind())
            .sorted()
            .toList());
  }

  @Test
  void derivesEntryPointsFromTheBriefWhenCaptureOmitsThem() {
    ChainSemanticCapture omitted =
        withEntryPoints(ChainSemanticCaptureFixtures.linearCapture(), List.of());

    ChainSemanticRevision revision = adapt(omitted);

    assertEquals(1, revision.entryPoints().size());
    assertEquals("http-in", revision.entryPoints().getFirst().entryPointId());
    assertEquals("trigger-http", revision.entryPoints().getFirst().triggerNodeId());
    assertEquals("op-shared", revision.entryPoints().getFirst().initialTargetNodeId());
    SemanticNode.Trigger trigger = node(revision, SemanticNode.Trigger.class);
    assertEquals("http-trigger", trigger.capabilityKey());
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT);
  }

  @Test
  void ignoresCaptureEntryPointsThatAreNotInTheApprovedBrief() {
    ChainSemanticCapture foreign =
        withEntryPoints(
            ChainSemanticCaptureFixtures.linearCapture(),
            List.of(
                new CapturedEntryPoint(
                    "foreign-entry",
                    "trigger-http",
                    "op-shared",
                    0,
                    List.of("trigger-1"),
                    null,
                    null)));

    ChainSemanticRevision revision = adapt(foreign);

    assertEquals("http-in", revision.entryPoints().getFirst().entryPointId());
  }

  @Test
  void rejectsAProvenanceFactOutsideTheApprovedBrief() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture foreign =
        withTriggers(
            capture, List.of(new CapturedTrigger("trigger-http", List.of("foreign-fact"))));
    assertTrue(failure(foreign).contains("foreign-fact"));
  }

  @Test
  void ignoresAnOperationThatRestatesAServerOwnedServiceCallNode() {
    ChainSemanticCapture capture =
        withOperations(
            ChainSemanticCaptureFixtures.linearCapture(),
            List.of(
                new CapturedOperation("op-shared", "script", List.of()),
                new CapturedOperation(
                    ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID, "service-call", List.of())));

    ChainSemanticRevision revision = adapt(capture);

    assertEquals(
        1, revision.nodes().stream().filter(SemanticNode.ServiceCall.class::isInstance).count());
    assertEquals(
        1, revision.nodes().stream().filter(SemanticNode.Operation.class::isInstance).count());
    SemanticNode.ServiceCall call = node(revision, SemanticNode.ServiceCall.class);
    assertEquals(ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID, call.nodeId());
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT);
  }

  @Test
  void ignoresAnOperationThatRestatesATriggerNode() {
    ChainSemanticCapture capture =
        withOperations(
            ChainSemanticCaptureFixtures.linearCapture(),
            List.of(
                new CapturedOperation("trigger-http", "script", List.of()),
                new CapturedOperation("op-shared", "script", List.of())));

    ChainSemanticRevision revision = adapt(capture);

    assertEquals(
        1, revision.nodes().stream().filter(SemanticNode.Trigger.class::isInstance).count());
    assertEquals(
        1, revision.nodes().stream().filter(SemanticNode.Operation.class::isInstance).count());
    assertEquals("op-shared", node(revision, SemanticNode.Operation.class).nodeId());
  }

  @Test
  void rejectsTwoModelOperationsWithTheSameNodeId() {
    ChainSemanticCapture capture =
        withOperations(
            ChainSemanticCaptureFixtures.linearCapture(),
            List.of(
                new CapturedOperation("op-shared", "script", List.of()),
                new CapturedOperation("dup-op", "script", List.of()),
                new CapturedOperation("dup-op", "script", List.of())));

    assertTrue(failure(capture).contains("Duplicate nodeId: dup-op"));
  }

  @Test
  void namesEachServiceCallNodeAfterTheBriefServiceCallId() {
    ChainSemanticRevision revision =
        adapt(
            ChainSemanticCaptureFixtures.linearCapture(),
            ChainSemanticCaptureFixtures.approvedBrief());

    SemanticNode.ServiceCall call =
        revision.nodes().stream()
            .filter(SemanticNode.ServiceCall.class::isInstance)
            .map(SemanticNode.ServiceCall.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("call-1", call.nodeId());
    assertEquals("call-1", call.serviceCallId());
    assertEquals("getOrder", call.operation());
  }

  @Test
  void rejectsABriefServiceCallWithoutAResolvedCatalogBinding() {
    RequirementBrief unbound =
        withServiceCalls(
            ChainSemanticCaptureFixtures.approvedBrief(),
            List.of(new RequirementServiceCall("call-1", "fact-call", "Orders API", "getOrder")));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> adapt(ChainSemanticCaptureFixtures.linearCapture(), unbound));
    assertTrue(failure.getMessage().contains("no resolved catalog binding"));
  }

  @Test
  void rejectsAnElementTypeTheCompilerContractDoesNotDeclare() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture unknown =
        withOperations(
            capture, List.of(new CapturedOperation("op-shared", "quantum-mapper", List.of())));
    assertTrue(failure(unknown).contains("quantum-mapper"));
  }

  @Test
  void rejectsAMappingIdTheApprovedBriefDoesNotOwn() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.mappedCapture();
    assertTrue(failure(capture).contains(ChainSemanticCaptureFixtures.MAPPING_INTENT_ID));
  }

  @Test
  void rejectsOneMappingPlacedOnTwoEdges() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.mappedCapture();
    List<CapturedEdge> edges = new ArrayList<>();
    for (CapturedEdge edge : capture.edges()) {
      edges.add(
          new CapturedEdge(
              edge.sourceNodeId(),
              edge.targetNodeId(),
              edge.regionId(),
              edge.routeKind(),
              edge.branchId(),
              edge.branchIds(),
              edge.handlerId(),
              ChainSemanticCaptureFixtures.MAPPING_INTENT_ID));
    }
    String message =
        failure(withEdges(capture, edges), ChainSemanticCaptureFixtures.briefWithMapping());
    assertTrue(message.contains("more than one edge"), message);
  }

  @Test
  void rejectsAnApprovedMappingThatNoEdgeCarries() {
    String message =
        failure(
            ChainSemanticCaptureFixtures.linearCapture(),
            ChainSemanticCaptureFixtures.briefWithMapping());
    assertTrue(message.contains("is not placed on any edge"), message);
  }

  @Test
  void rejectsAMappingWithoutAnAdjacentTransformSite() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.mappedCapture();
    ChainSemanticCapture noTransform =
        withOperations(
            capture, List.of(new CapturedOperation("op-shared", "condition", List.of())));
    String message = failure(noTransform, ChainSemanticCaptureFixtures.briefWithMapping());
    assertTrue(message.contains("mapper-2"), message);
  }

  private ChainSemanticRevision adapt(ChainSemanticCapture capture) {
    return adapt(capture, ChainSemanticCaptureFixtures.approvedBrief());
  }

  private ChainSemanticRevision adapt(ChainSemanticCapture capture, RequirementBrief brief) {
    return adapter.adapt(capture, "run-1", brief, CONTRACT);
  }

  private String failure(ChainSemanticCapture capture) {
    return failure(capture, ChainSemanticCaptureFixtures.approvedBrief());
  }

  private String failure(ChainSemanticCapture capture, RequirementBrief brief) {
    return assertThrows(IllegalArgumentException.class, () -> adapt(capture, brief)).getMessage();
  }

  private static List<String> edgeIds(ChainSemanticRevision revision) {
    return revision.executionEdges().stream().map(SemanticExecutionEdge::edgeId).sorted().toList();
  }

  private static <T extends SemanticNode> T node(ChainSemanticRevision revision, Class<T> type) {
    return revision.nodes().stream()
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow();
  }

  private static ChainSemanticCapture conditionCapture() {
    return new ChainSemanticCapture(
        "chain-orders",
        List.of(
            new CapturedEntryPoint(
                "http-in", "trigger-http", "op-condition", 0, List.of("trigger-1"), null, null)),
        List.of(new CapturedTrigger("trigger-http", List.of("trigger-1"))),
        List.of(
            new CapturedOperation("op-condition", "condition", List.of()),
            new CapturedOperation("op-else", "script", List.of())),
        List.of(),
        List.of(
            new ChainSemanticCapture.CapturedConditionRegion(
                "region-branch",
                "op-condition",
                List.of(
                    new ChainSemanticCapture.CapturedConditionBranch(
                        "branch-if",
                        ConditionBranchRole.IF,
                        "${header.kind} == 'order'",
                        0,
                        ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                        List.of(ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID)),
                    new ChainSemanticCapture.CapturedConditionBranch(
                        "branch-else",
                        ConditionBranchRole.ELSE,
                        null,
                        1,
                        "op-else",
                        List.of("op-else"))),
                null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new CapturedEdge("trigger-http", "op-condition", null, null, null, null, null, null),
            new CapturedEdge(
                "op-condition",
                ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                "region-branch",
                SemanticRouteKind.CONDITION_BRANCH,
                "branch-if",
                null,
                null,
                null),
            new CapturedEdge(
                "op-condition",
                "op-else",
                "region-branch",
                SemanticRouteKind.CONDITION_BRANCH,
                "branch-else",
                null,
                null,
                null)),
        List.of());
  }

  private static ChainSemanticCapture reverseEdges(ChainSemanticCapture capture) {
    List<CapturedEdge> reversed = new ArrayList<>(capture.edges());
    java.util.Collections.reverse(reversed);
    return withEdges(capture, reversed);
  }

  private static ChainSemanticCapture withEdges(
      ChainSemanticCapture capture, List<CapturedEdge> edges) {
    return new ChainSemanticCapture(
        capture.chainIdentity(),
        capture.entryPoints(),
        capture.triggers(),
        capture.operations(),
        capture.sequenceRegions(),
        capture.conditionRegions(),
        capture.splitRegions(),
        capture.loopRegions(),
        capture.retryRegions(),
        capture.errorScopeRegions(),
        edges,
        capture.containment());
  }

  private static ChainSemanticCapture withEntryPoints(
      ChainSemanticCapture capture, List<CapturedEntryPoint> entryPoints) {
    return new ChainSemanticCapture(
        capture.chainIdentity(),
        entryPoints,
        capture.triggers(),
        capture.operations(),
        capture.sequenceRegions(),
        capture.conditionRegions(),
        capture.splitRegions(),
        capture.loopRegions(),
        capture.retryRegions(),
        capture.errorScopeRegions(),
        capture.edges(),
        capture.containment());
  }

  private static ChainSemanticCapture withTriggers(
      ChainSemanticCapture capture, List<CapturedTrigger> triggers) {
    return new ChainSemanticCapture(
        capture.chainIdentity(),
        capture.entryPoints(),
        triggers,
        capture.operations(),
        capture.sequenceRegions(),
        capture.conditionRegions(),
        capture.splitRegions(),
        capture.loopRegions(),
        capture.retryRegions(),
        capture.errorScopeRegions(),
        capture.edges(),
        capture.containment());
  }

  private static RequirementBrief withServiceCalls(
      RequirementBrief brief, List<RequirementServiceCall> serviceCalls) {
    return new RequirementBrief(
        brief.goal(),
        brief.inputs(),
        brief.constraints(),
        brief.assumptions(),
        brief.citations(),
        brief.summary(),
        brief.approvedDraftReference(),
        brief.approvedDraftText(),
        brief.facts(),
        brief.dataMappings(),
        brief.entryPoints(),
        serviceCalls,
        brief.requirements(),
        brief.mappingIntents());
  }

  private static ChainSemanticCapture withOperations(
      ChainSemanticCapture capture, List<CapturedOperation> operations) {
    return new ChainSemanticCapture(
        capture.chainIdentity(),
        capture.entryPoints(),
        capture.triggers(),
        operations,
        capture.sequenceRegions(),
        capture.conditionRegions(),
        capture.splitRegions(),
        capture.loopRegions(),
        capture.retryRegions(),
        capture.errorScopeRegions(),
        capture.edges(),
        capture.containment());
  }
}
