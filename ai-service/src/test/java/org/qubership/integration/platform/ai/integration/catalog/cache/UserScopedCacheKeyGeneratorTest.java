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
    ToolSession.bind("conv-alice");
    UserTokenStore.current().ifPresent(t -> UserTokenStore.remove("conv-alice", t));
    ToolSession.bind("conv-bob");
    UserTokenStore.current().ifPresent(t -> UserTokenStore.remove("conv-bob", t));
    ToolSession.clear();
  }

  @Test
  void differentCallersGetDifferentKeys() {
    long exp = Instant.now().getEpochSecond() + 600;
    ToolSession.bind("conv-alice");
    UserTokenStore.put("conv-alice", jwt(exp, "alice"));
    Object alice = generator.generate(null, "model-1");
    ToolSession.clear();
    ToolSession.bind("conv-bob");
    UserTokenStore.put("conv-bob", jwt(exp, "bob"));

    assertNotEquals(alice, generator.generate(null, "model-1"));
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
