package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;

/** Captures and validates one typed design plan for the active planning session. */
@ApplicationScoped
public class DesignPlanCaptureTool {

  static final String CAPTURED_MESSAGE =
      "Design plan captured. Do not call captureDesignPlan again; finish this turn.";
  static final String DUPLICATE_MESSAGE =
      "Design plan already captured. Do not call captureDesignPlan again; finish this turn.";

  private final DesignPlanCaptureAdapter adapter;
  private final DesignPlanContractValidator validator;

  @Inject
  public DesignPlanCaptureTool() {
    this(new DesignPlanCaptureAdapter(), new DesignPlanContractValidator());
  }

  DesignPlanCaptureTool(
      DesignPlanCaptureAdapter adapter, DesignPlanContractValidator validator) {
    this.adapter = Objects.requireNonNull(adapter, "adapter");
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  @Tool(
      value = """
          Capture the complete typed design plan for this planning turn.
          Do not pass a conversation id. Copy every target id from the supplied semantic design.
          Each required target has exactly one PRODUCER claim. Later steps may add REFERENCE claims.
          Use exact skill ids or APIHub tool operation ids as owners. Express ordering only through
          dependsOnStepIds. Step summaries are display text and have no machine semantics. Call once;
          if the capture is rejected, correct the listed typed findings and call once more.""",
      returnBehavior = ReturnBehavior.IMMEDIATE)
  public String captureDesignPlan(DesignPlanCapture capture) {
    String conversationId = ToolSession.resolveConversationId();
    DesignPlanCaptureSession.Binding binding =
        DesignPlanCaptureSession.binding(conversationId).orElse(null);
    if (binding == null) {
      return "Design plan capture is not bound to an active planning session.";
    }
    if (binding.candidate().get() != null) {
      return DUPLICATE_MESSAGE;
    }
    DesignPlanContract contract;
    List<DesignPlanContractFinding> findings;
    try {
      contract =
          adapter.adapt(
              capture,
              binding.revision().revisionId(),
              binding.revisionHash(),
              binding.apiRelease());
      findings =
          validator.findings(contract, binding.revision(), binding.brief(), binding.pin());
    } catch (IllegalArgumentException | NullPointerException ex) {
      String message =
          ex.getMessage() == null || ex.getMessage().isBlank()
              ? "Invalid design plan capture shape"
              : ex.getMessage();
      DesignPlanContractFinding finding =
          new DesignPlanContractFinding(
              DesignPlanContractFinding.Code.CAPTURE_SHAPE_INVALID,
              null,
              "",
              "",
              true,
              message);
      binding.rejection().set(message);
      binding.rejectionFindings().set(List.of(finding));
      return DesignPlanContractValidator.format(List.of(finding));
    }
    if (!findings.isEmpty()) {
      String message = DesignPlanContractValidator.format(findings);
      binding.rejection().set(message);
      binding.rejectionFindings().set(findings);
      return message;
    }
    binding.rejection().set(null);
    binding.rejectionFindings().set(List.of());
    binding.candidate().set(contract);
    return CAPTURED_MESSAGE;
  }
}
