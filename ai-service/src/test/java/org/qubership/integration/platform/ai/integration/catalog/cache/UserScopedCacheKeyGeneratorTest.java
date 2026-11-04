package org.qubership.integration.platform.ai.integration.catalog.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;

class UserScopedCacheKeyGeneratorTest {

  private final UserScopedCacheKeyGenerator generator = new UserScopedCacheKeyGenerator();

  @AfterEach
  void unbind() {
    UserTokenStore.remove("conv-key", "alice");
    UserTokenStore.remove("conv-key", "bob");
    ToolSession.clear();
  }

  @Test
  void differentCallersGetDifferentKeys() {
    ToolSession.bind("conv-key");
    UserTokenStore.put("conv-key", "alice");
    Object alice = generator.generate(null, "model-1");
    UserTokenStore.put("conv-key", "bob");

    assertNotEquals(alice, generator.generate(null, "model-1"));
  }

  @Test
  void aNullArgumentStillMakesAKey() {
    assertEquals(generator.generate(null, (Object) null), generator.generate(null, (Object) null));
  }
}
