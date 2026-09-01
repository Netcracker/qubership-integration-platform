package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairMessageBuilder;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * LangChain4j tool that lets the discovery agent persist a requirement brief.
 * Conversation id is taken from {@link org.qubership.integration.platform.ai.chat.ChatMdc#CONVERSATION_ID}.
 */
@ApplicationScoped
public class RequirementBriefTool {

  private static final Logger LOG = Logger.getLogger(RequirementBriefTool.class);

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Discovery did not capture a requirement brief. The agent must call captureRequirementBrief"
          + " with a non-empty goal or summary before finishing.";

  static final String DUPLICATE_CAPTURE_MESSAGE =
      "Requirement brief already captured. Do not call captureRequirementBrief again;"
          + " finish this turn without further tool calls.";

  private final CaptureSession captureSession;
  private final RequirementDraftStore draftStore;
  private final ObjectMapper objectMapper;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final CaptureRepairMessageBuilder repairMessageBuilder;
  private final RequirementBriefCoverageValidator coverageValidator;

  @Inject
  RequirementBriefTool(
      CaptureSession captureSession,
      RequirementDraftStore draftStore,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureRepairMessageBuilder repairMessageBuilder) {
    this(
        captureSession,
        draftStore,
        objectMapper,
        feedbackStore,
        repairMessageBuilder,
        new RequirementBriefCoverageValidator());
  }

  RequirementBriefTool(
      CaptureSession captureSession,
      RequirementDraftStore draftStore,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureRepairMessageBuilder repairMessageBuilder,
      RequirementBriefCoverageValidator coverageValidator) {
    this.captureSession = captureSession;
    this.draftStore = draftStore;
    this.objectMapper = objectMapper;
    this.feedbackStore = feedbackStore;
    this.repairMessageBuilder = repairMessageBuilder;
    this.coverageValidator = coverageValidator;
  }

  /** Test constructor that keeps the previous brief-only wiring. */
  public RequirementBriefTool(
      CaptureSession captureSession,
      ObjectMapper objectMapper,
      CaptureAttemptFeedbackStore feedbackStore,
      CaptureRepairMessageBuilder repairMessageBuilder) {
    this(
        captureSession,
        new RequirementDraftStore(),
        objectMapper,
        feedbackStore,
        repairMessageBuilder,
        new RequirementBriefCoverageValidator());
  }

  @Tool("""
      Capture the distilled requirement brief in the same turn once analysis is complete.
      Do not pass conversationId — the server binds the brief to the current chat session\
       automatically.
      goal and summary cannot both be blank.
      Facts from the approved draft are pinned by the server (stable sourceFactId values). Focus\
       on goal, summary, inputs, constraints, and assumptions.
      Omit facts when an approved draft exists. The server projects entry points and service calls\
       from the approved RequirementFlow and catalog bindings.
      When no field adaptation was requested, leave mappingIntents empty. The\
       server records that as pass-through: a direct connection with no mapping row. When the\
       user requested field adaptation across an approved flow transition, capture\
       mappingIntents. Prose is enough: Subject = name\
       becomes sourcePath=name and targetPath=Subject. A computed rule such as a priority bucket,\
       a default, or JSON construction sets expression on that rule. One intent per\
       approved flow transition. sourceRef and targetRef must match a listed transition.\
       Put preserve or echo rules on the transition that writes the target payload.\
       Never invent identity copies for fields the user did not\
       mention. Use the approved interactionId values as sourceRef and targetRef. Do not set\
       mapping ports; the server assigns them from the approved flow.
      Minimal example:
      {
        "goal": "Expose a greeting HTTP endpoint",
        "inputs": ["HTTP request body"],
        "constraints": ["External route", "RBAC required"],
        "assumptions": [],
        "mappingIntents": [],
        "summary": "HTTP trigger forwards to a script that returns a greeting."
      }""")
  public String captureRequirementBrief(RequirementBriefCapture capture) {
    String conversationId = ChainPlanTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    String preview = previewCapture(capture);
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureRequirementBrief",
        conversationId,
        "preview=" + AiTraceLog.preview(preview, 400));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        String message = "conversationId is required (no active chat session)";
        LOG.warnf("captureRequirementBrief: %s", message);
        return finish(conversationId, startMs, message);
      }
      if (capture == null) {
        String message = "capture is required";
        LOG.warnf("captureRequirementBrief: %s conversationId=%s", message, conversationId);
        return finish(conversationId, startMs, message);
      }

      if (!hasText(capture.goal()) && !hasText(capture.summary())) {
        String message =
            repairMessageBuilder.requirementBriefEmptyMessage("captureRequirementBrief");
        LOG.warnf("captureRequirementBrief: validation failed conversationId=%s", conversationId);
        boolean repeated =
            feedbackStore.recordPlanValidationFailure(conversationId, message, capture);
        if (repeated) {
          throw new CaptureValidationException(message);
        }
        return finish(conversationId, startMs, message);
      }

      RequirementBrief brief = toRequirementBrief(capture);
      // A pooled worker can still carry another conversation's binding, so resolve by id.
      Optional<RequirementDraft> approved =
          ProductCapabilityCaptureContext.approvedDraft(conversationId)
              .or(() -> draftStore.get(conversationId).filter(RequirementDraft::readyForPlan));
      if (approved.isPresent()) {
        brief = pinApprovedDraft(brief, approved.get());
      } else if (ProductCapabilityCaptureContext.isBound(conversationId)) {
        String message = "approved draft is required before capturing a requirement brief";
        return finish(conversationId, startMs, message);
      }
      try {
        brief = RequirementBriefProjector.project(brief);
      } catch (IllegalArgumentException ex) {
        LOG.warnf(
            "captureRequirementBrief: projection rejected conversationId=%s reason=%s",
            conversationId, ex.getMessage());
        return finish(conversationId, startMs, ex.getMessage());
      }
      if (approved.isPresent()) {
        Optional<String> coverageError = coverageValidator.validate(approved.get(), brief);
        if (coverageError.isPresent()) {
          String message = "Requirement brief coverage failed: " + coverageError.get();
          LOG.warnf(
              "captureRequirementBrief: coverage failed conversationId=%s reason=%s",
              conversationId, coverageError.get());
          boolean repeated =
              feedbackStore.recordPlanValidationFailure(conversationId, message, capture);
          if (repeated) {
            throw new CaptureValidationException(message);
          }
          return finish(conversationId, startMs, message);
        }
      }

      CaptureKey key = CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId);
      String headline = hasText(brief.goal()) ? brief.goal() : brief.summary();
      String successMessage =
          "Requirement brief captured: "
              + AiTraceLog.preview(headline, 160)
              + ". Do not call captureRequirementBrief again;"
              + " finish this turn without further tool calls.";
      String accepted =
          captureSession.accept(key, brief, successMessage, DUPLICATE_CAPTURE_MESSAGE);
      feedbackStore.clearPlan(conversationId);
      RequirementBrief captured = brief;
      ProductCapabilityCaptureContext.binding(conversationId)
          .ifPresent(bound -> ProductCapabilityCaptureContext.offerBrief(bound, captured));

      LOG.infof(
          "captureRequirementBrief: stored brief conversationId=%s goal='%s' facts=%d",
          conversationId,
          AiTraceLog.preview(headline, 120),
          brief.facts().size());
      return finish(conversationId, startMs, accepted);
    } catch (CaptureValidationException e) {
      throw e;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureRequirementBrief", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing requirement brief: " + e.getMessage();
    }
  }

  /**
   * Replaces capture facts, flow, catalog bindings, and draft text with the server-owned approved
   * values. Goal/summary/inputs/constraints/assumptions stay from the agent.
   */
  static RequirementBrief pinApprovedDraft(RequirementBrief brief, RequirementDraft approved) {
    return brief
        .withFacts(approved.facts())
        .withApprovedDraftText(approved.planningText())
        .withFlow(approved.flow())
        .withCatalogBindings(approved.catalogBindings());
  }

  private static RequirementBrief toRequirementBrief(RequirementBriefCapture capture) {
    return new RequirementBrief(
            nullToEmpty(capture.goal()),
            copyList(capture.inputs()),
            copyList(capture.constraints()),
            copyList(capture.assumptions()),
            capture.citations() == null ? List.of() : List.copyOf(capture.citations()),
            nullToEmpty(capture.summary()),
            capture.approvedDraftReference(),
            nullToEmpty(capture.approvedDraftText()),
            capture.facts() == null ? List.of() : List.copyOf(capture.facts()))
        .withMappingIntents(capture.toIntents());
  }

  private static List<String> copyList(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static String nullToEmpty(String value) {
    return value != null ? value.trim() : "";
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private String previewCapture(RequirementBriefCapture capture) {
    if (capture == null) {
      return "null";
    }
    try {
      return objectMapper.writeValueAsString(capture);
    } catch (Exception e) {
      return capture.toString();
    }
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureRequirementBrief", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }
}
