package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainPlanLoaderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private ActiveChainPlanService activeChainPlanService;
  private ChainPlanLoader loader;

  @BeforeEach
  void setUp() {
    MDC.clear();
    activeChainPlanService = mock(ActiveChainPlanService.class);
    loader = new ChainPlanLoader(objectMapper, activeChainPlanService);
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  private static ChainImplementationPlan planWithConnectionsAndCatalog(
      ConnectionPlan conn, String elementIdOnNode, String parentElementIdOnNode) {
    ElementPlan elem = new ElementPlan();
    elem.setClientId("n1");
    elem.setType("http-trigger");
    elem.setElementId(elementIdOnNode);
    elem.setParentElementId(parentElementIdOnNode);
    ChainImplementationPlan p = new ChainImplementationPlan();
    p.setElements(new ArrayList<>(List.of(elem)));
    if (conn != null) {
      p.setConnections(new ArrayList<>(List.of(conn)));
    }
    return p;
  }

  @Test
  void blankBatchWithoutConversationIdReturnsLegacyEmpty() {
    ChainPlanLoadResult r = loader.load("chain-1", "   ");

    assertInstanceOf(ChainPlanLoadResult.LegacyEmpty.class, r);
    verify(activeChainPlanService, never()).getActive(any());
  }

  @Test
  void emptyBatchWithConversationIdButNoSnapshotReturnsLegacyEmpty() {
    MDC.put(ChatMdc.CONVERSATION_ID, " conv-abc ");
    when(activeChainPlanService.getActive("conv-abc")).thenReturn(Optional.empty());

    ChainPlanLoadResult r = loader.load("chain-1", "{}");

    assertInstanceOf(ChainPlanLoadResult.LegacyEmpty.class, r);
    verify(activeChainPlanService).getActive("conv-abc");
  }

  @Test
  void emptyBatchWithConversationIdReturnsLoadedFromSnapshot() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-xyz");
    ElementPlan elem = new ElementPlan();
    elem.setClientId("root");
    elem.setType("http-trigger");
    ChainImplementationPlan fromSnap = new ChainImplementationPlan();
    fromSnap.setElements(List.of(elem));
    ActiveChainPlanSnapshot snap =
        new ActiveChainPlanSnapshot(
            "p1", "c1", null, null, fromSnap, Instant.now(), List.of(), List.of());
    when(activeChainPlanService.getActive("conv-xyz")).thenReturn(Optional.of(snap));

    ChainPlanLoadResult r = loader.load("chain-1", "null");

    ChainPlanLoadResult.Loaded loaded = assertInstanceOf(ChainPlanLoadResult.Loaded.class, r);
    assertEquals("active_snapshot", loaded.source());
    assertEquals("conv-xyz", loaded.conversationId());
    assertEquals("root", loaded.plan().getElements().get(0).getClientId());
    verify(activeChainPlanService, never()).updateActivePlan(any(), any());
  }

  @Test
  void invalidJsonReturnsError() {
    ChainPlanLoadResult r = loader.load("chain-1", "{not json");

    ChainPlanLoadResult.Error err = assertInstanceOf(ChainPlanLoadResult.Error.class, r);
    assertTrue(err.message().startsWith("invalid JSON:"));
  }

  @Test
  void invalidJsonApprovedConversationUsesActiveSnapshot() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-appr-invalid");
    ElementPlan snapElem = new ElementPlan();
    snapElem.setClientId("from-snap");
    snapElem.setType("http-trigger");
    ChainImplementationPlan snapPlan = new ChainImplementationPlan();
    snapPlan.setElements(List.of(snapElem));
    ActiveChainPlanSnapshot snap =
        new ActiveChainPlanSnapshot(
            "pid", "nm", null, null, snapPlan, Instant.now(), List.of(), List.of());
    when(activeChainPlanService.isApproved("conv-appr-invalid")).thenReturn(true);
    when(activeChainPlanService.getActive("conv-appr-invalid")).thenReturn(Optional.of(snap));

    ChainPlanLoadResult r = loader.load("chain-1", "{truncated json");

    ChainPlanLoadResult.Loaded loaded = assertInstanceOf(ChainPlanLoadResult.Loaded.class, r);
    assertEquals("approved_active_snapshot", loaded.source());
    assertEquals("from-snap", loaded.plan().getElements().get(0).getClientId());
    verify(activeChainPlanService, never()).updateActivePlan(any(), any());
  }

  @Test
  void approvedWithoutActiveSnapshotReturnsError() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-appr-empty");
    when(activeChainPlanService.isApproved("conv-appr-empty")).thenReturn(true);
    when(activeChainPlanService.getActive("conv-appr-empty")).thenReturn(Optional.empty());

    ChainPlanLoadResult r = loader.load("chain-1", "{}");

    ChainPlanLoadResult.Error err = assertInstanceOf(ChainPlanLoadResult.Error.class, r);
    assertEquals("no active plan snapshot for approved conversation", err.message());
  }

  @Test
  void validJsonWithoutConversationIdDoesNotTouchActiveService() throws Exception {
    String json = objectMapper.writeValueAsString(simplePlanFromBatch());

    ChainPlanLoadResult r = loader.load("chain-1", json);

    ChainPlanLoadResult.Loaded loaded = assertInstanceOf(ChainPlanLoadResult.Loaded.class, r);
    assertEquals("batch_json", loaded.source());
    assertNull(loaded.conversationId());
    assertEquals("a1", loaded.plan().getElements().get(0).getClientId());
    verify(activeChainPlanService, never()).getActive(any());
    verify(activeChainPlanService, never()).isApproved(any());
    verify(activeChainPlanService, never()).updateActivePlan(any(), any());
  }

  @Test
  void validJsonWithConversationIdApprovedIgnoresBatchUsesSnapshot() throws Exception {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-merge");

    ConnectionPlan edge = new ConnectionPlan();
    edge.setFromClientId("n1");
    edge.setToClientId("n2");
    ChainImplementationPlan snapPlan = planWithConnectionsAndCatalog(edge, "cat-99", "par-1");

    ActiveChainPlanSnapshot snap =
        new ActiveChainPlanSnapshot(
            "pid", "nm", null, null, snapPlan, Instant.now(), List.of(), List.of());
    when(activeChainPlanService.getActive("conv-merge")).thenReturn(Optional.of(snap));
    when(activeChainPlanService.isApproved("conv-merge")).thenReturn(true);

    ElementPlan batchElem = new ElementPlan();
    batchElem.setClientId("batch-only");
    batchElem.setType("script");
    ChainImplementationPlan batchPlan = new ChainImplementationPlan();
    batchPlan.setElements(new ArrayList<>(List.of(batchElem)));
    String json = objectMapper.writeValueAsString(batchPlan);

    ChainPlanLoadResult r = loader.load("chain-merge", json);

    ChainPlanLoadResult.Loaded loaded = assertInstanceOf(ChainPlanLoadResult.Loaded.class, r);
    assertEquals("approved_active_snapshot", loaded.source());
    assertEquals("conv-merge", loaded.conversationId());
    ChainImplementationPlan out = loaded.plan();
    assertEquals("n1", out.getElements().get(0).getClientId());
    assertEquals("cat-99", out.getElements().get(0).getElementId());
    assertEquals("par-1", out.getElements().get(0).getParentElementId());
    assertEquals(1, out.getConnections().size());
    assertEquals("n2", out.getConnections().get(0).getToClientId());

    verify(activeChainPlanService, never()).updateActivePlan(any(), any());
  }

  @Test
  void validJsonWithConversationIdNotApprovedMergesElementIdsButDoesNotRestoreConnections()
      throws Exception {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-nappr");

    ConnectionPlan edge = new ConnectionPlan();
    edge.setFromClientId("n1");
    edge.setToClientId("n2");
    ChainImplementationPlan snapPlan = planWithConnectionsAndCatalog(edge, "cat-88", null);
    ActiveChainPlanSnapshot snap =
        new ActiveChainPlanSnapshot(
            "pid", "nm", null, null, snapPlan, Instant.now(), List.of(), List.of());
    when(activeChainPlanService.getActive("conv-nappr")).thenReturn(Optional.of(snap));
    when(activeChainPlanService.isApproved("conv-nappr")).thenReturn(false);

    ElementPlan batchElem = new ElementPlan();
    batchElem.setClientId("n1");
    batchElem.setType("http-trigger");
    ChainImplementationPlan batchPlan = new ChainImplementationPlan();
    batchPlan.setElements(new ArrayList<>(List.of(batchElem)));
    String json = objectMapper.writeValueAsString(batchPlan);

    ChainPlanLoadResult r = loader.load("chain-merge", json);

    ChainPlanLoadResult.Loaded loaded = assertInstanceOf(ChainPlanLoadResult.Loaded.class, r);
    assertEquals("batch_json", loaded.source());
    ChainImplementationPlan out = loaded.plan();
    assertEquals("cat-88", out.getElements().get(0).getElementId());
    assertTrue(out.getConnections() == null || out.getConnections().isEmpty());

    verify(activeChainPlanService).isApproved("conv-nappr");
    verify(activeChainPlanService).updateActivePlan("conv-nappr", out);
  }

  private static ChainImplementationPlan simplePlanFromBatch() {
    ElementPlan e = new ElementPlan();
    e.setClientId("a1");
    e.setType("http-trigger");
    ChainImplementationPlan p = new ChainImplementationPlan();
    p.setElements(List.of(e));
    return p;
  }
}
