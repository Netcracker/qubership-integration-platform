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
  void acceptsOneValidCaptureAndRejectsADuplicate() {
    DesignPlanCaptureSession.Binding binding = bind("conversation-a", "2026.1");
    ToolSession.bind("conversation-a");

    assertTrue(tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call"))
        .contains("\"nextAction\":\"HANDOFF\""));
    assertEquals("2026.1", binding.candidate().get().apiRelease());
    assertTrue(tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Other", "Other"))
        .contains("\"code\":\"DUPLICATE_CAPTURE\""));
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
    assertTrue(tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call"))
        .contains("\"nextAction\":\"HANDOFF\""));
    assertNotNull(binding.candidate().get());
  }

  @Test
  void keepsConcurrentConversationBindingsSeparateAndRejectsSameConversationOverlap() {
    DesignPlanCaptureSession.Binding first = bind("conversation-a", "2026.1");
    DesignPlanCaptureSession.Binding second = bind("conversation-b", "2027.1");

    ToolSession.bind("conversation-a");
    tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("A", "A"));
    ToolSession.bind("conversation-b");
    tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("B", "B"));

    assertEquals("2026.1", first.candidate().get().apiRelease());
    assertEquals("2027.1", second.candidate().get().apiRelease());
    assertThrows(IllegalStateException.class, () -> bind("conversation-b", "2028.1"));
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
}
