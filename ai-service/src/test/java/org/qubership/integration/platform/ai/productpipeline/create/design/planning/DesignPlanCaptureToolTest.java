package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.TargetKind;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

class DesignPlanCaptureToolTest {

  private final DesignPlanCaptureTool tool = new DesignPlanCaptureTool();

  @AfterEach
  void cleanup() {
    ToolSession.clear();
    DesignPlanCaptureSession.unbind("conversation-a");
    DesignPlanCaptureSession.unbind("conversation-b");
  }

  @Test
  void repeatedInvalidNotesSkipTheAdapterAndNewNotesRemainEligible() {
    bind("conversation-a", "2026.1");
    ToolSession.bind("conversation-a");
    var invalid = new DesignPlanCapture(List.of(new DesignPlanCapture.Note(TargetKind.ENTRY_POINT, "ghost", "Unknown")));
    assertTrue(tool.captureDesignPlan(invalid).contains("Unknown planning target"));
    assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
        () -> tool.captureDesignPlan(invalid));
    String accepted = endedTurn(() -> tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call")));
    assertTrue(accepted.contains("HANDOFF"), accepted);
  }

  @Test
  void acceptsOneValidCaptureAndRejectsADuplicate() {
    DesignPlanCaptureSession.Binding binding = bind("conversation-a", "2026.1");
    ToolSession.bind("conversation-a");

    String accepted = endedTurn(() -> tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call")));
    assertTrue(accepted.contains("\"nextAction\":\"HANDOFF\""), accepted);
    assertEquals("2026.1", binding.candidate().get().apiRelease());
    String duplicate = endedTurn(() -> tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Other", "Other")));
    assertTrue(duplicate.contains("\"code\":\"DUPLICATE_CAPTURE\""), duplicate);
  }

  @Test
  void rejectedCaptureDoesNotBlockACorrectedCapture() {
    DesignPlanCaptureSession.Binding binding = bind("conversation-a", "2026.1");
    ToolSession.bind("conversation-a");
    DesignPlanCapture invalid =
        new DesignPlanCapture(
            List.of(new DesignPlanCapture.Note(
                TargetKind.ENTRY_POINT, "ghost", "Unknown target")));

    String rejection = tool.captureDesignPlan(invalid);
    assertTrue(rejection.contains("Unknown planning target"), rejection);
    assertTrue(rejection.contains("\"nextAction\":\"REPAIR_CAPTURE\""), rejection);
    assertNull(binding.candidate().get());
    String accepted = endedTurn(() -> tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call")));
    assertTrue(accepted.contains("\"nextAction\":\"HANDOFF\""), accepted);
    assertNotNull(binding.candidate().get());
  }

  @Test
  void keepsConcurrentConversationBindingsSeparateAndRejectsSameConversationOverlap() {
    DesignPlanCaptureSession.Binding first = bind("conversation-a", "2026.1");
    DesignPlanCaptureSession.Binding second = bind("conversation-b", "2027.1");

    ToolSession.bind("conversation-a");
    endedTurn(() -> tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("A", "A")));
    ToolSession.bind("conversation-b");
    endedTurn(() -> tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("B", "B")));

    assertEquals("2026.1", first.candidate().get().apiRelease());
    assertEquals("2027.1", second.candidate().get().apiRelease());
    assertThrows(IllegalStateException.class, () -> bind("conversation-b", "2028.1"));
  }

  @Test
  void shouldRetireRejectedPlanFeedbackWhenCorrectedCaptureIsAccepted() {
    bind("conversation-a", "2026.1");
    ToolSession.bind("conversation-a");
    var invalid = new DesignPlanCapture(List.of(
        new DesignPlanCapture.Note(TargetKind.ENTRY_POINT, "ghost", "Unknown target")));
    tool.captureDesignPlan(invalid);

    endedTurn(() -> tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call")));

    assertEquals(java.util.Optional.empty(), tool.feedbackStore.lastPlanFailure("conversation-a"));
    assertEquals("", tool.feedbackStore.repairHistory("conversation-a", "design-planning"));
  }

  private static DesignPlanCaptureSession.Binding bind(
      String conversationId, String apiRelease) {
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    return DesignPlanCaptureSession.bind(
        conversationId,
        revision,
        "revision-hash",
        apiRelease,
        DesignPlanTestFixtures.brief(),
        DesignPlanTestFixtures.planningPin(revision));
  }

  /** Runs a call that must end the model's tool loop and returns the capture result it ended with. */
  private static String endedTurn(org.junit.jupiter.api.function.Executable call) {
    return assertThrows(
        org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException.class, call)
        .getMessage();
  }
}
