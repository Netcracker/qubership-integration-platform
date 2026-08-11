package org.qubership.integration.platform.ai.chat.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ConversationTurnReset;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.TruncateRequest;

class ConversationTurnResourceTest {

  private static final String CONVERSATION_ID = "conv-turn-rest";

  private ConversationService conversationService;
  private ConversationTurnReset turnReset;
  private ConversationTurnResource resource;

  @BeforeEach
  void setUp() {
    conversationService = new ConversationService();
    turnReset = mock(ConversationTurnReset.class);
    resource = new ConversationTurnResource(conversationService, turnReset);
  }

  @Test
  void truncateInvokesFacadeAndReturns204() {
    seedMessages("u0", "a1", "u2");

    Response response = resource.truncate(CONVERSATION_ID, new TruncateRequest(1));

    assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    verify(turnReset).truncateAndReset(CONVERSATION_ID, 1);
  }

  @Test
  void truncateAllowsNegativeOneForContractA() {
    seedMessages("u0");

    Response response = resource.truncate(CONVERSATION_ID, new TruncateRequest(-1));

    assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    verify(turnReset).truncateAndReset(CONVERSATION_ID, -1);
  }

  @Test
  void truncateRejectsIndexPastEnd() {
    seedMessages("u0", "a1");

    Response response = resource.truncate(CONVERSATION_ID, new TruncateRequest(2));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity().toString().contains("afterMessageIndex"));
    verifyNoInteractions(turnReset);
  }

  @Test
  void truncateRejectsIndexBelowNegativeOne() {
    seedMessages("u0");

    Response response = resource.truncate(CONVERSATION_ID, new TruncateRequest(-2));

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    verifyNoInteractions(turnReset);
  }

  @Test
  void truncateRejectsMissingBody() {
    Response response = resource.truncate(CONVERSATION_ID, null);

    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    verifyNoInteractions(turnReset);
  }

  @Test
  void resetInvokesFullResetAndReturns204() {
    Response response = resource.reset(CONVERSATION_ID);

    assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    verify(turnReset).fullReset(eq(CONVERSATION_ID));
  }

  private void seedMessages(String... contents) {
    for (int i = 0; i < contents.length; i++) {
      conversationService.addMessage(
          CONVERSATION_ID,
          i % 2 == 0
              ? ConversationMessage.user(contents[i])
              : ConversationMessage.assistant(contents[i]));
    }
  }
}
