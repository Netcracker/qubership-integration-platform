package org.qubership.integration.platform.ai.chat.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceSnapshot;

class ConversationEvidenceResourceTest {

  private static final String CONVERSATION_ID = "conv-evidence-rest";
  private static final String UNKNOWN_ID = "unknown-conversation";

  private ConversationEvidenceStore store;
  private ConversationEvidenceResource resource;

  @BeforeEach
  void setUp() {
    store = new ConversationEvidenceStore();
  }

  @Test
  void evidenceReturns404WhenFeatureDisabled() {
    resource = new ConversationEvidenceResource(store, false);

    Response response = resource.evidence(CONVERSATION_ID);

    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
  }

  @Test
  void evidenceReturns404ForUnknownConversationWhenEnabled() {
    resource = new ConversationEvidenceResource(store, true);

    Response response = resource.evidence(UNKNOWN_ID);

    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
  }

  @Test
  void evidenceReturnsEmptySnapshotForKnownEmptyAccumulator() {
    store.getOrCreate(CONVERSATION_ID);
    resource = new ConversationEvidenceResource(store, true);

    Response response = resource.evidence(CONVERSATION_ID);

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    EvidenceSnapshot snapshot = (EvidenceSnapshot) response.getEntity();
    assertNotNull(snapshot);
    assertEquals(CONVERSATION_ID, snapshot.conversationId());
    assertTrue(snapshot.timeline().isEmpty());
    assertEquals(null, snapshot.knowledge().packageRef());
    assertTrue(snapshot.knowledge().objectIds().isEmpty());
    assertEquals(0, snapshot.knowledge().contentChars());
  }

  @Test
  void evidenceReturnsSnapshotWhenAccumulatorHasData() {
    store.getOrCreate(CONVERSATION_ID).recordPipeline("plan", "completed");
    resource = new ConversationEvidenceResource(store, true);

    Response response = resource.evidence(CONVERSATION_ID);

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    EvidenceSnapshot snapshot = (EvidenceSnapshot) response.getEntity();
    assertEquals(1, snapshot.timeline().size());
    assertEquals("plan", snapshot.timeline().get(0).id());
  }
}
