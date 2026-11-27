package org.qubership.integration.platform.ai.integration.catalog.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

class UserScopedCacheKeyGeneratorTest {

  private final UserScopedCacheKeyGenerator generator = new UserScopedCacheKeyGenerator();

  @AfterEach
  void unbind() {
    UserTokenStore.clear("conv-alice");
    UserTokenStore.clear("conv-bob");
    ToolSession.clear();
  }

  @Test
  void differentCallersGetDifferentKeys() {
    long exp = Instant.now().getEpochSecond() + 600;
    ToolSession.bind("conv-alice");
    UserTokenStore.acquire("conv-alice", jwt(exp, "alice"));
    Object alice = generator.generate(null, "model-1");
    ToolSession.clear();
    ToolSession.bind("conv-bob");
    UserTokenStore.acquire("conv-bob", jwt(exp, "bob"));

    assertNotEquals(alice, generator.generate(null, "model-1"));
  }

  @Test
  void schemaKeysDifferByCallerAndOperation() {
    long exp = Instant.now().getEpochSecond() + 600;
    Object aliceOp1 = keyAs("conv-alice", jwt(exp, "alice"), "op-1");

    assertEquals(aliceOp1, keyAs("conv-alice", jwt(exp, "alice"), "op-1"));
    assertNotEquals(aliceOp1, keyAs("conv-alice", jwt(exp, "alice"), "op-2"));
    assertNotEquals(aliceOp1, keyAs("conv-bob", jwt(exp, "bob"), "op-1"));
  }

  private Object keyAs(String conversationId, String token, String operationId) {
    UserTokenStore.Hold hold = UserTokenStore.acquire(conversationId, token);
    try {
      ToolSession.bind(conversationId);
      return generator.generate(null, operationId);
    } finally {
      ToolSession.clear();
      hold.release();
    }
  }

  private static String jwt(long exp, String subject) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String payload = "{\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}";
    return b64.encodeToString("{}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }

  @Test
  void aNullArgumentStillMakesAKey() {
    assertEquals(generator.generate(null, (Object) null), generator.generate(null, (Object) null));
  }
}
