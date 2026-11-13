package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.NotAuthorizedException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UserTokenStoreTest {

  @AfterEach
  void unbind() {
    UserTokenStore.remove("conv-exp", "old");
    UserTokenStore.remove("conv-exp", jwt("alice", 1));
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

  @Test
  void renewSwapsTheTokenOfTheSameSubjectOnly() {
    UserTokenStore.put("conv-exp", jwt("alice", 1));

    assertFalse(UserTokenStore.renew("conv-exp", jwt("bob", 2)));
    assertFalse(UserTokenStore.renew("conv-exp", "opaque"));
    assertFalse(UserTokenStore.renew("no-turn", jwt("alice", 2)));
    assertTrue(UserTokenStore.renew("conv-exp", jwt("alice", 2)));

    assertEquals(Optional.of(jwt("alice", 2)), UserTokenStore.get("conv-exp"));
    UserTokenStore.remove("conv-exp", jwt("alice", 1));
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
  }

  @Test
  void aSharedAliasSeesRenewalsAndReportsRejectionsToTheTurn() {
    UserTokenStore.put("conv-exp", jwt("alice", 1));
    String shared = UserTokenStore.share("conv-exp", "conv-exp-clean");
    try {
      UserTokenStore.renew("conv-exp", jwt("alice", 2));
      assertEquals(Optional.of(jwt("alice", 2)), UserTokenStore.get("conv-exp-clean"));

      ToolSession.bind("conv-exp-clean");
      UserTokenStore.rejectCurrent(403);
      assertEquals(403, UserTokenStore.rejection("conv-exp"));
    } finally {
      UserTokenStore.remove("conv-exp-clean", shared);
    }
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp-clean"));
  }

  private static String jwt(String subject, int version) {
    String payload = "{\"sub\":\"" + subject + "\",\"v\":" + version + "}";
    return "h."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".s";
  }
}
