package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.quarkus.arc.Arc;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutor;
import io.quarkiverse.langchain4j.runtime.tool.QuarkusToolExecutorFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFeedbackChannel;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Binding;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.Mode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.llm.tool.StructuredCaptureArguments;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryFaultInjector;

/** Captures a product brief while keeping selected requirements under server ownership. */
@ApplicationScoped
public class ProductRequirementBriefTool {

  /** Dynamic tool registration allows stateless tools on Quarkus LangChain4j 1.12.2. */
  @ApplicationScoped
  public static class ProviderSupplier implements java.util.function.Supplier<ToolProvider> {
    @Override
    public ToolProvider get() {
      return request -> {
        var tool = Arc.container().instance(ProductRequirementBriefTool.class).get();
        var factory = Arc.container().instance(QuarkusToolExecutorFactory.class).get();
        var method = ToolsRecorder.getMetadata().get(ProductRequirementBriefTool.class.getName())
            .stream().filter(info -> "captureRequirementBrief".equals(info.methodName()))
            .findFirst().orElseThrow();
        var executor = factory.create(new QuarkusToolExecutor.Context(tool, method.invokerClassName(),
            method.methodName(), method.argumentMapperClassName(), method.executionModel(),
            method.returnBehavior(), false, method));
        return ToolProviderResult.builder().add(specification(method), executor).build();
      };
    }
  }

  static dev.langchain4j.agent.tool.ToolSpecification specification(
      io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo method) {
    var original = method.toolSpecification();
    var root = original.parameters();
    var capture = (dev.langchain4j.model.chat.request.json.JsonObjectSchema) root.properties().get("capture");
    var required = capture.toBuilder().required(List.of("goal", "summary", "inputs", "assumptions", "citations"))
        .build();
    return original.toBuilder().parameters(root.toBuilder().addProperty("capture", required)
        .required(List.of("capture")).build()).build();
  }

  public record Issue(String code, String path, String message) {}

  public record Result(
      boolean accepted, boolean changed, String nextAction, List<Issue> issues, String message) {}

  private final CaptureSession captureSession;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final RequirementBriefCoverageValidator coverageValidator;
  private final ObjectMapper mapper;
  private final E2eRecoveryFaultInjector recoveryFaultInjector;
  private final CaptureToolOutcomeGateway outcomeGateway;
  private org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;

  @Inject
  ProductRequirementBriefTool(
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      ObjectMapper mapper,
      E2eRecoveryFaultInjector recoveryFaultInjector,
      CaptureToolOutcomeGateway outcomeGateway,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this(captureSession, feedbackStore, new RequirementBriefCoverageValidator(), mapper,
        recoveryFaultInjector, outcomeGateway);
    this.conversations = conversations;
  }

  ProductRequirementBriefTool(
      CaptureSession captureSession, CaptureAttemptFeedbackStore feedbackStore,
      ObjectMapper mapper, E2eRecoveryFaultInjector recoveryFaultInjector) {
    this(captureSession, feedbackStore, new RequirementBriefCoverageValidator(), mapper, recoveryFaultInjector,
        new CaptureToolOutcomeGateway(new ToolCallFingerprintStore(mapper), feedbackStore));
  }

  ProductRequirementBriefTool(
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      RequirementBriefCoverageValidator coverageValidator,
      ObjectMapper mapper,
      E2eRecoveryFaultInjector recoveryFaultInjector,
      CaptureToolOutcomeGateway outcomeGateway) {
    this.captureSession = captureSession;
    this.feedbackStore = feedbackStore;
    this.coverageValidator = coverageValidator;
    this.mapper = mapper;
    this.recoveryFaultInjector = recoveryFaultInjector;
    this.outcomeGateway = outcomeGateway;
  }

  @Tool("""
      Capture one requirement brief from the selected draft. The server owns its facts, flow,
      catalog bindings, constraints, and draft text. Supply a goal, readable summary, inputs,
      assumptions, and relevant knowledge citations. For authored FIELD_MAPPING facts, omit
      mappingIntents; the server projects those approved rules. For catalog imports without
      authored mapping facts, use approved interaction ids as mapping endpoints. Omit rows for
      pass-through. The server assigns mapping ids and ports. After acceptance, finish this turn.
      """)
  public String captureRequirementBrief(
      RequirementAnalysisCapture capture, @ToolMemoryId String conversationId,
      InvocationParameters parameters) {
    return withAuthority(conversationId, parameters, binding -> {
          Result result = capture(capture, conversationId);
          if (result.accepted()) {
            outcomeGateway.onTerminalAccept(result.message());
          }
          if ("STOP".equals(result.nextAction())) {
            outcomeGateway.onTerminalDuplicate(result.message());
          }
          outcomeGateway.endInvocation(encode(result));
          return null;
        });
  }

  public String rejectArguments(
      String conversationId, InvocationParameters parameters, StructuredCaptureArguments.Issue issue) {
    return withAuthority(conversationId, parameters, binding -> {
          String message = issue.code() + " at " + issue.path() + ": " + issue.message();
          failure(binding, CaptureFailureKind.TOOL_ARGUMENTS, message);
          outcomeGateway.endInvocation(message);
          return null;
        });
  }

  private String withAuthority(String conversationId, InvocationParameters parameters,
      java.util.function.Function<Binding, String> action) {
    Binding expected = parameters == null ? null : parameters.get(org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.CONTEXT_KEY);
    java.util.function.Supplier<String> capture = () -> ProductCapabilityCaptureContext.withBinding(
        conversationId, parameters, Mode.ANALYSIS, action);
    return conversations != null && expected != null && expected.resetGeneration() != null
        ? conversations.archive().withAuthority(conversationId, expected.resetGeneration(),
            expected.sourceTurnId(), capture) : capture.get();
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
    failure(binding, CaptureFailureKind.TOOL_ARGUMENTS, message);
    return encode(rejected(issue.code(), issue.path(), issue.message(), false));
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
      return rejected("SELECTED_DRAFT_REQUIRED", "/", "A selected draft is required.", true);
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
    if (blank(capture.goal()) || blank(capture.summary())) {
      return repair(binding, capture, "MISSING_SUMMARY", "/summary",
          "Provide both a goal and a summary.");
    }
    if (capture.inputs().isEmpty() && approved.flow().interactions().stream()
        .anyMatch(interaction -> interaction.direction()
            == org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND)) {
      return repair(binding, capture, "INPUT_REQUIRED", "/inputs",
          "Describe each selected inbound interaction in inputs.");
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
          .withCatalogBindings(approved.catalogBindings())
          .withTargetFolderPath(targetFolderPath(approved));
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

  private static String targetFolderPath(RequirementDraft approved) {
    return approved.authoredDraft() == null
        ? null : approved.authoredDraft().settings().targetFolderPath();
  }

  private Result repair(
      Binding binding, RequirementAnalysisCapture capture, String code, String path,
      String message) {
    failure(binding, CaptureFailureKind.VALIDATION, code + " at " + path + ": " + message);
    return rejected(code, path, message, false);
  }

  private void failure(Binding binding, CaptureFailureKind kind, String message) {
    outcomeGateway.onFailure(CaptureFeedbackChannel.PLAN, binding.conversationId(),
        binding.runId() + ":" + binding.attemptId() + ":" + binding.approvedDraftReference(), kind,
        CaptureFailureClass.CORRECTABLE, "captureRequirementBrief", null, message);
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
