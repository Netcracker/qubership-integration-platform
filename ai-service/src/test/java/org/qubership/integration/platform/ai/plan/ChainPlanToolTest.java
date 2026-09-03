package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ChainPlanToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CaptureSession captureSession;
  private ChainPlanStore store;
  private ChainPlanRepairDraftStore repairDraftStore;
  private ChainPlanTool tool;
  private DeterministicElementSchemaService schemaService;

  @BeforeEach
  void setUp() {
    captureSession = new CaptureSession();
    store = new ChainPlanStore();
    repairDraftStore = new ChainPlanRepairDraftStore();
    schemaService = mock(DeterministicElementSchemaService.class);
    when(schemaService.allowedPatchPropertyKeys(any())).thenReturn(Set.of());
    tool =
        new ChainPlanTool(
            captureSession,
            store,
            repairDraftStore,
            new ChainPlanGraphValidator(schemaService),
            MAPPER,
            new CaptureAttemptFeedbackStore());
    MDC.put(ChatMdc.CONVERSATION_ID, "conversation-1");
  }

  @AfterEach
  void tearDown() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void resolvesConversationIdFromMdc() {
    MDC.put(ChatMdc.CONVERSATION_ID, "038bb5b5-705f-4d88-8ed6-e12e0c4bca50");

    assertEquals("038bb5b5-705f-4d88-8ed6-e12e0c4bca50", ChainPlanTool.resolveConversationId());
  }

  @Test
  void rejectsNullGraphWithoutThrowing() {
    String result = tool.captureChainPlan(null);

    assertTrue(result.contains("graph is required"));
    assertTrue(store.get("conversation-1").isEmpty());
  }

  @Test
  void rejectsMissingSessionWithoutThrowing() {
    MDC.remove(ChatMdc.CONVERSATION_ID);

    String result = tool.captureChainPlan(toCapture(validPlanGraph()));

    assertTrue(result.contains("conversationId is required"));
    assertTrue(store.get("conversation-1").isEmpty());
  }

  @Test
  void storesValidPlanUnderMdcConversationId() {
    String result = tool.captureChainPlan(toCapture(validPlanGraph()));

    assertTrue(result.contains("Plan captured"));
    assertTrue(store.get("conversation-1").isPresent());
    assertFalse(store.get("conversation-1").get().nodes().isEmpty());
  }

  @Test
  void storesFortuneRoutingPlanWithEmptyNodeProperties() {
    ChainPlanCapture capture = toCapture(fortuneRoutingPlan());

    String result = tool.captureChainPlan(capture);

    assertTrue(result.contains("Plan captured"));
    ChainPlanGraph stored = store.get("conversation-1").orElseThrow();
    assertEquals(7, stored.nodes().size());
    assertTrue(
        stored.nodes().stream()
            .allMatch(node -> node.properties() == null || node.properties().isEmpty()));
  }

  @Test
  void rejectsInvalidGraphStructure() {
    ChainPlanCapture invalid =
        new ChainPlanCapture(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(),
            List.of());

    String result = tool.captureChainPlan(invalid);

    assertTrue(result.contains("Plan validation failed"));
    assertTrue(store.get("conversation-1").isEmpty());
    assertTrue(repairDraftStore.get("conversation-1").isPresent());
  }

  @Test
  void interruptsRepeatedValidationFailure() {
    ChainPlanCapture invalid =
        new ChainPlanCapture(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(),
            List.of());

    tool.captureChainPlan(invalid);

    assertThrows(CaptureValidationException.class, () -> tool.captureChainPlan(invalid));
    assertTrue(store.get("conversation-1").isEmpty());
    assertTrue(repairDraftStore.get("conversation-1").isPresent());
  }

  @Test
  void rejectsTriggerNestedUnderContainer() {
    ChainPlanCapture graph =
        new ChainPlanCapture(
            "1.0",
            new ChainSection("greetings", "Greetings chain"),
            List.of(
                new ChainPlanNodeCapture(
                    "tcff", "try-catch-finally-2", "Try/Catch", null, null),
                new ChainPlanNodeCapture(
                    "n1", "http-trigger", "HTTP Trigger", "tcff", null)),
            List.of());

    String result = tool.captureChainPlan(graph);

    assertTrue(result.contains("Plan validation failed"));
    assertTrue(result.contains("parentNodeId"));
    assertTrue(store.get("conversation-1").isEmpty());
  }

  @Test
  void capturesServiceCallSkeletonWithoutOperationBinding() {
    ChainPlanCapture capture =
        new ChainPlanCapture(
            "1.0",
            new ChainSection("pet-lookup", "Pet lookup"),
            List.of(
                new ChainPlanNodeCapture(
                    "trigger", "http-trigger", "HTTP Trigger", null, null),
                new ChainPlanNodeCapture(
                    "call-pets", "service-call", "Call pets API", null, null)),
            List.of(new ChainPlanEdge("e1", "trigger", "call-pets", null)));

    String result = tool.captureChainPlan(capture);

    assertTrue(result.contains("Plan captured"));
    ChainPlanNode serviceCall =
        store.get("conversation-1").orElseThrow().nodes().stream()
            .filter(node -> "service-call".equals(node.type()))
            .findFirst()
            .orElseThrow();
    assertTrue(
        serviceCall.properties() == null
            || serviceCall.properties().stream()
                .noneMatch(p -> "integrationOperationId".equals(p.key())));
  }

  @Test
  void duplicateValidPlanPreservesFirstCaptureAndWritesDurableOnce() {
    ChainPlanStore durableStore = mock(ChainPlanStore.class);
    ChainPlanTool toolWithDurableStore = newTool(durableStore);
    CaptureKey key = CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, "conversation-1");

    String firstResult = toolWithDurableStore.captureChainPlan(toCapture(validPlanGraph()));
    ChainPlanGraph firstValue =
        captureSession.get(key, ChainPlanGraph.class).orElseThrow();

    CaptureValidationException duplicate =
        assertThrows(
            CaptureValidationException.class,
            () -> toolWithDurableStore.captureChainPlan(toCapture(fortuneRoutingPlan())));

    assertTrue(firstResult.contains("finish this turn"));
    assertTrue(duplicate.getMessage().contains("already captured"));
    assertTrue(duplicate.getMessage().contains("finish this turn"));
    assertSame(firstValue, captureSession.get(key, ChainPlanGraph.class).orElseThrow());
    verify(durableStore, times(1)).put(eq("conversation-1"), any(ChainPlanGraph.class));
  }

  @Test
  void durableWriteFailureClearsAcceptedValueAndAllowsRetry() {
    ChainPlanStore durableStore = mock(ChainPlanStore.class);
    doThrow(new IllegalStateException("durable write failed"))
        .doNothing()
        .when(durableStore)
        .put(eq("conversation-1"), any(ChainPlanGraph.class));
    ChainPlanTool toolWithDurableStore = newTool(durableStore);
    CaptureKey key = CaptureKey.conversation(CaptureSlot.CHAIN_PLAN, "conversation-1");
    ChainPlanCapture capture = toCapture(validPlanGraph());

    String failed = toolWithDurableStore.captureChainPlan(capture);

    assertTrue(failed.contains("Error capturing plan: durable write failed"));
    assertFalse(captureSession.isPresent(key));

    String retried = toolWithDurableStore.captureChainPlan(capture);

    assertTrue(retried.contains("Plan captured"));
    assertTrue(captureSession.isPresent(key));
    verify(durableStore, times(2)).put(eq("conversation-1"), any(ChainPlanGraph.class));
  }

  private ChainPlanTool newTool(ChainPlanStore targetStore) {
    return new ChainPlanTool(
        captureSession,
        targetStore,
        repairDraftStore,
        new ChainPlanGraphValidator(schemaService),
        MAPPER,
        new CaptureAttemptFeedbackStore());
  }

  private static ChainPlanGraph validPlanGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("greetings", "Greetings chain"),
        List.of(
            new ChainPlanNode("n1", "http-trigger", "HTTP Trigger", null, null, List.of()),
            new ChainPlanNode("n2", "script", "Script", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
  }

  private static ChainPlanGraph fortuneRoutingPlan() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Fortune API", "Fortune API with language routing"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, null, List.of()),
            new ChainPlanNode("parse-lang", "script", "Parse lang", null, null, List.of()),
            new ChainPlanNode("route", "condition", "Route by language", null, null, List.of()),
            new ChainPlanNode(
                "if-fr",
                "if",
                "French branch",
                "route",
                null,
                List.of()),
            new ChainPlanNode("else-en", "else", "Default branch", "route", null, List.of()),
            new ChainPlanNode("fr-response", "script", "FR response", "if-fr", null, List.of()),
            new ChainPlanNode("en-response", "script", "EN response", "else-en", null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger", "parse-lang", null),
            new ChainPlanEdge("e2", "parse-lang", "route", null)));
  }

  private static ChainPlanCapture toCapture(ChainPlanGraph graph) {
    List<ChainPlanNodeCapture> nodes =
        graph.nodes() == null
            ? null
            : graph.nodes().stream().map(ChainPlanToolTest::toCaptureNode).toList();
    return new ChainPlanCapture(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
  }

  private static ChainPlanNodeCapture toCaptureNode(ChainPlanNode node) {
    return new ChainPlanNodeCapture(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order());
  }
}
