package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceEmitter;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.llm.agent.DiscoveryAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.ProposedBriefChange;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.knowledge.CanonicalKnowledgeObject;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClientException;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextPackage;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextRequest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeFailureKind;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/**
 * Product-pipeline requirement analysis. Consumes only the approved draft planning text and one
 * compiled runtime context package via {@link KnowledgeClient}; never re-reads the conversation
 * transcript.
 */
@ApplicationScoped
public class RequirementAnalysisCapability implements StageCapability {

  public static final String CAPABILITY_ID = "requirement-analysis";
  public static final String SKILL_ID = "cip-requirement-analyzer";

  private final KnowledgeClient knowledgeClient;
  private final KnowledgeContextProvider knowledgeContextProvider;
  private final RequirementBriefCoverageValidator coverageValidator;
  private final CaptureSession captureSession;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final DiscoveryAgent discoveryAgent;
  private final Function<StageExecutionContext, RequirementBrief> briefProducer;
  private final BiFunction<String, String, Multi<ChatEvent>> analysisRunner;
  private final EvidenceEmitter evidenceEmitter;
  private final RequirementDraftStore draftStore;
  private final CaptureRepairRunner captureRepairRunner;
  private final ChatMemorySanitizer chatMemorySanitizer;

  @Inject
  public RequirementAnalysisCapability(
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider knowledgeContextProvider,
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      DiscoveryAgent discoveryAgent,
      EvidenceEmitter evidenceEmitter,
      RequirementDraftStore draftStore,
      CaptureRepairRunner captureRepairRunner,
      ChatMemorySanitizer chatMemorySanitizer) {
    this(
        knowledgeClient,
        knowledgeContextProvider,
        new RequirementBriefCoverageValidator(),
        captureSession,
        feedbackStore,
        discoveryAgent,
        null,
        null,
        evidenceEmitter,
        draftStore,
        captureRepairRunner,
        chatMemorySanitizer);
  }

