package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * The wait-view sanitizer is gone because gates no longer travel as prose. Chat output must still
 * never carry machine text: neither pipeline status tokens nor the markers a wait uses to name its
 * gate.
 */
class ChatOutputInternalTokenTest {

  private static final Pattern MACHINE_TEXT =
      Pattern.compile(
          "\\b(?:READY_FOR_[A-Z0-9_]+|WAITING_FOR_[A-Z0-9_]+|CHAIN_MATERIALIZED|PLAN_APPROVED|"
              + "NEEDS_INPUT|CONTRACT_FAILURE|RETRYABLE_TECHNICAL_FAILURE|"
              + "MISSING_MANDATORY_INPUT)\\b|__GATE:|__MAPPING_EDGES__");

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
      assertClean(event.toString());
      if (event instanceof ChatEvent.Decision decision) {
        // Named fields as well as the record's own rendering: a leak into missingEvidence reads
        // differently from one into the question, and both reach the reader.
        assertClean(decision.question());
        assertClean(decision.reason());
        decision.missingEvidence().forEach(ChatOutputInternalTokenTest::assertClean);
      }
    }
    assertTrue(
        events.stream().anyMatch(ChatEvent.Decision.class::isInstance),
        "approval wait must reach the reader as a decision card, not status prose");
  }

  /**
   * A gate names itself with a marker inside its prompt. The reader gets the question without it,
   * whichever side builds the card.
   */
  @Test
  void aGateMarkerNeverSurvivesIntoTheQuestionAReaderSees() {
    String tagged =
        PipelineGates.tag(PipelineGates.MAPPING_GAP, "Some data mappings are still missing.");

    assertEquals(PipelineGates.MAPPING_GAP, PipelineGates.gateOf(tagged).orElseThrow());
    assertClean(PipelineGates.strip(tagged));
    assertClean(
        ((ChatEvent.Decision)
                ChatEvent.decision(
                    new CreateChainPendingAction.Clarify(
                        PipelineGates.strip(tagged), List.of(), PipelineGates.MAPPING_GAP),
                    3L,
                    PipelineGates.strip(tagged),
                    ChatEvent.actionsForGate(PipelineGates.MAPPING_GAP)))
            .toString());
  }

  private static void assertClean(String text) {
    if (text == null) {
      return;
    }
    assertFalse(
        MACHINE_TEXT.matcher(text).find(), () -> "machine text reached the reader: " + text);
  }
}
