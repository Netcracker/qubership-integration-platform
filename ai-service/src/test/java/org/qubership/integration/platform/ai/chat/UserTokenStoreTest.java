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
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UserTokenStoreTest {

  private static final LongSupplier SYSTEM_CLOCK = UserTokenStore.clock;

  @AfterEach
  void unbind() {
    UserTokenStore.clock = SYSTEM_CLOCK;
    UserTokenStore.clear("conv-exp");
    UserTokenStore.clear("conv-exp-clean");
    UserTokenStore.clear("conv-busy");
    ToolSession.clear();
  }

  private static void setNow(long epochSecond) {
    UserTokenStore.clock = () -> epochSecond;
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
  void sameSubjectTurnsShareTheSlotUntilTheLastHoldEnds() {
    UserTokenStore.Hold first = UserTokenStore.acquire("conv-exp", jwt("alice", 1));
    UserTokenStore.Hold second = UserTokenStore.acquire("conv-exp", jwt("alice", 2));

    first.release();
    assertEquals(Optional.of(jwt("alice", 2)), UserTokenStore.get("conv-exp"));

    second.release();
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
  }

  @Test
  void releaseIsIdempotentAndDoesNotDropAnotherHold() {
    UserTokenStore.Hold first = UserTokenStore.acquire("conv-exp", jwt("alice", 1));
    UserTokenStore.Hold second = UserTokenStore.acquire("conv-exp", jwt("alice", 1));

    first.release();
    first.release();
    assertEquals(Optional.of(jwt("alice", 1)), UserTokenStore.get("conv-exp"));

    second.release();
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
  }

  @Test
  void otherSubjectIsRejectedWhileTheConversationIsHeld() {
    UserTokenStore.Hold alice = UserTokenStore.acquire("conv-busy", jwt("alice", 1));

    UserTokenStore.ConversationBusyException busy = assertThrows(
        UserTokenStore.ConversationBusyException.class,
        () -> UserTokenStore.acquire("conv-busy", jwt("bob", 1)));
    assertEquals(409, busy.getResponse().getStatus());

    alice.release();
    UserTokenStore.Hold bob = UserTokenStore.acquire("conv-busy", jwt("bob", 1));
    bob.release();
  }

  @Test
  void pinnedRunKeepsTheSlotAfterTheTurnReleasesItsHold() {
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwt("alice", 1));
    Optional<UserTokenStore.Hold> run = UserTokenStore.pin("conv-exp");

    turn.release();
    assertEquals(Optional.of(jwt("alice", 1)), UserTokenStore.get("conv-exp"));

    run.orElseThrow().release();
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
  }

  @Test
  void pinWithoutASlotIsEmpty() {
    assertEquals(Optional.empty(), UserTokenStore.pin("conv-exp"));
  }

  @Test
  void expiredTokenIsNeverReturnedWhileItsSlotIsHeld() {
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwtExpiring("alice", 100));
    try {
      setNow(99);
      assertEquals(Optional.of(jwtExpiring("alice", 100)), UserTokenStore.get("conv-exp"));

      setNow(100);
      assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
      ToolSession.bind("conv-exp");
      assertEquals(Optional.empty(), UserTokenStore.current());
      assertThrows(
          UserTokenStore.ConversationBusyException.class,
          () -> UserTokenStore.acquire("conv-exp", jwt("bob", 1)));
    } finally {
      turn.release();
    }
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
  }

  @Test
  void expiredSlotPinnedForRunResumesAfterRenewalAndIsDroppedWithItsLastHold() {
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwtExpiring("alice", 100));
    UserTokenStore.Hold run = UserTokenStore.pin("conv-exp").orElseThrow();
    setNow(100);
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));

    assertTrue(UserTokenStore.renew("conv-exp", jwtExpiring("alice", 200)));
    assertEquals(Optional.of(jwtExpiring("alice", 200)), UserTokenStore.get("conv-exp"));

    turn.release();
    assertEquals(Optional.of(jwtExpiring("alice", 200)), UserTokenStore.get("conv-exp"));
    run.release();
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
    UserTokenStore.Hold bob = UserTokenStore.acquire("conv-exp", jwt("bob", 1));
    bob.release();
  }

  @Test
  void sameSubjectAcquireAfterExpiryReusesTheSlot() {
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwtExpiring("alice", 100));
    UserTokenStore.Hold run = UserTokenStore.pin("conv-exp").orElseThrow();
    setNow(100);

    UserTokenStore.Hold next = UserTokenStore.acquire("conv-exp", jwtExpiring("alice", 200));
    turn.release();
    next.release();

    assertEquals(Optional.of(jwtExpiring("alice", 200)), UserTokenStore.get("conv-exp"));
    run.release();
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
  }

  @Test
  void renewSwapsTheTokenOfTheSameSubjectOnly() {
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwt("alice", 1));

    assertFalse(UserTokenStore.renew("conv-exp", jwt("bob", 2)));
    assertFalse(UserTokenStore.renew("conv-exp", "opaque"));
    assertFalse(UserTokenStore.renew("no-turn", jwt("alice", 2)));
    assertTrue(UserTokenStore.renew("conv-exp", jwt("alice", 2)));

    assertEquals(Optional.of(jwt("alice", 2)), UserTokenStore.get("conv-exp"));
    turn.release();
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp"));
  }

  @Test
  void aSharedAliasSeesRenewalsAndReportsRejectionsToTheTurn() {
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwt("alice", 1));
    UserTokenStore.Hold shared = UserTokenStore.share("conv-exp", "conv-exp-clean");
    try {
      UserTokenStore.renew("conv-exp", jwt("alice", 2));
      assertEquals(Optional.of(jwt("alice", 2)), UserTokenStore.get("conv-exp-clean"));

      ToolSession.bind("conv-exp-clean");
      UserTokenStore.rejectCurrent(403);
      assertEquals(403, UserTokenStore.rejection("conv-exp"));
    } finally {
      shared.release();
      turn.release();
    }
    assertEquals(Optional.empty(), UserTokenStore.get("conv-exp-clean"));
  }

  @Test
  void rejectionBelongsToTheTurnThatRecordedIt() {
    UserTokenStore.Hold first = UserTokenStore.acquire("conv-exp", jwt("alice", 1));
    ToolSession.bind("conv-exp");
    UserTokenStore.rejectCurrent(401);
    assertEquals(401, UserTokenStore.rejection("conv-exp"));

    first.release();
    UserTokenStore.Hold next = UserTokenStore.acquire("conv-exp", jwt("alice", 2));
    assertEquals(0, UserTokenStore.rejection("conv-exp"));
    next.release();
  }

  @Test
  void currentSubjectReadsTheBoundConversationToken() {
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwt("alice", 1));
    ToolSession.bind("conv-exp");
    assertEquals(Optional.of("alice"), UserTokenStore.currentSubject());
    turn.release();
  }

  @Test
  void statusReadsAbsentLiveAndExpiredWithoutDroppingTheSlot() {
    assertEquals(UserTokenStore.Status.ABSENT, UserTokenStore.status("conv-exp"));
    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwtExpiring("alice", 100));
    try {
      setNow(99);
      assertEquals(UserTokenStore.Status.LIVE, UserTokenStore.status("conv-exp"));

      setNow(100);
      assertEquals(UserTokenStore.Status.EXPIRED, UserTokenStore.status("conv-exp"));
      assertEquals(UserTokenStore.Status.EXPIRED, UserTokenStore.status("conv-exp"));
      UserTokenStore.Hold run = UserTokenStore.pin("conv-exp").orElseThrow();
      run.release();
    } finally {
      turn.release();
    }
    assertEquals(UserTokenStore.Status.ABSENT, UserTokenStore.status("conv-exp"));
  }

  @Test
  void currentStatusFollowsTheConversationBoundToThisThread() {
    assertEquals(UserTokenStore.Status.ABSENT, UserTokenStore.currentStatus());
    ToolSession.bind("conv-exp");
    assertEquals(UserTokenStore.Status.ABSENT, UserTokenStore.currentStatus());

    UserTokenStore.Hold turn = UserTokenStore.acquire("conv-exp", jwt("alice", 1));
    try {
      assertEquals(UserTokenStore.Status.LIVE, UserTokenStore.currentStatus());
    } finally {
      turn.release();
    }
  }

  private static String jwt(String subject, int version) {
    return jwtWithClaims("{\"sub\":\"" + subject + "\",\"v\":" + version + "}");
  }

  private static String jwtExpiring(String subject, long expiresAtEpochSecond) {
    return jwtWithClaims("{\"sub\":\"" + subject + "\",\"exp\":" + expiresAtEpochSecond + "}");
  }

  private static String jwtWithClaims(String payload) {
    return "h."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".s";
  }
}
