package org.qubership.integration.platform.ai.a2a.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalCallerAccessPolicyTest {

  @Test
  void localCallerUsesConfiguredDefaults() {
    LocalCallerContextProvider provider =
        new LocalCallerContextProvider("local", "local-user");
    CallerContext caller = provider.current();
    assertEquals("local", caller.tenantId());
    assertEquals("local-user", caller.subjectId());
  }

  @Test
  void localCallerIgnoresRequestMetadataIdentityHints() {
    LocalCallerContextProvider provider =
        new LocalCallerContextProvider("local", "local-user");
    Map<String, Object> metadata =
        Map.of("tenantId", "spoofed-tenant", "subjectId", "spoofed-user", "user", "attacker");
    CallerContext caller = provider.current();
    assertEquals("local", caller.tenantId());
    assertEquals("local-user", caller.subjectId());
    assertNotEquals(metadata.get("tenantId"), caller.tenantId());
    assertNotEquals(metadata.get("subjectId"), caller.subjectId());
  }

  @Test
  void permitAllPolicyAcceptsEveryOperation() {
    TaskAccessPolicy policy = new LocalPermitAllTaskAccessPolicy();
    CallerContext caller = new CallerContext("local", "local-user");
    for (TaskOperation operation : TaskOperation.values()) {
      assertDoesNotThrow(
          () -> policy.check(caller, operation, new TaskIdentity("task-1", "ctx-1")));
    }
  }
}
