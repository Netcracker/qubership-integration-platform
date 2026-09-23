package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Binding;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Mode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;

/** Captures a product brief while keeping approved requirements under server ownership. */
@ApplicationScoped
public class ProductRequirementBriefTool {

  public record Issue(String code, String path, String message) {}

  public record Result(
      boolean accepted, boolean changed, String nextAction, List<Issue> issues, String message) {}

  private final CaptureSession captureSession;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final RequirementBriefCoverageValidator coverageValidator;
  private final ObjectMapper mapper;
  private final E2eRecoveryFaultInjector recoveryFaultInjector;

  @Inject
  ProductRequirementBriefTool(
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      ObjectMapper mapper,
      E2eRecoveryFaultInjector recoveryFaultInjector) {
    this(captureSession, feedbackStore, new RequirementBriefCoverageValidator(), mapper,
        recoveryFaultInjector);
  }

  ProductRequirementBriefTool(
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      RequirementBriefCoverageValidator coverageValidator,
      ObjectMapper mapper,
      E2eRecoveryFaultInjector recoveryFaultInjector) {
    this.captureSession = captureSession;
    this.feedbackStore = feedbackStore;
    this.coverageValidator = coverageValidator;
    this.mapper = mapper;
    this.recoveryFaultInjector = recoveryFaultInjector;
  }

  @Tool("""
      Capture one requirement brief from the approved draft. The server owns its facts, flow,
      catalog bindings, constraints, and draft text. Supply a goal, readable summary, inputs,
      assumptions, and relevant knowledge citations. For authored FIELD_MAPPING facts, omit
      mappingIntents; the server projects those approved rules. For catalog imports without
      authored mapping facts, use approved interaction ids as mapping endpoints. Omit rows for
      pass-through. The server assigns mapping ids and ports. After acceptance, finish this turn.
      """)
  public String captureRequirementBrief(
      RequirementAnalysisCapture capture, @ToolMemoryId String conversationId) {
    return encode(capture(capture, conversationId));
  }

  /** Reports a schema rejection that occurred before Java argument binding. */
  public String rejectArguments(String conversationId, StructuredCaptureArguments.Issue issue) {
    Binding binding = ProductCapabilityCaptureContext.binding(conversationId)
        .filter(bound -> bound.mode() == Mode.ANALYSIS)
        .orElse(null);
    if (binding == null) {
      String message = "No active chat session for analysis capture.";
      feedbackStore.recordPlanToolArgumentsFailure(conversationId, message);
      return encode(rejected("ANALYSIS_SESSION_NOT_FOUND", "/", message, true));
    }
    if (binding.briefCandidate().get() != null) {
      return encode(rejected("DUPLICATE_CAPTURE", "/",
          RequirementBriefTool.DUPLICATE_CAPTURE_MESSAGE, true));
    }
    String message = issue.code() + " at " + issue.path() + ": " + issue.message();
    boolean repeated = feedbackStore.recordPlanValidationFailure(
        conversationId, message, message);
    return encode(rejected(issue.code(), issue.path(), issue.message(), repeated));
  }

