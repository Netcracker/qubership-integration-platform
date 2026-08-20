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
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureRepairRunner;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.llm.agent.DiscoveryAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.CanonicalKnowledgeObject;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClientException;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextPackage;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextRequest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeFailureKind;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
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
        CapabilitySignal.Completed completed =
            completeWithBrief(context, approved, workspace, briefProducer.apply(context));
        return Multi.createFrom()
            .iterable(
                prependRunning(
                    SKILL_ID, SkillActivitySupport.wrapTerminal(SKILL_ID, List.of(completed))));
      } finally {
        SkillActivitySupport.clearParents();
        ProductCapabilityCaptureContext.unbind();
        ToolSession.clear();
      }
    }

    Multi<ChatEvent> agentStream;
    try {
      agentStream =
          ToolSession.propagateBinding(toolSessionContext, runAnalyzer(context, approved));
    } catch (KnowledgeClientException e) {
      ProductCapabilityCaptureContext.unbind();
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
                        ProductCapabilityCaptureContext.unbind();
                        ToolSession.clear();
                      }
                    })
                .onFailure()
                .recoverWithMulti(
                    error -> {
                      SkillActivitySupport.clearParents();
                      ProductCapabilityCaptureContext.unbind();
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
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.NEEDS_INPUT,
              "Requirement analysis did not capture a requirement brief"));
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
    return new CapabilitySignal.Completed(
        new StageOutcome(
            outcomeClass, List.of(candidate), "Requirement brief ready", null));
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
        buildAnalysisUserMessage(approved, context.attributeAsString("userText"))
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
    String planning = approved.planningText() == null ? "" : approved.planningText();
    StringBuilder sb = new StringBuilder();
    sb.append("Analyze the approved requirement draft and call captureRequirementBrief now.\n\n");
    if (hasPositiveServiceCall(approved)) {
      sb.append(
          "Capture typed dataMappings for every required edge around positive SERVICE_CALL facts. "
              + "Use PASS_THROUGH with no rules when the user requested no transformation. Use "
              + "EXPLICIT only for approved sourcePath and targetPath rules; never invent rules. "
              + "Reuse the sourceFactId values below as fromIntentRef and toIntentRef. Give every "
              + "PASS_THROUGH mapping at least one approved sourceFactId for provenance.\n\n");
    } else {
      sb.append(
          "Leave dataMappings empty. There are no positive SERVICE_CALL facts, so do not invent "
              + "mappings. If you still emit a mapping, it must include stage and at least one "
              + "sourceFactId.\n\n");
    }
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
            .append(fact.text())
            .append('\n');
      }
    }
    if (changeRequestText != null && !changeRequestText.isBlank()) {
      sb.append("\nChange request for this analysis turn:\n")
          .append(changeRequestText.trim())
          .append('\n');
    }
    return sb.toString();
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
