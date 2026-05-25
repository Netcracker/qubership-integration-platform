package org.qubership.integration.platform.ai.llm.routing;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ConversationPhaseResolverQuarkusTest {

  @Inject ConversationPhaseResolver conversationPhaseResolver;

  @Test
  void resolveWithoutPlanIsCold() {
    assertEquals(
        ConversationPhase.COLD, conversationPhaseResolver.resolve(UUID.randomUUID().toString()));
  }
}
