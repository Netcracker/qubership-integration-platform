package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Ticket 10: the wait-view sanitizer is gone because gates no longer travel as prose. Chat output
 * must still never carry internal pipeline status tokens.
 */
class ChatOutputInternalTokenTest {

  private static final Pattern INTERNAL_STATUS_TOKEN =
      Pattern.compile(
          "\\b(?:READY_FOR_[A-Z0-9_]+|WAITING_FOR_[A-Z0-9_]+|CHAIN_MATERIALIZED|PLAN_APPROVED|"
              + "NEEDS_INPUT|CONTRACT_FAILURE|RETRYABLE_TECHNICAL_FAILURE|"
              + "MISSING_MANDATORY_INPUT)\\b");

  @Test
  void chatOutputDoesNotCarryInternalStatusTokens() throws Exception {
    CreateProductPipelineCoordinatorTest.FixtureHelper helper =
        CreateProductPipelineCoordinatorTest.FixtureHelper.create();
    CreateProductPipelineCoordinator coordinator = helper.coordinator();

    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("create greetings API");
    List<ChatEvent> events =
        coordinator.handle(request, "conv-no-token").collect().asList().await().indefinitely();

    assertTrue(
        coordinator.loadRun("conv-no-token").orElseThrow().run().status()
            == RunStatus.WAITING_FOR_APPROVAL);

    for (ChatEvent event : events) {
      assertFalse(
          INTERNAL_STATUS_TOKEN.matcher(event.toString()).find(),
          () -> "chat event leaked an internal status token: " + event);
    }
    assertTrue(
        events.stream().anyMatch(ChatEvent.Decision.class::isInstance),
        "approval wait must reach the reader as a decision card, not status prose");
  }
}
