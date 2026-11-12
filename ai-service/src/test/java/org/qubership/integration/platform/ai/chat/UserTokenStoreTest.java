package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.NotAuthorizedException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UserTokenStoreTest {

  @AfterEach
  void unbind() {
    UserTokenStore.current().ifPresent(token -> UserTokenStore.remove("conv-exp", token));
    ToolSession.clear();
  }

  @Test
  void parsesBearerCaseInsensitively() {
    assertEquals("abc", UserTokenStore.parseBearer("Bearer abc"));
    assertEquals("abc", UserTokenStore.parseBearer("bearer  abc "));
  }

  @Test
  void rejectsMissingOrNonBearerHeader() {
    assertNull(UserTokenStore.parseBearer(null));
    assertNull(UserTokenStore.parseBearer("Basic abc"));
    assertNull(UserTokenStore.parseBearer("Bearer "));
    assertThrows(NotAuthorizedException.class, () -> UserTokenStore.requireBearer(null));
  }

  @Test
  void turnBearerIsOptionalOnlyWhenAuthNotRequired() {
    assertThrows(NotAuthorizedException.class, () -> UserTokenStore.bearerForTurn(null));
    assertEquals("abc", UserTokenStore.bearerForTurn("Bearer abc"));
    System.setProperty("qip.ai.auth.required", "false");
    try {
      assertNull(UserTokenStore.bearerForTurn(null));
      assertEquals("abc", UserTokenStore.bearerForTurn("Bearer abc"));
    } finally {
      System.clearProperty("qip.ai.auth.required");
    }
  }

  @Test
  void aFinishedTurnDoesNotRemoveANewerTurnsToken() {
    ToolSession.bind("conv-exp");
    UserTokenStore.put("conv-exp", "old");
    UserTokenStore.put("conv-exp", "newer");

    UserTokenStore.remove("conv-exp", "old");

    assertEquals(Optional.of("newer"), UserTokenStore.current());
  }
}
