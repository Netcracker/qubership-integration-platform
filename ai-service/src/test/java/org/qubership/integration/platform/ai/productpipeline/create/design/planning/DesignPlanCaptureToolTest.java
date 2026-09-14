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
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.ClaimRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract.OwnerKind;
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

    assertEquals(
        DesignPlanCaptureTool.CAPTURED_MESSAGE,
        tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call")));
    assertEquals("2026.1", binding.candidate().get().apiRelease());
    assertEquals(
        DesignPlanCaptureTool.DUPLICATE_MESSAGE,
        tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Other", "Other")));
  }

  @Test
  void rejectedCaptureDoesNotBlockACorrectedCapture() {
    DesignPlanCaptureSession.Binding binding = bind("conversation-a", "2026.1");
    ToolSession.bind("conversation-a");
    DesignPlanCapture invalid =
        new DesignPlanCapture(
            List.of(
                new DesignPlanCapture.Step(
                    "ghost",
                    "Unknown target",
                    new DesignPlanCapture.Owner(OwnerKind.SKILL, "cip-trigger-generator"),
                    List.of(
                        new DesignPlanCapture.Claim(
                            TargetKind.ENTRY_POINT, "ghost", ClaimRole.PRODUCER)),
                    List.of())));

    String rejection = tool.captureDesignPlan(invalid);
    assertTrue(rejection.contains("UNKNOWN_TARGET"), rejection);
    assertNull(binding.candidate().get());
    assertEquals(
        DesignPlanCaptureTool.CAPTURED_MESSAGE,
        tool.captureDesignPlan(DesignPlanTestFixtures.validCapture("Trigger", "Call")));
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
        DesignPlanTestFixtures.pin(revision));
  }
}
