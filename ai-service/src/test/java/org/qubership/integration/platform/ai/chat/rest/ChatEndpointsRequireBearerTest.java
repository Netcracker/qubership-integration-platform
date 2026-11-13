package org.qubership.integration.platform.ai.chat.rest;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.NotAuthorizedException;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;

class ChatEndpointsRequireBearerTest {

  @Test
  void chatWithoutBearerIsRejected() {
    assertThrows(
        NotAuthorizedException.class, () -> new ChatController(null, null).chat(null, new ChatRequest()));
    assertThrows(
        NotAuthorizedException.class,
        () -> new UiChatAdapterResource(null).chat("Basic abc", new ChatRequest()));
  }

  @Test
  void renewWithoutBearerIsRejected() {
    assertThrows(
        NotAuthorizedException.class, () -> new ChatController(null, null).renewToken("c1", null));
  }

  @Test
  void chatWithoutBearerIsAcceptedWhenAuthNotRequired() {
    System.setProperty("qip.ai.auth.required", "false");
    try {
      var request = new ChatRequest();
      assertThrows(
          NullPointerException.class, () -> new ChatController(null, null).chat(null, request));
      assertNull(request.getUserToken());
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }
}
