package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.ToolArgumentsException;
import org.junit.jupiter.api.Test;

class ToolArgumentsFailuresTest {

  @Test
  void detectsToolArgumentsExceptionInCauseChain() {
    Throwable nested =
        new IllegalStateException(
            "skill stream failed",
            new ToolArgumentsException("Cannot deserialize value of type `NamingManifest`"));

    assertTrue(ToolArgumentsFailures.isToolArgumentsFailure(nested));
    assertEquals(
        "Cannot deserialize value of type `NamingManifest`",
        ToolArgumentsFailures.message(nested));
  }

  @Test
  void detectsToolArgumentsExceptionNameInMessage() {
    assertTrue(
        ToolArgumentsFailures.isToolArgumentsFailure(
            new RuntimeException(
                "io.quarkus.langchain4j.runtime.aiservice.QuarkusToolExecutor: "
                    + "ToolArgumentsException: Unexpected token")));
  }

  @Test
  void ignoresUnrelatedFailures() {
    assertFalse(ToolArgumentsFailures.isToolArgumentsFailure(new IllegalStateException("timeout")));
    assertFalse(ToolArgumentsFailures.isToolArgumentsFailure(null));
  }
}