  /** Test helper: knowledge-only construction without analyzer agent. */
  RequirementAnalysisCapability(
      KnowledgeClient knowledgeClient, KnowledgeContextProvider knowledgeContextProvider) {
    this(
        knowledgeClient,
        knowledgeContextProvider,
        new RequirementBriefCoverageValidator(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  RequirementAnalysisCapability(
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider knowledgeContextProvider,
      RequirementBriefCoverageValidator coverageValidator,
      Function<StageExecutionContext, RequirementBrief> briefProducer) {
    this(
        knowledgeClient,
        knowledgeContextProvider,
        coverageValidator,
        null,
        null,
        null,
        briefProducer,
        null,
        null,
        null,
        null,
        null);
  }

  RequirementAnalysisCapability(
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider knowledgeContextProvider,
      RequirementBriefCoverageValidator coverageValidator,
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      DiscoveryAgent discoveryAgent,
      Function<StageExecutionContext, RequirementBrief> briefProducer,
      BiFunction<String, String, Multi<ChatEvent>> analysisRunner,
      EvidenceEmitter evidenceEmitter) {
    this(
        knowledgeClient,
        knowledgeContextProvider,
        coverageValidator,
        captureSession,
        feedbackStore,
        discoveryAgent,
        briefProducer,
        analysisRunner,
        evidenceEmitter,
        null,
        null,
        null);
  }

  RequirementAnalysisCapability(
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider knowledgeContextProvider,
      RequirementBriefCoverageValidator coverageValidator,
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      DiscoveryAgent discoveryAgent,
      Function<StageExecutionContext, RequirementBrief> briefProducer,
      BiFunction<String, String, Multi<ChatEvent>> analysisRunner,
      EvidenceEmitter evidenceEmitter,
      RequirementDraftStore draftStore) {
    this(
        knowledgeClient,
        knowledgeContextProvider,
        coverageValidator,
        captureSession,
        feedbackStore,
        discoveryAgent,
        briefProducer,
        analysisRunner,
        evidenceEmitter,
        draftStore,
        null,
        null);
  }

  RequirementAnalysisCapability(
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider knowledgeContextProvider,
      RequirementBriefCoverageValidator coverageValidator,
      CaptureSession captureSession,
      CaptureAttemptFeedbackStore feedbackStore,
      DiscoveryAgent discoveryAgent,
      Function<StageExecutionContext, RequirementBrief> briefProducer,
      BiFunction<String, String, Multi<ChatEvent>> analysisRunner,
      EvidenceEmitter evidenceEmitter,
      RequirementDraftStore draftStore,
      CaptureRepairRunner captureRepairRunner,
      ChatMemorySanitizer chatMemorySanitizer) {
    this.knowledgeClient = Objects.requireNonNull(knowledgeClient, "knowledgeClient");
    this.knowledgeContextProvider =
        Objects.requireNonNull(knowledgeContextProvider, "knowledgeContextProvider");
    this.coverageValidator = Objects.requireNonNull(coverageValidator, "coverageValidator");
    this.captureSession = captureSession;
    this.feedbackStore = feedbackStore;
    this.discoveryAgent = discoveryAgent;
    this.briefProducer = briefProducer;
    this.analysisRunner = analysisRunner;
    this.evidenceEmitter = evidenceEmitter;
    this.draftStore = draftStore;
    this.captureRepairRunner = captureRepairRunner;
    this.chatMemorySanitizer = chatMemorySanitizer;
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    RequirementDraft approved = resolveApprovedDraft(context);
    if (approved == null || !approved.readyForPlan()) {
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(
                      StageOutcomeClass.MISSING_MANDATORY_INPUT,
                      "Approved requirement draft is required for analysis")));
    }

    // Sidecar RestClient is blocking — must not run on the Vert.x event loop.
    return Uni.createFrom()
        .voidItem()
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
        .onItem()
        .transformToMulti(ignored -> continueAfterKnowledge(context, approved));
  }

  private Multi<CapabilitySignal> continueAfterKnowledge(
      StageExecutionContext context, RequirementDraft approved) {
    // Mirror CompilerSkillRuntime.clearCaptureState: re-runs after change-request must free the
    // one-shot REQUIREMENT_BRIEF slot, or the second captureRequirementBrief kills the SSE stream.
    clearAnalysisCaptureState(context.conversationId());

    ToolSession.bind(context.conversationId());
    Context toolSessionContext = ToolSession.attachedContext();

    SkillWorkspace workspace = seedWorkspaceWithApprovedDraftOnly(approved);
    AtomicReference<RequirementBrief> captured = new AtomicReference<>();
    ProductCapabilityCaptureContext.bindAnalysis(
        context.runId(),
        context.conversationId(),
        approved,
        payload -> {
          if (payload instanceof RequirementBrief brief) {
            captured.set(brief);
          }
        });

    if (briefProducer != null) {
      try {
        SkillActivitySupport.bindParents(SKILL_ID);
        RequirementBrief produced = briefProducer.apply(context);
        CapabilitySignal.Completed completed =
            completeWithBrief(context, approved, workspace, produced);
        List<CapabilitySignal> terminal = new java.util.ArrayList<>();
        if (StageRepairEvidence.isRepairTurn(context)
            && completed.outcome().outcomeClass() == StageOutcomeClass.CANDIDATE) {
          terminal.add(new CapabilitySignal.Message(repairChangeSummary(context, produced)));
        }
        terminal.add(completed);
        return Multi.createFrom()
            .iterable(
                prependRunning(
                    SKILL_ID, SkillActivitySupport.wrapTerminal(SKILL_ID, List.copyOf(terminal))));
      } finally {
        SkillActivitySupport.clearParents();
        ProductCapabilityCaptureContext.unbind(context.conversationId());
        ToolSession.clear();
      }
    }

    Multi<ChatEvent> agentStream;
    try {
      agentStream =
          ToolSession.propagateBinding(toolSessionContext, runAnalyzer(context, approved));
    } catch (KnowledgeClientException e) {
      ProductCapabilityCaptureContext.unbind(context.conversationId());
      ToolSession.clear();
      StageOutcomeClass outcomeClass =
          e.kind() == KnowledgeFailureKind.KNOWLEDGE_NOT_FOUND
              ? StageOutcomeClass.CONTRACT_FAILURE
              : StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE;
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(
                      outcomeClass, "Knowledge resolution failed: " + e.getMessage())));
    }
    SkillActivitySupport.bindParents(SKILL_ID);
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(SKILL_ID)),
            agentStream
                .onItem()
                .transform(
                    event -> (CapabilitySignal) new CapabilitySignal.Message(tokenText(event)))
                .onCompletion()
                .switchTo(
                    () -> {
                      try {
                        RequirementBrief brief =
                            captured.get() != null
                                ? captured.get()
                                : captureSession != null
                                    ? captureSession
                                        .get(
                                            CaptureKey.conversation(
                                                CaptureSlot.REQUIREMENT_BRIEF,
                                                context.conversationId()),
                                            RequirementBrief.class)
                                        .orElse(null)
                                    : null;
                        return Multi.createFrom()
                            .iterable(
                                SkillActivitySupport.wrapTerminal(
                                    SKILL_ID,
                                    List.of(
                                        completeWithBrief(
                                            context, approved, workspace, brief))));
                      } finally {
                        SkillActivitySupport.clearParents();
                        ProductCapabilityCaptureContext.unbind(context.conversationId());
                        ToolSession.clear();
                      }
                    })
                .onFailure()
                .recoverWithMulti(
                    error -> {
                      SkillActivitySupport.clearParents();
                      ProductCapabilityCaptureContext.unbind(context.conversationId());
                      ToolSession.clear();
                      return Multi.createFrom()
                          .items(
                              SkillActivitySupport.error(SKILL_ID),
                              new CapabilitySignal.Completed(
                                  StageOutcome.of(
                                      StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                                      error.getMessage() == null
                                          ? "requirement analysis failed"
                                          : error.getMessage())));
                    }));
  }

  private static List<CapabilitySignal> prependRunning(
      String skillId, List<CapabilitySignal> signals) {
    List<CapabilitySignal> out = new java.util.ArrayList<>(signals.size() + 1);
    out.add(SkillActivitySupport.running(skillId));
    out.addAll(signals);
    return List.copyOf(out);
  }

  /**
   * Clears conversation-scoped analysis capture before each execution, matching the compiler skill
   * re-entry protocol. Discovery already resets via {@code draftStore.beginTurn}; planning clears
   * through {@code CompilerSkillRuntime.clearCaptureState}.
   */
  void clearAnalysisCaptureState(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    if (feedbackStore != null) {
      feedbackStore.clearPlan(conversationId);
    }
    if (captureSession != null) {
      captureSession.clear(
          CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, conversationId));
    }
  }

  private CapabilitySignal.Completed completeWithBrief(
      StageExecutionContext context,
      RequirementDraft approved,
      SkillWorkspace workspace,
      RequirementBrief brief) {
    if (brief == null) {
      if (feedbackStore != null) {
        var lastFailure = feedbackStore.lastPlanFailure(context.conversationId());
        if (lastFailure.isPresent() && lastFailure.get().kind() == CaptureFailureKind.VALIDATION) {
          return new CapabilitySignal.Completed(
              StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, lastFailure.get().summary()));
        }
      }
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.NEEDS_INPUT,
              "Requirement analysis did not capture a requirement brief"));
    }
    var unresolvedMapping = BriefMappingValidator.unresolvedRequiredMessage(brief);
    if (unresolvedMapping.isPresent()) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, unresolvedMapping.get()));
    }
    var coverageError = coverageValidator.validate(approved, brief);
    if (coverageError.isPresent()) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.CONTRACT_FAILURE,
              "Requirement brief coverage failed: " + coverageError.get()));
    }
    // Workspace seed is planning text only — never conversation history.
    String seeded =
        workspace
            .get(SkillArtifactType.RAW_USER_REQUEST)
            .map(a -> ((SkillArtifactPayload.RawUserRequestPayload) a.payload()).effectiveText())
            .orElse("");
    if (!seeded.equals(approved.planningText())) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.CONTRACT_FAILURE,
              "Analysis workspace must be seeded with approved draft planning text only"));
    }
    ArtifactCandidate candidate =
        new ArtifactCandidate(
            CompilationArtifacts.Kind.REQUIREMENT_BRIEF, brief, context.inputRefs());
    // create-chain gates on brief approval (CANDIDATE); legacy planning advances (SUCCEEDED).
    StageOutcomeClass outcomeClass =
        stageRequiresApproval(context)
            ? StageOutcomeClass.CANDIDATE
            : StageOutcomeClass.SUCCEEDED;
    String message =
        StageRepairEvidence.isRepairTurn(context)
            ? "Requirement brief updated. Approve to rebuild the plan."
            : "Requirement brief ready";
    return new CapabilitySignal.Completed(
        new StageOutcome(outcomeClass, List.of(candidate), message, null));
  }

  /** True when the active profile stage declares an approval gate for this capability run. */
  static boolean stageRequiresApproval(StageExecutionContext context) {
    if (context == null || context.profile() == null || context.stageId() == null) {
      return false;
    }
    return context.profile().stages().stream()
        .anyMatch(
            stage ->
                context.stageId().equals(stage.stageId()) && stage.approval() != null);
  }

  static SkillWorkspace seedWorkspaceWithApprovedDraftOnly(RequirementDraft approved) {
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("requirement-analysis");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "requirement-analysis",
            new SkillArtifactPayload.RawUserRequestPayload(
                approved.planningText(), List.of())));
    return workspace;
  }

  private Multi<ChatEvent> runAnalyzer(StageExecutionContext context, RequirementDraft approved) {
    KnowledgeContextPackage contextPackage =
        knowledgeClient.context(
            knowledgeContextProvider.forConversation(context.conversationId()),
            new KnowledgeContextRequest(
                approved.planningText(),
                SKILL_ID,
                "DISCOVERY",
                List.of(),
                12,
                20_000));
    String userMessage =
        buildAnalysisUserMessage(
                approved,
                context.attributeAsString("userText"),
                responseLocale(context),
                repairEvidence(context))
            + "\n\n"
            + contextPackage.renderMarkdown();
    if (evidenceEmitter != null) {
      evidenceEmitter.knowledge(
          context.conversationId(),
          contextPackage.identity().packageRef(),
          contextPackage.objects().stream()
              .map(CanonicalKnowledgeObject::id)
              .toList(),
          contextPackage.contentChars());
    }
    java.util.function.Function<String, Multi<String>> agentChat =
        message -> {
          // Quarkus LangChain4j treats @UserMessage as a PromptTemplate/Qute string. Escape each
          // initial or repair message immediately before the agent call.
          String safeMessage = QuteUserMessageEscaping.escapeForAiServiceUserMessage(message);
          if (analysisRunner != null) {
            return analysisRunner
                .apply(context.conversationId(), safeMessage)
                .onItem()
                .transform(RequirementAnalysisCapability::tokenText);
          }
          if (discoveryAgent == null) {
            return Multi.createFrom().empty();
          }
          return discoveryAgent.chat(context.conversationId(), safeMessage);
        };
    if (captureRepairRunner == null || captureSession == null || feedbackStore == null) {
      return agentChat.apply(userMessage).onItem().transform(ChatEvent::token);
    }
    CaptureKey briefKey =
        CaptureKey.conversation(CaptureSlot.REQUIREMENT_BRIEF, context.conversationId());
    return captureRepairRunner
        .runWithRepair(
            agentChat,
            () -> captureSession.isPresent(briefKey),
            () -> feedbackStore.lastPlanFailure(context.conversationId()),
            () -> repairDanglingToolCalls(context.conversationId()),
            "captureRequirementBrief",
            userMessage,
            true,
            null,
            () -> repairDanglingToolCalls(context.conversationId()),
            1)
        .onItem()
        .transform(ChatEvent::token);
  }

  private void repairDanglingToolCalls(String conversationId) {
    if (chatMemorySanitizer == null) {
      return;
    }
    String feedback =
        feedbackStore == null
            ? null
            : feedbackStore
                .lastPlanFailure(conversationId)
                .map(
                    org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback
                        ::summary)
                .orElse(null);
    chatMemorySanitizer.repairDanglingToolCalls(conversationId, feedback);
  }

  static String buildAnalysisUserMessage(RequirementDraft approved) {
    return buildAnalysisUserMessage(approved, null);
  }

  static String buildAnalysisUserMessage(RequirementDraft approved, String changeRequestText) {
    return buildAnalysisUserMessage(approved, changeRequestText, "en", null);
  }

  static String buildAnalysisUserMessage(
      RequirementDraft approved, String changeRequestText, String responseLocale) {
    return buildAnalysisUserMessage(approved, changeRequestText, responseLocale, null);
  }

  static String buildAnalysisUserMessage(
      RequirementDraft approved,
      String changeRequestText,
      String responseLocale,
      BriefRepairEvidence repair) {
    String planning = approved.planningText() == null ? "" : approved.planningText();
    StringBuilder sb = new StringBuilder();
    if (repair != null && repair.hasEvidence()) {
      sb.append(
          "Repair the previously approved requirement brief so it addresses the halt findings. "
              + "Call captureRequirementBrief with the updated brief. Do not restart discovery "
              + "from scratch.\n\n");
      sb.append(
          "Response language rule: after capture, summarize the changes you made in pinned "
              + "response locale "
              + normalizedLocale(responseLocale)
              + ". Start with what you added or updated. Mention that approving rebuilds the "
              + "plan. This locale is authoritative; do not infer another language from Planning "
              + "text, conversation history, approval controls, tool output, or this English "
              + "instruction.\n\n");
      appendRepairEvidence(sb, repair);
    } else {
      sb.append("Analyze the approved requirement draft and call captureRequirementBrief now.\n\n");
      sb.append(
          "Response language rule: after capture, summarize in pinned response locale "
              + normalizedLocale(responseLocale)
              + ". This locale is authoritative; do not infer another language from Planning text, "
              + "conversation history, approval controls, tool output, or this English instruction.\n\n");
    }
    if (hasPositiveServiceCall(approved)) {
      sb.append(
          "Do not invent dataMappings or mappingIntents for trigger-to-call edges. "
              + "Pass-through is the absence of a mapping intent. Capture mappingIntents only "
              + "when the user requested field adaptation with explicit sourcePath and targetPath "
              + "rules. Never invent rules.\n\n");
    } else {
      sb.append(
          "Leave mappingIntents and dataMappings empty. There are no positive SERVICE_CALL "
              + "facts, so do not invent mappings.\n\n");
    }
    sb.append(
        "Fact identity the later DERIVE step copies as-is (named fields, not text):\n"
            + "- ENDPOINT capabilityKey is the CIP trigger type (http-trigger, async-api-trigger,"
            + " or kafka-trigger-2).\n"
            + "- HTTP ENDPOINT: set httpMethod and path; operation is the optional operation id.\n"
            + "- Catalog Kafka consume: capabilityKey async-api-trigger; set participant,"
            + " operation, and serviceCallId.\n"
            + "- Native Kafka consume: capabilityKey kafka-trigger-2; set topic and operation.\n"
            + "- SERVICE_CALL: set participant and operation. text is a description only.\n\n");
    sb.append("Planning text:\n").append(planning).append('\n');
    if (!approved.facts().isEmpty()) {
      sb.append(
          "\nApproved facts (server pins these sourceFactId values on capture — still include"
              + " goal/summary/inputs/constraints):\n");
      for (var fact : approved.facts()) {
        sb.append("- id=")
            .append(fact.sourceFactId())
            .append(" [")
            .append(fact.polarity())
            .append('/')
            .append(fact.kind())
            .append("] ")
            .append(fact.text());
        appendFactIdentity(sb, fact);
        sb.append('\n');
      }
    }
    if (changeRequestText != null && !changeRequestText.isBlank()) {
      sb.append("\nChange request for this analysis turn:\n")
          .append(changeRequestText.trim())
          .append('\n');
    }
    return sb.toString();
  }

  private static void appendFactIdentity(StringBuilder sb, RequirementFact fact) {
    appendNamedField(sb, "capabilityKey", fact.capabilityKey());
    appendNamedField(sb, "participant", fact.participant());
    appendNamedField(sb, "operation", fact.operation());
    appendNamedField(sb, "topic", fact.topic());
    appendNamedField(sb, "httpMethod", fact.httpMethod());
    appendNamedField(sb, "path", fact.path());
  }

  private static void appendNamedField(StringBuilder sb, String name, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    sb.append(' ').append(name).append('=').append(value.trim());
  }

  private static void appendRepairEvidence(StringBuilder sb, BriefRepairEvidence repair) {
    sb.append("Halt repair evidence:\n");
    if (repair.outcomeClass() != null && !repair.outcomeClass().isBlank()) {
      sb.append("- outcomeClass: ").append(repair.outcomeClass().trim()).append('\n');
    }
    if (repair.failedStageId() != null && !repair.failedStageId().isBlank()) {
      sb.append("- failedStageId: ").append(repair.failedStageId().trim()).append('\n');
    }
    if (repair.findings() != null && !repair.findings().isBlank()) {
      sb.append("- validationFindings:\n").append(repair.findings().trim()).append('\n');
    }
    if (repair.errorEvidence() != null && !repair.errorEvidence().isBlank()) {
      sb.append("- errorEvidence:\n").append(repair.errorEvidence().trim()).append('\n');
    }
    if (repair.haltFollowUpText() != null && !repair.haltFollowUpText().isBlank()) {
      sb.append("- haltFollowUpText: ").append(repair.haltFollowUpText().trim()).append('\n');
    }
    if (repair.recoveryEvidenceRef() != null && !repair.recoveryEvidenceRef().isBlank()) {
      sb.append("- recoveryEvidenceRef: ").append(repair.recoveryEvidenceRef().trim()).append('\n');
    }
    if (!repair.proposedBriefChanges().isEmpty()) {
      sb.append(
          "Proposed brief corrections (do not invent values when authorDecisionRequired is"
              + " true):\n");
      for (ProposedBriefChange change : repair.proposedBriefChanges()) {
        appendProposedBriefChange(sb, change);
      }
    }
    if (repair.priorBriefText() != null && !repair.priorBriefText().isBlank()) {
      sb.append("\nPrior requirement brief:\n")
          .append(repair.priorBriefText().trim())
          .append("\n\n");
    } else {
      sb.append('\n');
    }
  }

  private static void appendProposedBriefChange(StringBuilder sb, ProposedBriefChange change) {
    if (change == null) {
      return;
    }
    sb.append("- field=").append(nullToEmpty(change.field()));
    sb.append(" previousValue=").append(nullToEmpty(change.previousValue()));
    sb.append(" proposedValue=").append(nullToEmpty(change.proposedValue()));
    sb.append(" authorDecisionRequired=").append(change.authorDecisionRequired());
    if (change.sourceFactId() != null && !change.sourceFactId().isBlank()) {
      sb.append(" sourceFactId=").append(change.sourceFactId().trim());
    }
    if (change.findingCode() != null && !change.findingCode().isBlank()) {
      sb.append(" findingCode=").append(change.findingCode().trim());
    }
    sb.append('\n');
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** Adds the prior brief text to the shared halt evidence; the brief-specific part of a repair. */
  static BriefRepairEvidence repairEvidence(StageExecutionContext context) {
    StageRepairEvidence shared = StageRepairEvidence.from(context);
    if (shared == null) {
      return null;
    }
    RequirementBrief prior = priorBrief(context);
    return new BriefRepairEvidence(
        shared.outcomeClass(),
        shared.failedStageId(),
        shared.findings(),
        shared.errorEvidence(),
        shared.haltFollowUpText(),
        shared.recoveryEvidenceRef(),
        proposedBriefChanges(context),
        prior == null ? "" : RequirementBriefText.format(prior));
  }

  private static List<ProposedBriefChange> proposedBriefChanges(StageExecutionContext context) {
    if (context == null || context.attributes() == null) {
      return List.of();
    }
    Object value = context.attributes().get(ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR);
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    List<ProposedBriefChange> changes = new java.util.ArrayList<>();
    for (Object entry : values) {
      if (entry instanceof ProposedBriefChange change) {
        changes.add(change);
      }
    }
    return List.copyOf(changes);
  }

  private static RequirementBrief priorBrief(StageExecutionContext context) {
    Object attribute = context.attributes().get("requirementBrief");
    if (attribute instanceof RequirementBrief brief) {
      return brief;
    }
    return null;
  }

  static String repairChangeSummary(StageExecutionContext context, RequirementBrief repaired) {
    BriefRepairEvidence evidence = repairEvidence(context);
    StringBuilder sb = new StringBuilder();
    sb.append("I updated the requirement brief to address the earlier failure");
    if (evidence != null
        && evidence.findings() != null
        && !evidence.findings().isBlank()) {
      sb.append(" (").append(firstFindingHint(evidence.findings())).append(')');
    } else if (evidence != null
        && evidence.errorEvidence() != null
        && !evidence.errorEvidence().isBlank()) {
      sb.append(" (").append(firstFindingHint(evidence.errorEvidence())).append(')');
    }
    if (evidence != null) {
      boolean appendedProposal = false;
      for (ProposedBriefChange change : evidence.proposedBriefChanges()) {
        if (change.field() == null || change.field().isBlank()) {
          continue;
        }
        appendedProposal = true;
        sb.append(" Proposed ")
            .append(change.field().trim())
            .append(": ")
            .append(nullToEmpty(change.previousValue()))
            .append(" -> ")
            .append(nullToEmpty(change.proposedValue()))
            .append('.');
      }
      if (!appendedProposal) {
        sb.append('.');
      }
    } else {
      sb.append('.');
    }
    if (repaired != null && repaired.goal() != null && !repaired.goal().isBlank()) {
      sb.append(" Updated goal: ").append(repaired.goal().trim()).append('.');
    }
    sb.append(" If you approve, the plan will be rebuilt.");
    return sb.toString();
  }

  private static String firstFindingHint(String text) {
    String trimmed = text.trim();
    int newline = trimmed.indexOf('\n');
    String first = newline < 0 ? trimmed : trimmed.substring(0, newline).trim();
    return first.length() <= 160 ? first : first.substring(0, 160);
  }

  /** Structured halt evidence injected into the analysis repair turn. */
  record BriefRepairEvidence(
      String outcomeClass,
      String failedStageId,
      String findings,
      String errorEvidence,
      String haltFollowUpText,
      String recoveryEvidenceRef,
      List<ProposedBriefChange> proposedBriefChanges,
      String priorBriefText) {

    BriefRepairEvidence {
      proposedBriefChanges =
          proposedBriefChanges == null ? List.of() : List.copyOf(proposedBriefChanges);
    }

    boolean hasEvidence() {
      return (errorEvidence != null && !errorEvidence.isBlank())
          || (findings != null && !findings.isBlank())
          || !proposedBriefChanges.isEmpty();
    }
  }

  private static String normalizedLocale(String responseLocale) {
    return responseLocale == null || responseLocale.isBlank() ? "en" : responseLocale.trim();
  }

  private static String responseLocale(StageExecutionContext context) {
    return context.runManifest() == null ? "en" : context.runManifest().responseLocale();
  }

  private static boolean hasPositiveServiceCall(RequirementDraft approved) {
    return approved.facts().stream()
        .anyMatch(
            fact ->
                fact != null
                    && fact.polarity() == RequirementFactPolarity.POSITIVE
                    && fact.kind() == RequirementFactKind.SERVICE_CALL);
  }

  private RequirementDraft resolveApprovedDraft(StageExecutionContext context) {
    // Prefer draft store: specification-import mutates the draft in place (ADR 0001 SoT).
    if (draftStore != null) {
      RequirementDraft fromStore = draftStore.get(context.conversationId()).orElse(null);
      if (fromStore != null) {
        return fromStore;
      }
    }
    Object fromAttribute = context.attributes().get("approvedDraft");
    if (fromAttribute instanceof RequirementDraft draft) {
      return draft;
    }
    return ProductCapabilityCaptureContext.approvedDraft().orElse(null);
  }

  private static String tokenText(ChatEvent event) {
    if (event instanceof ChatEvent.Token token) {
      return token.text() == null ? "" : token.text();
    }
    return "";
  }
}
