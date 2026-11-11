package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;

/** Creates the product capture tool for offline pipeline integration tests. */
public final class ProductRequirementBriefToolTestSupport {

  private ProductRequirementBriefToolTestSupport() {}

  public static ProductRequirementBriefTool.Result capture(
      ProductRequirementBriefTool tool, RequirementAnalysisCapture capture, String conversationId) {
    return tool.capture(capture, conversationId);
  }

  public static ProductRequirementBriefTool create(
      CaptureSession session, CaptureAttemptFeedbackStore feedback) {
    return new ProductRequirementBriefTool(
        session, feedback, new ObjectMapper(), new E2eRecoveryFaultInjector("", ""));
  }
}
