package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;

/** Captures and validates one typed design plan for the active planning session. */
@ApplicationScoped
public class DesignPlanCaptureTool {

  public record Issue(String code, String path, String message) {}

  public record Result(
      boolean accepted, boolean changed, String nextAction, List<Issue> issues, String message) {}

  static final String CAPTURED_MESSAGE =
      "Design plan captured. Do not call captureDesignPlan again; finish this turn.";
  static final String DUPLICATE_MESSAGE =
      "Design plan already captured. Do not call captureDesignPlan again; finish this turn.";

  private final DesignPlanCaptureAdapter adapter;
  private final ObjectMapper mapper;

  @Inject
  public DesignPlanCaptureTool(ObjectMapper mapper) {
    this(new DesignPlanCaptureAdapter(), mapper);
  }

  DesignPlanCaptureTool() {
    this(new DesignPlanCaptureAdapter(), new ObjectMapper());
  }

  DesignPlanCaptureTool(DesignPlanCaptureAdapter adapter, ObjectMapper mapper) {
    this.adapter = Objects.requireNonNull(adapter, "adapter");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Tool(
      value = """
          Capture optional descriptions for approved planning targets. Use notes=[] when no
          descriptions are needed. Do not send steps, owners, claims, dependencies, release, or
          revision fields. The server builds the complete plan from the approved semantic design
          and pinned compiler DAG. Call once; if rejected, correct only the listed notes.""",
      returnBehavior = ReturnBehavior.IMMEDIATE)
  public String captureDesignPlan(DesignPlanCapture capture) {
    return encode(capture(capture, ToolSession.resolveConversationId()));
  }

  public String rejectArguments(String conversationId, StructuredCaptureArguments.Issue issue) {
    DesignPlanCaptureSession.Binding binding =
        DesignPlanCaptureSession.binding(conversationId).orElse(null);
    if (binding == null) {
      return encode(rejected("PLANNING_SESSION_NOT_FOUND", "/",
          "No active design planning session.", true));
    }
    if (binding.candidate().get() != null) {
      return encode(rejected("DUPLICATE_CAPTURE", "/", DUPLICATE_MESSAGE, true));
    }
    if (binding.terminal().get()) {
      return encode(rejected("PLANNING_INPUT_UNRESOLVED", "/",
          binding.rejection().get(), true));
    }
    return encode(reject(binding, issue.code(), issue.path(), issue.message(), false));
  }

  Result capture(DesignPlanCapture capture, String conversationId) {
    DesignPlanCaptureSession.Binding binding =
        DesignPlanCaptureSession.binding(conversationId).orElse(null);
    if (binding == null) {
      return rejected("PLANNING_SESSION_NOT_FOUND", "/",
          "No active design planning session.", true);
    }
    if (binding.candidate().get() != null) {
      return rejected("DUPLICATE_CAPTURE", "/", DUPLICATE_MESSAGE, true);
    }
    if (binding.terminal().get()) {
      return rejected("PLANNING_INPUT_UNRESOLVED", "/",
          binding.rejection().get(), true);
    }
    try {
      DesignPlanContract contract = adapter.adapt(capture, binding.revision(), binding.brief(), binding.pin(),
          binding.apiRelease());
      binding.rejection().set(null);
      binding.rejectionFindings().set(List.of());
      binding.candidate().set(contract);
      return new Result(true, true, "HANDOFF", List.of(), CAPTURED_MESSAGE);
    } catch (PlannerContractException ex) {
      String message = ex.getMessage() == null ? "Planning inputs are unresolved." : ex.getMessage();
      binding.rejection().set(message);
      binding.rejectionFindings().set(List.of());
      binding.terminal().set(true);
      return rejected("PLANNING_INPUT_UNRESOLVED", "/", message, true);
    } catch (IllegalArgumentException | NullPointerException ex) {
      String message =
          ex.getMessage() == null || ex.getMessage().isBlank()
              ? "Invalid design plan capture shape"
              : ex.getMessage();
      return reject(binding, "CAPTURE_SHAPE_INVALID", "/notes", message, false);
    }
  }

  private static Result reject(
      DesignPlanCaptureSession.Binding binding, String code, String path,
      String message, boolean terminal) {
    binding.rejection().set(message);
    binding.rejectionFindings().set(List.of(new DesignPlanContractFinding(
        DesignPlanContractFinding.Code.CAPTURE_SHAPE_INVALID,
        null, "", "", true, code + ": " + message)));
    return rejected(code, path, message, terminal);
  }

  private static Result rejected(String code, String path, String message, boolean terminal) {
    return new Result(false, false, terminal ? "STOP" : "REPAIR_CAPTURE",
        List.of(new Issue(code, path, message)), message);
  }

  private String encode(Result result) {
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize design plan capture result", error);
    }
  }
}
