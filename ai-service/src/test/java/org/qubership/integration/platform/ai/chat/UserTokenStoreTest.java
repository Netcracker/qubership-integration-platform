package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
  void readsExpiryFromJwtAndTreatsOpaqueTokenAsValid() {
    long now = Instant.now().getEpochSecond();
    assertTrue(UserTokenStore.isExpired(jwt(now - 60)));
    assertFalse(UserTokenStore.isExpired(jwt(now + 600)));
    assertFalse(UserTokenStore.isExpired("opaque-token"));
  }

  @Test
  void waitsForRenewedTokenThenReturnsIt() throws Exception {
    long now = Instant.now().getEpochSecond();
    ToolSession.bind("conv-exp");
    UserTokenStore.put("conv-exp", jwt(now - 60, "alice"));
    String fresh = jwt(now + 600, "alice");
    Thread renew =
        new Thread(
            () -> {
              try {
                Thread.sleep(700);
              } catch (InterruptedException ignored) {
                return;
              }
              UserTokenStore.renew("conv-exp", fresh);
            });
    renew.start();

    assertEquals(Optional.of(fresh), UserTokenStore.currentUsable(5000));
    renew.join();
  }

  @Test
  void stopsWaitingWhenTheTurnEnds() throws Exception {
    String expired = jwt(Instant.now().getEpochSecond() - 60);
    ToolSession.bind("conv-exp");
    UserTokenStore.put("conv-exp", expired);
    Thread finish =
        new Thread(
            () -> {
              try {
                Thread.sleep(700);
              } catch (InterruptedException ignored) {
                return;
              }
              UserTokenStore.remove("conv-exp", expired);
            });
    finish.start();

    long started = System.nanoTime();
    assertEquals(Optional.empty(), UserTokenStore.currentUsable(5000));
    assertTrue(System.nanoTime() - started < 3_000_000_000L);
    finish.join();
  }

  @Test
  void returnsEmptyWhenNobodyRenewsExpiredToken() {
    String expired = jwt(Instant.now().getEpochSecond() - 60);
    ToolSession.bind("conv-exp");
    UserTokenStore.put("conv-exp", expired);

    assertEquals(Optional.empty(), UserTokenStore.currentUsable(600));
  }

  private static String jwt(long exp) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String payload = "{\"exp\":" + exp + "}";
    return b64.encodeToString("{}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }

  @Test
  void renewAcceptsSameSubjectOnly() {
    long exp = Instant.now().getEpochSecond() + 600;
    ToolSession.bind("conv-exp");
    UserTokenStore.put("conv-exp", jwt(exp, "alice"));

    assertFalse(UserTokenStore.renew("conv-exp", jwt(exp, "mallory")));
    assertFalse(UserTokenStore.renew("no-such-turn", jwt(exp, "alice")));
    String renewed = jwt(exp + 60, "alice");
    assertTrue(UserTokenStore.renew("conv-exp", renewed));
    assertEquals(Optional.of(renewed), UserTokenStore.current());
  }

  @Test
  void aSecondUserCannotTakeOverARunningTurn() {
    long exp = Instant.now().getEpochSecond() + 600;
    ToolSession.bind("conv-exp");
    String alice = jwt(exp, "alice");
    UserTokenStore.put("conv-exp", alice);

    assertThrows(ForbiddenException.class, () -> UserTokenStore.put("conv-exp", jwt(exp, "mallory")));
    assertEquals(Optional.of(alice), UserTokenStore.current());
  }

  @Test
  void opaqueTokenCannotReplaceAnotherOnRunningConversation() {
    ToolSession.bind("conv-exp");
    UserTokenStore.put("conv-exp", "opaque-alice");

    assertThrows(ForbiddenException.class, () -> UserTokenStore.put("conv-exp", "opaque-mallory"));
    assertEquals(Optional.of("opaque-alice"), UserTokenStore.current());
  }

  @Test
  void aFinishedTurnDoesNotRemoveANewerTurnsToken() {
    long exp = Instant.now().getEpochSecond() + 600;
    ToolSession.bind("conv-exp");
    String old = jwt(exp, "alice");
    String newer = jwt(exp + 60, "alice");
    UserTokenStore.put("conv-exp", old);
    UserTokenStore.put("conv-exp", newer);

    UserTokenStore.remove("conv-exp", old);

    assertEquals(Optional.of(newer), UserTokenStore.current());
  }

  private static String jwt(long exp, String subject) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String payload = "{\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}";
    return b64.encodeToString("{}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }
}