  Result capture(RequirementAnalysisCapture capture, String conversationId) {
    Binding binding = ProductCapabilityCaptureContext.binding(conversationId)
        .filter(bound -> bound.mode() == Mode.ANALYSIS)
        .orElse(null);
    if (binding == null) {
      String message = "No active chat session for analysis capture.";
      feedbackStore.recordPlanToolArgumentsFailure(conversationId, message);
      return rejected("ANALYSIS_SESSION_NOT_FOUND", "/", message, true);
    }
    RequirementDraft approved = binding.approvedDraft();
    if (approved == null || !approved.readyForPlan()) {
      return rejected("APPROVED_DRAFT_REQUIRED", "/", "An approved draft is required.", true);
    }
    CaptureKey key = CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId);
    if (captureSession.isPresent(key) || binding.briefCandidate().get() != null) {
      return rejected("DUPLICATE_CAPTURE", "/", RequirementBriefTool.DUPLICATE_CAPTURE_MESSAGE,
          true);
    }
    if (capture == null || blank(capture.goal()) && blank(capture.summary())) {
      return repair(binding, capture, "MISSING_SUMMARY", "/goal",
          "Provide a goal or a summary.");
    }
    for (int index = 0; index < capture.mappingIntents().size(); index++) {
      RequirementAnalysisCapture.Mapping mapping = capture.mappingIntents().get(index);
      String path = "/mappingIntents/" + index + "/rules";
      if (mapping.rules().isEmpty()) {
        return repair(binding, capture, "MAPPING_RULE_REQUIRED", path,
            "Provide a field rule or omit this pass-through mapping.");
      }
      for (int ruleIndex = 0; ruleIndex < mapping.rules().size(); ruleIndex++) {
        RequirementAnalysisCapture.Rule rule = mapping.rules().get(ruleIndex);
        String rulePath = path + "/" + ruleIndex;
        if (blank(rule.targetPath())) {
          return repair(binding, capture, "MAPPING_TARGET_REQUIRED", rulePath + "/targetPath",
              "Provide a target field path.");
        }
        if (blank(rule.sourcePath()) && blank(rule.expression())) {
          return repair(binding, capture, "MAPPING_VALUE_REQUIRED", rulePath,
              "Provide a source field path or an expression.");
        }
      }
    }
    RequirementBrief brief;
    try {
      brief = new RequirementBrief(
          trim(capture.goal()), capture.inputs(), constraints(approved), capture.assumptions(),
          capture.citations(), trim(capture.summary()), binding.approvedDraftReference(),
          approved.planningText(),
          approved.facts())
          .withMappingIntents(approvedMappings(approved, capture))
          .withFlow(approved.flow())
          .withCatalogBindings(approved.catalogBindings());
      brief = RequirementBriefProjector.project(brief);
      String coverageError = coverageValidator.validate(approved, brief).orElse(null);
      if (coverageError != null) {
        return repair(binding, capture, "BRIEF_COVERAGE", "/", coverageError);
      }
    } catch (IllegalArgumentException error) {
      return repair(binding, capture, "BRIEF_CONTRACT_VIOLATION", "/mappingIntents",
          error.getMessage());
    }
    if (recoveryFaultInjector.next(binding.runId(), approved.planningText(),
        "requirement-analysis").filter(code -> code == RecoveryCauseCode.CONTRACT_SHAPE)
        .isPresent()) {
      return repair(binding, capture, "INJECTED_BRIEF_REJECTION", "/capture",
          "Injected E2E requirement brief rejection.");
    }
    String message = "Requirement brief captured. Finish this turn without another capture call.";
    String accepted = captureSession.accept(
        key, brief, message, RequirementBriefTool.DUPLICATE_CAPTURE_MESSAGE);
    if (!message.equals(accepted)) {
      return rejected("DUPLICATE_CAPTURE", "/", accepted, true);
    }
    feedbackStore.clearPlan(conversationId);
    ProductCapabilityCaptureContext.offerBrief(binding, brief);
    return new Result(true, true, "HANDOFF", List.of(), message);
  }

  private Result repair(
      Binding binding, RequirementAnalysisCapture capture, String code, String path,
      String message) {
    boolean repeated = feedbackStore.recordPlanValidationFailure(
        binding.conversationId(), message, capture);
    return rejected(code, path, message, repeated);
  }

  private static List<String> constraints(RequirementDraft approved) {
    LinkedHashSet<String> constraints = new LinkedHashSet<>();
    for (RequirementFact fact : approved.facts()) {
      if (fact != null
          && (fact.polarity() == RequirementFactPolarity.NEGATIVE
              || fact.kind() == RequirementFactKind.CONSTRAINT)
          && !blank(fact.text())) {
        constraints.add(fact.text().trim());
      }
    }
    return new ArrayList<>(constraints);
  }

  private static List<MappingIntent> approvedMappings(
      RequirementDraft approved, RequirementAnalysisCapture capture) {
    if (approved.authoredDraft() == null) {
      return capture.toIntents();
    }
    List<MappingIntent> pinned = new ArrayList<>();
    for (RequirementCaptureInput.FactInput fact : approved.authoredDraft().facts()) {
      if (fact.kind() != RequirementCaptureInput.FactKind.FIELD_MAPPING) {
        continue;
      }
      RequirementCaptureInput.FieldMappingInput mapping = fact.fieldMapping();
      if (mapping == null) {
        throw new IllegalArgumentException("Approved field mapping lacks structured fields.");
      }
      pinned.add(new MappingIntent("", mapping.sourceInteractionId(), null,
          mapping.targetInteractionId(), null,
          List.of(new MappingIntentRule(mapping.sourcePath(), mapping.targetPath(),
              mapping.expression())), null));
    }
    for (MappingIntent proposal : capture.toIntents()) {
      for (MappingIntentRule rule : proposal.rules()) {
        boolean approvedRule = pinned.stream().anyMatch(intent ->
            intent.sourceRef().equals(proposal.sourceRef())
                && intent.targetRef().equals(proposal.targetRef())
                && intent.rules().contains(rule));
        if (!approvedRule) {
          throw new IllegalArgumentException(
              "Field mapping differs from the approved requirement: "
                  + proposal.sourceRef() + " -> " + proposal.targetRef() + " / "
                  + rule.targetPath());
        }
      }
    }
    return List.copyOf(pinned);
  }

  private static Result rejected(String code, String path, String message, boolean terminal) {
    return new Result(false, false, terminal ? "STOP" : "REPAIR_CAPTURE",
        List.of(new Issue(code, path, message)), message);
  }

  private String encode(Result result) {
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Cannot serialize requirement brief capture result", error);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
