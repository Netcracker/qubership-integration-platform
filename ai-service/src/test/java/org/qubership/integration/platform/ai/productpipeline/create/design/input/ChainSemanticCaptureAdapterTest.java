package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
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
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegionKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRouteKind;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

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
  void treatsBlankOptionalFinallyEntryAsAbsent() {
    ChainSemanticCapture linear = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture capture = new ChainSemanticCapture(
        linear.chainIdentity(),
        linear.operations(),
        linear.sequenceRegions(),
        linear.conditionRegions(),
        linear.splitRegions(),
        linear.loopRegions(),
        linear.retryRegions(),
        List.of(new ChainSemanticCapture.CapturedErrorScopeRegion(
            "error-1", ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
            ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID, List.of(), "  ", List.of())),
        linear.edges(),
        linear.containment());

    SemanticRegion.ErrorScope region = (SemanticRegion.ErrorScope) adapt(capture).regions().stream()
        .filter(SemanticRegion.ErrorScope.class::isInstance)
        .findFirst().orElseThrow();
    assertNull(region.finallyEntryNodeId());
  }

  @Test
  void copiesFailureModeFromTheApprovedOccurrence() {
    RequirementBrief approved = ChainSemanticCaptureFixtures.approvedBrief();
    RequirementServiceCall call = approved.serviceCalls().getFirst();
    RequirementBrief inlineFailure =
        approved.withServiceCalls(
            List.of(
                new RequirementServiceCall(
                    call.serviceCallId(),
                    call.sourceFactId(),
                    call.participant(),
                    call.operation(),
                    call.catalogBinding(),
                    ServiceCallFailureMode.INLINE_RESPONSE)));

    ChainSemanticRevision revision = adapt(ChainSemanticCaptureFixtures.linearCapture(), inlineFailure);

    assertEquals(
        ServiceCallFailureMode.INLINE_RESPONSE,
        node(revision, SemanticNode.ServiceCall.class).failureMode());
  }

  @Test
  void rejectsAResponseBehaviorThatHasNoSemanticOperation() {
    ChainSemanticCapture capture =
        new ChainSemanticCapture(
            "chain-orders",
            List.of(
                new CapturedEntryPoint(
                    "http-in",
                    "trigger-http",
                    ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                    0,
                    List.of("trigger-1"),
                    "Create order",
                    null)),
            List.of(new CapturedTrigger("trigger-http", List.of("trigger-1"))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new CapturedEdge(
                    "http-in",
                    ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)),
            List.of());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> adapt(capture, ChainSemanticCaptureFixtures.approvedBrief()));

    assertTrue(error.getMessage().contains("fact-script"), error.getMessage());
    assertTrue(error.getMessage().contains("sourceFactId"), error.getMessage());
  }

  @Test
  void projectsOneOperationWhenOutboundInteractionHasMultipleFacts() {
    String outboundId = "relay-out";
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("http-in", Direction.INBOUND, "Caller", "POST /notify", ""),
                new Interaction(outboundId, Direction.OUTBOUND, "Target", "send", "")),
            List.of(new Transition("http-in", outboundId)));
    List<RequirementFact> facts =
        List.of(
            new RequirementFact(
                "trigger-1",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-trigger",
                "Expose POST /notify",
                "",
                "",
                "POST",
                "/notify",
                ""),
            new RequirementFact(
                outboundId,
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "kafka-sender-2",
                "Send with kafka-sender-2"),
            new RequirementFact(
                outboundId,
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CONSTRAINT,
                "",
                "Use topic orders-out"));
    RequirementBrief brief =
        new RequirementBrief(
            "Relay",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Relay",
            "draft-1",
            "draft",
            facts,
            List.of(
                new RequirementEntryPoint(
                    "http-in", "trigger-1", "http-trigger", "", "POST", "/notify", "POST /notify")),
            List.of(),
            List.of(),
            List.of(),
            flow,
            List.of());

    ChainSemanticRevision revision = adapt(nativeSenderCapture(outboundId), brief);

    long senderCount =
        revision.nodes().stream()
            .filter(SemanticNode.Operation.class::isInstance)
            .map(SemanticNode.Operation.class::cast)
            .filter(operation -> outboundId.equals(operation.nodeId()))
            .count();
    assertEquals(1, senderCount);
    assertEquals("kafka-sender-2", senderOperation(revision, outboundId).elementType());
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
  }

  @Test
  void projectsNativeSenderFromCapabilityFactWithoutCapturedOperation() {
    RequirementBrief brief = nativeSenderBrief("kafka-sender-2", "relay-out", List.of());
    ChainSemanticCapture capture = nativeSenderCapture("relay-out");

    ChainSemanticRevision revision = adapt(capture, brief);

    SemanticNode.Operation sender = senderOperation(revision, "relay-out");
    assertEquals("kafka-sender-2", sender.elementType());
    assertTrue(revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance));
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "graphql-sender",
        "http-sender",
        "jms-sender",
        "kafka-sender-2",
        "mail-sender",
        "pubsub-sender",
        "rabbitmq-sender-2",
        "scs-sender"
      })
  void projectsDirectSenderCapabilityAsOperationWithoutCapturedOperation(String senderType) {
    RequirementBrief brief = nativeSenderBrief(senderType, "relay-out", List.of());
    ChainSemanticRevision revision = adapt(nativeSenderCapture("relay-out"), brief);

    assertEquals(senderType, senderOperation(revision, "relay-out").elementType());
    assertTrue(revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance));
    if (CONTRACT.elements().containsKey(senderType)) {
      new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
    }
  }

  @Test
  void projectsChainCallFromCapabilityFactWithoutCatalogBinding() {
    RequirementBrief brief =
        nativeSenderBrief(
            "chain-call-2",
            "call-other",
            List.of(
                new RequirementServiceCall(
                    "call-other",
                    "call-other",
                    "Chain trigger + Header modification",
                    "chain-trigger")));
    ChainSemanticRevision revision = adapt(nativeSenderCapture("call-other"), brief);

    assertEquals("chain-call-2", senderOperation(revision, "call-other").elementType());
    assertTrue(revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance));
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
  }

  @Test
  void rejectsExtraChainCallOperationWithNewNodeId() {
    RequirementBrief brief =
        nativeSenderBrief(
            "chain-call-2",
            "call-other",
            List.of());
    ChainSemanticCapture capture =
        withAddedOperations(
            nativeSenderCapture("call-other"),
            List.of(
                new CapturedOperation(
                    "op-chain-call", "chain-call-2", List.of("call-other"))));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> adapt(capture, brief));

    assertTrue(failure.getMessage().contains("op-chain-call"), failure.getMessage());
    assertTrue(failure.getMessage().contains("chain-call-2"), failure.getMessage());
    assertTrue(failure.getMessage().contains("call-other"), failure.getMessage());
  }

  @Test
  void mergesExtraSourceFactIdsOntoProjectedChainCall() {
    RequirementBrief base =
        nativeSenderBrief("chain-call-2", "call-other", List.of());
    RequirementBrief brief =
        base.withFacts(
            List.of(
                base.facts().get(0),
                base.facts().get(1),
                new RequirementFact(
                    "call-failure-behavior",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.BEHAVIOR,
                    "",
                    "The chain-call is blocking and failures propagate")));
    ChainSemanticCapture capture =
        withAddedOperations(
            nativeSenderCapture("call-other"),
            List.of(
                new CapturedOperation(
                    "call-other",
                    "chain-call-2",
                    List.of("call-other", "call-failure-behavior"))));

    ChainSemanticRevision revision = adapt(capture, brief);

    long chainCallCount =
        revision.nodes().stream()
            .filter(SemanticNode.Operation.class::isInstance)
            .map(SemanticNode.Operation.class::cast)
            .filter(operation -> "chain-call-2".equals(operation.elementType()))
            .count();
    assertEquals(1, chainCallCount);
    assertEquals(
        List.of("call-other", "call-failure-behavior"),
        senderOperation(revision, "call-other").provenance().sourceFactIds());
  }

  @Test
  void rejectsExtraChainCallWhenTwoCallsAlreadyProjected() {
    RequirementBrief brief = twoChainCallBrief();
    ChainSemanticCapture capture =
        withAddedOperations(
            twoChainCallCapture(),
            List.of(
                new CapturedOperation(
                    "op-chain-call", "chain-call-2", List.of("call-a"))));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> adapt(capture, brief));

    assertTrue(failure.getMessage().contains("op-chain-call"), failure.getMessage());
    assertFalse(failure.getMessage().contains("merge"), failure.getMessage());
  }

  @Test
  void skipsServiceCallMaterializationForHttpSenderCapability() {
    RequirementBrief brief =
        nativeSenderBrief(
            "http-sender",
            "relay-out",
            List.of(new RequirementServiceCall("relay-out", "relay-out", "Relay", "POST")));
    ChainSemanticRevision revision = adapt(nativeSenderCapture("relay-out"), brief);

    assertEquals("http-sender", senderOperation(revision, "relay-out").elementType());
    assertTrue(revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance));
  }

  @Test
  void implementedServiceHttpTriggerStaysTriggerWithCatalogBinding() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint geoHint =
        new CatalogBindingHint(
            CatalogBindingHint.SCHEMA_VERSION,
            "geo-api",
            "geo-api",
            "retrieveGeographicSite",
            "sys-geo",
            "sg-geo",
            "spec-geo",
            "op-geo",
            "http",
            "GET",
            "/geo",
            "v1",
            observedAt,
            "catalog-read:sys-geo/spec-geo/op-geo");
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction(
                    "geo-api", Direction.INBOUND, "GeoSite", "retrieveGeographicSite", "")),
            List.of());
    List<RequirementFact> facts =
        List.of(
            new RequirementFact(
                "geo-api",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-trigger",
                "Expose GeoSite implemented service",
                "GeoSite",
                "",
                "",
                "GET",
                ""),
            new RequirementFact(
                "fact-script",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.BEHAVIOR,
                "",
                "Prepare the response"));
    RequirementBrief brief =
        new RequirementBrief(
            "GeoSite",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Expose GeoSite",
            "draft-1",
            "draft",
            facts,
            List.of(
                new RequirementEntryPoint(
                    "geo-api",
                    "geo-api",
                    "http-trigger",
                    "",
                    "GET",
                    "",
                    "retrieveGeographicSite")),
            List.of(),
            List.of(),
            List.of(),
            flow,
            List.of(geoHint));
    ChainSemanticCapture capture =
        new ChainSemanticCapture(
            "chain-geo",
            List.of(
                new CapturedEntryPoint(
                    "geo-api", "trigger-http", "op-script", 0, List.of("geo-api"), null, null)),
            List.of(new CapturedTrigger("trigger-http", List.of("geo-api"))),
            List.of(new CapturedOperation("op-script", "script", List.of("fact-script"))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new CapturedEdge("geo-api", "op-script", null, null, null, null, null, null)),
            List.of());

    ChainSemanticRevision revision = adapt(capture, brief);

    SemanticNode.Trigger trigger = node(revision, SemanticNode.Trigger.class);
    assertEquals("http-trigger", trigger.capabilityKey());
    assertEquals("geo-api", trigger.nodeId());
    assertEquals("op-geo", brief.catalogBindings().getFirst().integrationOperationId());
    assertTrue(revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance));
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
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
                new CapturedEdge("async-in", "op-shared", null, null, null, null, null, null)),
            List.of());

    ChainSemanticRevision revision = adapt(capture, brief);

    assertEquals(
        0, revision.nodes().stream().filter(SemanticNode.ServiceCall.class::isInstance).count());
    SemanticNode.Trigger trigger = node(revision, SemanticNode.Trigger.class);
    assertEquals("async-api-trigger", trigger.capabilityKey());
    assertEquals("async-in", trigger.nodeId());
    assertEquals("async-in", trigger.interactionId());
  }

  @Test
  void coercesMapper2OperationsToScriptWhileMapper2IsDisabled() {
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
            List.of(new CapturedOperation("op-shared", "mapper-2", List.of())),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new CapturedEdge("async-in", "op-shared", null, null, null, null, null, null)),
            List.of());

    ChainSemanticRevision revision = adapt(capture, brief);

    SemanticNode.Operation operation = node(revision, SemanticNode.Operation.class);
    assertEquals("script", operation.elementType());
  }

  @Test
  void acceptsTheBriefHopLabelAsMappingIntentId() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.briefWithMapping();
    MappingIntent intent = brief.mappingIntents().getFirst();

    ChainSemanticRevision revision =
        adapt(ChainSemanticCaptureFixtures.mappedCapture(intent.hopLabel()), brief);

    assertEquals(
        ChainSemanticCaptureFixtures.MAPPING_INTENT_ID,
        revision.executionEdges().stream()
            .map(SemanticExecutionEdge::mappingId)
            .filter(ChainSemanticCaptureFixtures.MAPPING_INTENT_ID::equals)
            .findFirst()
            .orElse(null));
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
  }

  @Test
  void acceptsHopLabelAfterProjectorMintsABlankMappingIntentId() {
    RequirementBrief raw =
        ChainSemanticCaptureFixtures.briefWithMapping()
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "",
                        "trigger-1",
                        MappingPort.OUTPUT,
                        "fact-call",
                        MappingPort.REQUEST,
                        List.of(new MappingIntentRule("id", "orderId", null)))));
    RequirementBrief brief = RequirementBriefProjector.canonicalizeMappingIntents(raw);
    MappingIntent intent = brief.mappingIntents().getFirst();
    assertNotEquals("", intent.mappingIntentId());

    ChainSemanticRevision revision =
        adapt(ChainSemanticCaptureFixtures.mappedCapture(intent.hopLabel()), brief);

    assertEquals(
        intent.mappingIntentId(),
        revision.executionEdges().stream()
            .map(SemanticExecutionEdge::mappingId)
            .filter(intent.mappingIntentId()::equals)
            .findFirst()
            .orElse(null));
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
  }

  @Test
  void rewritesMappingRefsFromFactIdsOntoTheCarryingEdge() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.briefWithMapping();
    ChainSemanticRevision revision =
        adapt(ChainSemanticCaptureFixtures.mappedCapture(), brief);

    SemanticExecutionEdge site =
        revision.executionEdges().stream()
            .filter(edge -> ChainSemanticCaptureFixtures.MAPPING_INTENT_ID.equals(edge.mappingId()))
            .findFirst()
            .orElseThrow();
    assertTrue(revision.mappingIntents().isEmpty());
    assertEquals(ChainSemanticCaptureFixtures.MAPPING_INTENT_ID, site.mappingId());
    assertEquals("trigger-1", brief.mappingIntents().getFirst().sourceRef());
    assertEquals("fact-call", brief.mappingIntents().getFirst().targetRef());
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, brief);
  }

  @Test
  void foldedPlaceholderDoesNotNeedItsOwnEdge() {
    MappingIntent approved = ChainSemanticCaptureFixtures.briefWithMapping().mappingIntents().getFirst();
    MappingIntent placeholder =
        new MappingIntent(
            "process-instance-to-process-id",
            "edge-495d48ab0cc3cf30",
            MappingPort.OUTPUT,
            "edge-495d48ab0cc3cf30",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("processInstanceId", "orderId", "alias")));
    RequirementBrief raw =
        ChainSemanticCaptureFixtures.briefWithMapping()
            .withMappingIntents(List.of(approved, placeholder));

    String missingSite =
        assertThrows(
                IllegalArgumentException.class,
                () -> adapt(ChainSemanticCaptureFixtures.mappedCapture(), raw))
            .getMessage();
    assertTrue(missingSite.contains("process-instance-to-process-id"), missingSite);

    RequirementBrief canonical = RequirementBriefProjector.canonicalizeMappingIntents(raw);
    ChainSemanticRevision revision =
        adapt(ChainSemanticCaptureFixtures.mappedCapture(), canonical);

    assertTrue(revision.mappingIntents().isEmpty());
    assertEquals(
        ChainSemanticCaptureFixtures.MAPPING_INTENT_ID,
        revision.executionEdges().stream()
            .map(SemanticExecutionEdge::mappingId)
            .filter(ChainSemanticCaptureFixtures.MAPPING_INTENT_ID::equals)
            .findFirst()
            .orElse(null));
    assertEquals(1, canonical.mappingIntents().size());
    assertEquals(2, canonical.mappingIntents().getFirst().rules().size());
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT, canonical);
  }

  @Test
  void mappingRuleChangeMovesRevisionIdWithoutStoringBodies() {
    RequirementBrief first = ChainSemanticCaptureFixtures.briefWithMapping();
    MappingIntent original = first.mappingIntents().getFirst();
    RequirementBrief second =
        first.withMappingIntents(
            List.of(
                new MappingIntent(
                    original.mappingIntentId(),
                    original.sourceRef(),
                    original.sourcePort(),
                    original.targetRef(),
                    original.targetPort(),
                    List.of(new MappingIntentRule("id", "customerId", null)))));

    ChainSemanticRevision before =
        adapt(ChainSemanticCaptureFixtures.mappedCapture(), first);
    ChainSemanticRevision after =
        adapt(ChainSemanticCaptureFixtures.mappedCapture(), second);

    assertTrue(before.mappingIntents().isEmpty());
    assertTrue(after.mappingIntents().isEmpty());
    assertNotEquals(before.revisionId(), after.revisionId());
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
    assertEquals("http-in", revision.entryPoints().getFirst().triggerNodeId());
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
  void capturedTriggerProvenanceDoesNotOverrideTheApprovedBrief() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture foreign =
        withTriggers(
            capture, List.of(new CapturedTrigger("trigger-http", List.of("foreign-fact"))));
    ChainSemanticRevision revision = adapt(foreign);
    assertEquals("http-in", node(revision, SemanticNode.Trigger.class).nodeId());
  }

  @Test
  void rejectsAnOperationThatRestatesAServerOwnedServiceCallNode() {
    ChainSemanticCapture capture =
        withOperations(
            ChainSemanticCaptureFixtures.linearCapture(),
            List.of(
                new CapturedOperation("op-shared", "script", List.of()),
                new CapturedOperation(
                    ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID, "service-call", List.of())));

    assertTrue(failure(capture).contains("reuses an interaction id"), failure(capture));
  }

  @Test
  void rejectsAnOperationThatRestatesATriggerNode() {
    ChainSemanticCapture capture =
        withOperations(
            ChainSemanticCaptureFixtures.linearCapture(),
            List.of(
                new CapturedOperation("http-in", "script", List.of()),
                new CapturedOperation("op-shared", "script", List.of())));

    assertTrue(failure(capture).contains("reuses an interaction id"), failure(capture));
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
  void projectsNativeKafkaSenderWithoutCatalogBinding() {
    RequirementBrief base = ChainSemanticCaptureFixtures.approvedBrief();
    RequirementFact triggerFact = base.facts().getFirst();
    RequirementFact kafkaFact =
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-sender-2",
            "Publish the request body to Kafka");
    RequirementBrief nativeKafkaBrief =
        base.withFacts(List.of(triggerFact, kafkaFact))
            .withServiceCalls(
                List.of(new RequirementServiceCall("call-1", "call-1", "Kafka", "send")));
    ChainSemanticCapture nativeKafkaCapture =
        new ChainSemanticCapture(
            "chain-kafka",
            List.of(
                new CapturedEntryPoint(
                    "http-in",
                    "trigger-http",
                    "call-1",
                    0,
                    List.of("trigger-1"),
                    "Publish event",
                    null)),
            List.of(new CapturedTrigger("trigger-http", List.of("trigger-1"))),
            List.of(new CapturedOperation("call-1", "kafka-sender-2", List.of("call-1"))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new CapturedEdge("http-in", "call-1", null, null, null, null, null, null)),
            List.of());

    ChainSemanticRevision revision = adapt(nativeKafkaCapture, nativeKafkaBrief);

    assertTrue(
        revision.nodes().stream()
            .anyMatch(
                node ->
                    node instanceof SemanticNode.Operation operation
                        && "call-1".equals(operation.nodeId())
                        && "kafka-sender-2".equals(operation.elementType())));
    assertTrue(revision.nodes().stream().noneMatch(SemanticNode.ServiceCall.class::isInstance));
  }

  @Test
  void rejectsNativeKafkaFactWithoutMatchingOperationNode() {
    RequirementBrief base = ChainSemanticCaptureFixtures.approvedBrief();
    RequirementFact triggerFact = base.facts().getFirst();
    RequirementFact kafkaFact =
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-sender-2",
            "Publish the request body to Kafka");
    RequirementBrief nativeKafkaBrief =
        base.withFacts(List.of(triggerFact, kafkaFact))
            .withServiceCalls(
                List.of(new RequirementServiceCall("call-1", "call-1", "Kafka", "send")));
    ChainSemanticCapture wrongOperationCapture =
        new ChainSemanticCapture(
            "chain-kafka",
            List.of(
                new CapturedEntryPoint(
                    "http-in",
                    "trigger-http",
                    "call-1",
                    0,
                    List.of("trigger-1"),
                    "Publish event",
                    null)),
            List.of(new CapturedTrigger("trigger-http", List.of("trigger-1"))),
            List.of(new CapturedOperation("call-1", "header-modification", List.of("call-1"))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new CapturedEdge("http-in", "call-1", null, null, null, null, null, null)),
            List.of());

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> adapt(wrongOperationCapture, nativeKafkaBrief));

    assertTrue(
        failure.getMessage().contains("reuses an interaction id")
            || failure.getMessage().contains("requires operation nodeId 'call-1'"),
        failure.getMessage());
  }

  @Test
  void acceptsCheckpointAsAnInternalOperation() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture withCheckpoint =
        new ChainSemanticCapture(
            capture.chainIdentity(),
            List.of(),
            List.of(),
            List.of(
                new CapturedOperation("op-checkpoint", "checkpoint", List.of()),
                new CapturedOperation("op-shared", "script", List.of("fact-script"))),
            capture.sequenceRegions(),
            capture.conditionRegions(),
            capture.splitRegions(),
            capture.loopRegions(),
            capture.retryRegions(),
            capture.errorScopeRegions(),
            List.of(
                new CapturedEdge("http-in", "op-checkpoint", null, null, null, null, null, null),
                new CapturedEdge(
                    "op-checkpoint", "op-shared", null, null, null, null, null, null),
                new CapturedEdge(
                    "op-shared",
                    ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)),
            capture.containment());

    ChainSemanticRevision revision = adapt(withCheckpoint);

    assertTrue(
        revision.nodes().stream()
            .anyMatch(
                node ->
                    node instanceof SemanticNode.Operation operation
                        && "checkpoint".equals(operation.elementType())));
    new DefaultChainSemanticRevisionValidator()
        .validate(revision, CONTRACT, ChainSemanticCaptureFixtures.approvedBrief());
  }

  @Test
  void rejectsAnElementTypeTheCompilerContractDoesNotDeclare() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture unknown =
        withOperations(
            capture, List.of(new CapturedOperation("op-shared", "quantum-mapper", List.of())));
    String rejection = failure(unknown);
    assertTrue(rejection.contains("quantum-mapper"));
    assertTrue(rejection.contains("Allowed elementType values:"), rejection);
    assertTrue(rejection.contains("try-catch-finally-2"), rejection);
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
  void placesApprovedMappingOnTheUniqueTransformEdgeEnteringItsTarget() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.mappedCapture();
    List<CapturedEdge> withoutMappingId = capture.edges().stream()
        .map(edge -> new CapturedEdge(
            edge.sourceNodeId(), edge.targetNodeId(), edge.regionId(), edge.routeKind(),
            edge.branchId(), edge.branchIds(), edge.handlerId(), null))
        .toList();

    ChainSemanticRevision revision = adapt(
        withEdges(capture, withoutMappingId), ChainSemanticCaptureFixtures.briefWithMapping());

    assertEquals(ChainSemanticCaptureFixtures.MAPPING_INTENT_ID,
        revision.executionEdges().stream()
            .filter(edge -> ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID.equals(
                edge.targetNodeId()))
            .findFirst().orElseThrow().mappingId());
  }

  @Test
  void doesNotGuessBetweenTwoTransformEdgesEnteringOneTarget() {
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.mappedCapture();
    ChainSemanticCapture ambiguous = new ChainSemanticCapture(
        capture.chainIdentity(),
        List.of(
            new CapturedOperation("op-left", "script", List.of("fact-script")),
            new CapturedOperation("op-right", "script", List.of("fact-script"))),
        capture.sequenceRegions(), capture.conditionRegions(), capture.splitRegions(),
        capture.loopRegions(), capture.retryRegions(), capture.errorScopeRegions(),
        List.of(
            new CapturedEdge("http-in", "op-left", null, null, null, null, null, null),
            new CapturedEdge("op-left", "op-right", null, null, null, null, null, null),
            new CapturedEdge("op-left", ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                null, null, null, null, null, null),
            new CapturedEdge("op-right", ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                null, null, null, null, null, null)),
        capture.containment());

    String message = failure(ambiguous, ChainSemanticCaptureFixtures.briefWithMapping());
    assertTrue(message.contains("None of the captured edges listed that id"), message);
  }

  @Test
  void rejectsAnApprovedMappingThatNoEdgeCarries() {
    String message =
        failure(
            withEdges(
                withOperations(ChainSemanticCaptureFixtures.linearCapture(), List.of()),
                List.of(new CapturedEdge(
                    "http-in", ChainSemanticCaptureFixtures.SERVICE_CALL_NODE_ID,
                    null, null, null, null, null, null))),
            ChainSemanticCaptureFixtures.briefWithMapping());
    assertEquals(
        "The approved brief already has mappingIntentId='"
            + ChainSemanticCaptureFixtures.MAPPING_INTENT_ID
            + "'. None of the captured edges listed that id. Set mappingIntentId on the edge that"
            + " carries this mapping, not on the brief.",
        message);
  }

  @Test
  void rockyInboundCreatesATriggerWithoutACapturedTrigger() {
    ChainSemanticRevision revision =
        adapt(ChainSemanticCaptureFixtures.rockyCapture(), ChainSemanticCaptureFixtures.rockyBrief());
    SemanticNode.Trigger trigger = node(revision, SemanticNode.Trigger.class);
    assertEquals("task-start", trigger.nodeId());
    assertEquals("task-start", trigger.interactionId());
    assertEquals("async-api-trigger", trigger.capabilityKey());
    assertEquals(
        Set.of("create-task", "task-result"),
        revision.nodes().stream()
            .filter(SemanticNode.ServiceCall.class::isInstance)
            .map(SemanticNode.ServiceCall.class::cast)
            .map(SemanticNode.ServiceCall::nodeId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    assertTrue(
        revision.nodes().stream()
            .noneMatch(node -> "generic-barrier".equals(node.nodeId())));
    assertEquals("mapper-1", node(revision, SemanticNode.Operation.class).nodeId());
    assertEquals(
        Set.of("task-start -> create-task", "create-task -> task-result"),
        contracted(revision, Set.of("task-start", "create-task", "task-result")));
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT);
  }

  @Test
  void rockyCaptureRejectsReversedBusinessTransitions() {
    ChainSemanticCapture reversed =
        ChainSemanticCaptureFixtures.rockyCapture(
            List.of(),
            List.of(
                new CapturedEdge("task-start", "task-result", null, null, null, null, null, null),
                new CapturedEdge("task-result", "create-task", null, null, null, null, null, null)));
    assertTrue(
        failure(reversed, ChainSemanticCaptureFixtures.rockyBrief())
            .contains("do not preserve approved business transitions"));
  }

  @Test
  void rockyCaptureRejectsReplacingAnOutboundAnchorWithAGenericBarrier() {
    ChainSemanticCapture replaced =
        ChainSemanticCaptureFixtures.rockyCapture(
            List.of(new CapturedOperation("generic-barrier", "script", List.of())),
            List.of(
                new CapturedEdge("task-start", "create-task", null, null, null, null, null, null),
                new CapturedEdge(
                    "create-task", "generic-barrier", null, null, null, null, null, null)));
    assertTrue(
        failure(replaced, ChainSemanticCaptureFixtures.rockyBrief())
            .contains("do not preserve approved business transitions"));
  }

  @Test
  void rockyCaptureRejectsOmittingAnApprovedTransition() {
    ChainSemanticCapture omitted =
        ChainSemanticCaptureFixtures.rockyCapture(
            List.of(),
            List.of(
                new CapturedEdge("task-start", "create-task", null, null, null, null, null, null)));
    assertTrue(
        failure(omitted, ChainSemanticCaptureFixtures.rockyBrief())
            .contains("do not preserve approved business transitions"));
  }

  @Test
  void rockyCaptureRejectsAnUnapprovedExternalTransition() {
    ChainSemanticCapture extra =
        ChainSemanticCaptureFixtures.rockyCapture(
            List.of(new CapturedOperation("join-1", "script", List.of())),
            List.of(
                new CapturedEdge("task-start", "join-1", null, null, null, null, null, null),
                new CapturedEdge("join-1", "create-task", null, null, null, null, null, null),
                new CapturedEdge("join-1", "task-result", null, null, null, null, null, null),
                new CapturedEdge("create-task", "task-result", null, null, null, null, null, null)));
    assertTrue(
        failure(extra, ChainSemanticCaptureFixtures.rockyBrief())
            .contains("do not preserve approved business transitions"));
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

  private static Set<String> contracted(
      ChainSemanticRevision revision, Set<String> interactionIds) {
    Map<String, List<String>> outgoing = new LinkedHashMap<>();
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      outgoing
          .computeIfAbsent(edge.sourceNodeId(), unused -> new ArrayList<>())
          .add(edge.targetNodeId());
    }
    Set<String> contracted = new LinkedHashSet<>();
    for (String source : interactionIds) {
      ArrayDeque<String> pending = new ArrayDeque<>();
      Set<String> seen = new LinkedHashSet<>();
      pending.add(source);
      seen.add(source);
      while (!pending.isEmpty()) {
        String current = pending.removeFirst();
        for (String next : outgoing.getOrDefault(current, List.of())) {
          if (interactionIds.contains(next) && !next.equals(source)) {
            contracted.add(source + " -> " + next);
            continue;
          }
          if (seen.add(next)) {
            pending.add(next);
          }
        }
      }
    }
    return contracted;
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
            new CapturedOperation("op-else", "script", List.of("fact-script"))),
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
            new CapturedEdge("http-in", "op-condition", null, null, null, null, null, null),
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
        List.of(),
        List.of(),
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
        List.of(),
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
        List.of(),
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
        brief.entryPoints(),
        serviceCalls,
        brief.requirements(),
        brief.mappingIntents());
  }

  private static RequirementBrief nativeSenderBrief(
      String senderType, String outboundId, List<RequirementServiceCall> serviceCalls) {
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("http-in", Direction.INBOUND, "Caller", "POST /notify", ""),
                new Interaction(outboundId, Direction.OUTBOUND, "Target", "send", "")),
            List.of(new Transition("http-in", outboundId)));
    List<RequirementFact> facts =
        List.of(
            new RequirementFact(
                "trigger-1",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-trigger",
                "Expose POST /notify",
                "",
                "",
                "POST",
                "/notify",
                ""),
            new RequirementFact(
                outboundId,
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                senderType,
                "Send with " + senderType));
    return new RequirementBrief(
        "Relay",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Relay",
        "draft-1",
        "draft",
        facts,
        List.of(
            new RequirementEntryPoint(
                "http-in", "trigger-1", "http-trigger", "", "POST", "/notify", "POST /notify")),
        serviceCalls,
        List.of(),
        List.of(),
        flow,
        List.of());
  }

  private static ChainSemanticCapture nativeSenderCapture(String outboundId) {
    return new ChainSemanticCapture(
        "chain-relay",
        List.of(
            new CapturedEntryPoint(
                "http-in", "trigger-http", outboundId, 0, List.of("trigger-1"), null, null)),
        List.of(new CapturedTrigger("trigger-http", List.of("trigger-1"))),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(new CapturedEdge("http-in", outboundId, null, null, null, null, null, null)),
        List.of());
  }

  private static RequirementBrief twoChainCallBrief() {
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("http-in", Direction.INBOUND, "Caller", "GET /start", ""),
                new Interaction("call-a", Direction.OUTBOUND, "Chain A", "call", ""),
                new Interaction("call-b", Direction.OUTBOUND, "Chain B", "call", "")),
            List.of(
                new Transition("http-in", "call-a"),
                new Transition("call-a", "call-b")));
    List<RequirementFact> facts =
        List.of(
            new RequirementFact(
                "trigger-1",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-trigger",
                "Expose GET /start"),
            new RequirementFact(
                "call-a",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "chain-call-2",
                "Call chain A"),
            new RequirementFact(
                "call-b",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "chain-call-2",
                "Call chain B"));
    return new RequirementBrief(
        "Two calls",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Two calls",
        "draft-1",
        "draft",
        facts,
        List.of(
            new RequirementEntryPoint(
                "http-in", "trigger-1", "http-trigger", "", "GET", "/start", "GET /start")),
        List.of(),
        List.of(),
        List.of(),
        flow,
        List.of());
  }

  private static ChainSemanticCapture twoChainCallCapture() {
    return new ChainSemanticCapture(
        "chain-two-calls",
        List.of(
            new CapturedEntryPoint(
                "http-in", "trigger-http", "call-a", 0, List.of("trigger-1"), null, null)),
        List.of(new CapturedTrigger("trigger-http", List.of("trigger-1"))),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new CapturedEdge("http-in", "call-a", null, null, null, null, null, null),
            new CapturedEdge("call-a", "call-b", null, null, null, null, null, null)),
        List.of());
  }

  private static ChainSemanticCapture withAddedOperations(
      ChainSemanticCapture capture, List<CapturedOperation> extra) {
    List<CapturedOperation> operations = new ArrayList<>(capture.operations());
    operations.addAll(extra);
    return new ChainSemanticCapture(
        capture.chainIdentity(),
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

  private static SemanticNode.Operation senderOperation(
      ChainSemanticRevision revision, String outboundId) {
    return revision.nodes().stream()
        .filter(SemanticNode.Operation.class::isInstance)
        .map(SemanticNode.Operation.class::cast)
        .filter(operation -> outboundId.equals(operation.nodeId()))
        .findFirst()
        .orElseThrow();
  }

  private static ChainSemanticCapture withOperations(
      ChainSemanticCapture capture, List<CapturedOperation> operations) {
    return new ChainSemanticCapture(
        capture.chainIdentity(),
        List.of(),
        List.of(),
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
