package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import io.smallrye.mutiny.Context;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore.RequirementRejection;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsAgentCall;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.plan.workdocument.flow.WorkLogicalFlow;
import org.qubership.integration.platform.ai.plan.RequirementDiscoveryDirective;
import org.qubership.integration.platform.ai.plan.RequirementFlowValidator;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/**
 * Product-pipeline requirement discovery stage. Uses brainstorming capture only; analysis never
 * receives the raw transcript from this capability.
 */
@ApplicationScoped
public class RequirementDiscoveryCapability implements StageCapability {

  public static final String CAPABILITY_ID = "requirement-discovery";

  private final GatherRequirementsAgent gatherRequirementsAgent;
  private final RequirementDraftStore draftStore;
  private final GatherRequirementsPromptBuilder promptBuilder;
  private final BiFunction<String, String, Multi<ChatEvent>> gatherRunner;
  private final ConversationService conversationService;
  private final CatalogBindingMatcher catalogBindingMatcher;
  @Inject CaptureAttemptFeedbackStore captureFeedback;

  @Inject
  public RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder,
      ConversationService conversationService,
      CatalogBindingMatcher catalogBindingMatcher) {
    this(
        gatherRequirementsAgent,
        draftStore,
        promptBuilder,
        null,
        conversationService,
        catalogBindingMatcher);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder) {
    this(gatherRequirementsAgent, draftStore, promptBuilder, null, null, null);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner) {
    this(gatherRequirementsAgent, draftStore, null, gatherRunner, null, null);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner) {
    this(gatherRequirementsAgent, draftStore, promptBuilder, gatherRunner, null, null);
  }

  public RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner,
      CatalogBindingMatcher catalogBindingMatcher) {
    this(gatherRequirementsAgent, draftStore, null, gatherRunner, null, catalogBindingMatcher);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner,
      ConversationService conversationService) {
    this(
        gatherRequirementsAgent,
        draftStore,
        promptBuilder,
        gatherRunner,
        conversationService,
        null);
  }

  RequirementDiscoveryCapability(
      GatherRequirementsAgent gatherRequirementsAgent,
      RequirementDraftStore draftStore,
      GatherRequirementsPromptBuilder promptBuilder,
      BiFunction<String, String, Multi<ChatEvent>> gatherRunner,
      ConversationService conversationService,
      CatalogBindingMatcher catalogBindingMatcher) {
    this.gatherRequirementsAgent = gatherRequirementsAgent;
    this.draftStore = Objects.requireNonNull(draftStore, "draftStore");
    this.promptBuilder = promptBuilder;
    this.gatherRunner = gatherRunner;
    this.conversationService = conversationService;
    this.catalogBindingMatcher = catalogBindingMatcher;
  }

  /** True when discovery can resolve a missing binding from clarification text. */
  public boolean canResolveMissingBindings() {
    return catalogBindingMatcher != null;
  }

  /**
   * Matches clarification text against the local catalog. Exact uses the answer as both service
   * name and operation query. {@link CatalogBindingMatcher.MatchResult.None} when the matcher is
   * absent or the text is blank.
   */
  public CatalogBindingMatcher.MatchResult matchMissingInteraction(
      String conversationId, String answer) {
    if (catalogBindingMatcher == null || answer == null || answer.isBlank()) {
      return new CatalogBindingMatcher.MatchResult.None();
    }
    String name = answer.trim();
    return catalogBindingMatcher.match("service-call", name, name, conversationId);
  }

  /**
   * Resolves an unambiguous catalog name for a missing interaction. Empty when the matcher is
   * absent or the name is not a single catalog operation.
   */
  public Optional<CatalogBindingHint> bindMissingInteraction(
      String conversationId, String interactionId, String answer) {
    if (catalogBindingMatcher == null
        || interactionId == null
        || interactionId.isBlank()
        || answer == null
        || answer.isBlank()) {
      return Optional.empty();
    }
    String name = answer.trim();
    CatalogBindingMatcher.MatchResult result =
        matchMissingInteraction(conversationId, answer);
    if (!(result instanceof CatalogBindingMatcher.MatchResult.Exact exact)) {
      return Optional.empty();
    }
    String operation =
        exact.match().operationName() == null || exact.match().operationName().isBlank()
            ? name
            : exact.match().operationName();
    RequirementFlow.Interaction interaction =
        new RequirementFlow.Interaction(
            interactionId.trim(),
            RequirementFlow.Direction.OUTBOUND,
            name,
            operation,
            "");
    return Optional.of(
        CatalogBindingHint.from(interaction, exact.match(), "catalog", Instant.now()));
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  /** Opens a logical repair for a contradiction found during discovery. Profile wiring is later. */
  public static WorkTaskScope logicalRepairScope(WorkDocumentState state, String defectRecordId) {
    return WorkLogicalFlow.repairScope(state, defectRecordId);
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    String conversationId = context.conversationId();
    String userMessage =
        context.attributeAsString("userText") == null ? "" : context.attributeAsString("userText");
    RequirementDraft mappingRepairDraft = mappingRepairDraft(context);
    if (mappingRepairDraft != null) {
      var repair = StageRepairEvidence.from(context);
      userMessage = """
          Repair the selected draft's field paths using the catalog contract findings below.
          Read the accepted draft, then call updateRequirementDraft for the affected FIELD_MAPPING
          facts. Preserve all fact IDs, text, expressions, source and target interactions, flow,
          settings, and unrelated requirements. Change only the invalid field paths.
          Paths are relative to the actual message body. An operation name, resource name, or schema
          title is not a JSON wrapper. For example, a schema with root property Subject requires
          targetPath Subject; use Task.Subject only if its properties actually contain Task.Subject.
          Do not invent a field or remove its mapping to make validation pass. If the contract does
          not determine the correction, ask for clarification. After saving, finish with CONTINUE
          so analysis can present a new brief for approval.
          Contract findings:
          %s
          %s
          """.formatted(repair.findings(), repair.errorEvidence());
      draftStore.restoreAccepted(conversationId, mappingRepairDraft);
    }
    var repair = StageRepairEvidence.from(context);
    if (repair != null && ChatEvent.CONTINUE_TO_PLANNING_ACTION.equals(userMessage.strip())) {
      userMessage = "";
    }
    if (userMessage.isBlank() && repair != null
        && (RecoveryCauseCode.MISSING_BRIEF_FACTS.name().equals(repair.causeCode())
            || RecoveryCauseCode.BRIEF_CONTRACT.name().equals(repair.causeCode()))) {
      userMessage = """
          Correct the reported missing or contradictory fields in the accepted draft. Call readRequirementSource to read
          the saved author messages in order, including later corrections, then read the accepted
          draft. Call updateRequirementDraft only for changes supported by those messages. Preserve
          unrelated facts, IDs, flow, settings, and confirmed policy choices. Ask for clarification
          only if the saved source leaves a required value absent or a choice unresolved. After
          saving, finish with CONTINUE so analysis can present a new brief for approval.
          Repair findings:
          %s
          %s
          Proposed corrections:
          %s
          """.formatted(repair.findings(), repair.errorEvidence(),
              context.attributes().getOrDefault(ProductPipelineRunSupport.PROPOSED_BRIEF_CHANGES_ATTR, List.of()));
    }
    if (userMessage.isBlank() && repair != null) {
      boolean clean = context.attributeAsString(ProductPipelineRunSupport.PRODUCER_CLEAN_REBUILD_EVIDENCE_REF_ATTR) != null;
      userMessage = """
          Correct the generated requirement draft using the saved author messages and accepted facts.
          Read readRequirementSource and the current draft. Preserve confirmed choices, exact values,
          fact IDs, flow, and unrelated requirements. A capture/schema failure is a producer error;
          ask the author only if the saved source leaves a required value or product choice unresolved.
          Strategy: %s
          Cause: %s
          Findings:
          %s
          %s
          """.formatted(clean ? "Generate a complete draft in this fresh turn from the saved source."
              : "Correct the affected capture fields from the saved evidence.",
              repair.causeCode(), repair.findings(), repair.errorEvidence());
    }
    if (userMessage.isBlank() && conversationService != null && context.inputRefs().stream()
        .anyMatch(ref -> ref.kind() == CompilationArtifacts.Kind.USER_INPUT)) {
      var pending = conversationService.archive().pendingInputs(conversationId);
      if (!pending.isEmpty()) {
        userMessage = pending.getLast().message().content();
      }
    }
    // Initial advance has no recorded input; recovery can reuse the pending author source.
    if (userMessage.isBlank()) {
      RequirementDraft saved = draftStore.get(conversationId).orElse(null);
      if (saved != null && saved.readyForPlan()) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
            PipelineGates.tag(PipelineGates.REQUIREMENT_DRAFT_READY,
                "Requirements draft saved. Continue to planning when ready."))));
      }
      // Silent wait: first-stage advance has no userText yet. Do not surface agent capture jargon.
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
    }
    Object correction = context.attributes().get("requirementCorrectionDraft");
    if (correction instanceof RequirementDraft prior && draftStore.get(conversationId).isEmpty()) {
      draftStore.restoreAccepted(conversationId, prior);
    }
    RequirementDraft current = draftStore.get(conversationId).orElse(null);
    if (current != null
        && current.readyForPlan()
        && repair == null
        && ChatEvent.CONTINUE_TO_PLANNING_ACTION.equals(userMessage.strip())) {
      draftStore.beginTurn(conversationId);
      draftStore.finishTurn(conversationId, RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().item(completeDiscovery(context, new AtomicReference<>(current)));
    }
    Boolean idsChoice = idsChoice(userMessage);
    if (current != null
        && current.readyForPlan()
        && repair == null
        && current.idsRequested() == null
        && stageDeclaresIdsBypass(context)
        && idsChoice != null) {
      RequirementDraft decided = current.withIdsRequested(idsChoice);
      draftStore.beginTurn(conversationId);
      draftStore.recordIdsChoice(conversationId, idsChoice);
      draftStore.markCaptured(conversationId);
      return Multi.createFrom().item(completeDiscovery(context, new AtomicReference<>(decided)));
    }
    AtomicReference<RequirementDraft> captured = new AtomicReference<>();
    String gatherMessage = userMessage;
    RequirementDraft repairBase = mappingRepairDraft != null ? mappingRepairDraft : draftStore.get(conversationId).orElse(null);
    var nativeRepairPaths = requirementRepairPaths(context, repairBase);
    ToolSession.bind(conversationId);
    Context toolSessionContext = ToolSession.attachedContext();
    java.util.function.Supplier<Context> startCapture = () -> {
      Context installed = ProductCapabilityCaptureContext.bindDiscovery(
        context.runId(), conversationId, nativeRepairPaths.isEmpty() ? mappingRepairDraft : repairBase,
        mappingRepairPaths(context), nativeRepairPaths, payload -> {
          if (payload instanceof RequirementDraft draft) {
            captured.set(draft);
          }
        });
      if (captureFeedback != null) {
        captureFeedback.clearRequirement(conversationId);
      }
      draftStore.beginTurn(conversationId, gatherMessage);
      return installed;
    };
    if (conversationService != null) {
      conversationService.getOrCreate(conversationId);
    }
    var authority = conversationService == null ? null : conversationService.archive().read(conversationId);
    Context captureContext = authority == null ? startCapture.get() : conversationService.archive()
        .withAuthority(conversationId, authority.generation(),
            org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(authority), startCapture);
    ProductCapabilityCaptureContext.Binding binding = captureContext.get(ProductCapabilityCaptureContext.CONTEXT_KEY);
    String skillId = RequirementDraftTool.SOURCE_SKILL_ID;
    SkillActivitySupport.bindParents(skillId);
    Multi<ChatEvent> agentStream =
        ToolSession.propagateBinding(
            toolSessionContext,
            runGather(
                conversationId,
                userMessage,
                context.runManifest() == null ? "en" : context.runManifest().responseLocale(), binding));
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(skillId)),
            agentStream
                .collect().asList().onItem().transformToMulti(
                    events -> {
                      try {
                        java.util.function.Supplier<CapabilitySignal.Completed> finish = () ->
                            ProductCapabilityCaptureContext.withBinding(conversationId,
                                ProductCapabilityCaptureContext.invocationParameters(binding),
                                ProductCapabilityCaptureContext.Mode.DISCOVERY,
                                currentBinding -> completeDiscovery(context, captured));
                        var completed = authority == null ? finish.get() : conversationService.archive()
                            .withAuthority(conversationId, authority.generation(),
                                org.qubership.integration.platform.ai.chat.conversation.ConversationArchive.latestInputId(authority), finish);
                        List<CapabilitySignal> signals = new ArrayList<>();
                        var settledDraft = draftStore.get(conversationId).orElse(null);
                        if (completed.outcome().outcomeClass() == StageOutcomeClass.NEEDS_INPUT
                            && (settledDraft == null || !settledDraft.readyForPlan()
                                || !draftStore.wasCapturedThisTurn(conversationId))) {
                          events.forEach(event -> signals.add(new CapabilitySignal.Message(tokenText(event))));
                        }
                        signals.addAll(SkillActivitySupport.wrapTerminal(skillId, List.of(completed)));
                        return Multi.createFrom().iterable(signals);
                      } finally {
                        SkillActivitySupport.clearParents();
                        ProductCapabilityCaptureContext.unbind(binding);
                        ToolSession.clear();
                      }
                    })
                .onFailure()
                .recoverWithMulti(
                    error -> {
                      SkillActivitySupport.clearParents();
                      ProductCapabilityCaptureContext.unbind(binding);
                      ToolSession.clear();
                      return Multi.createFrom()
                          .items(
                              SkillActivitySupport.error(skillId),
                              new CapabilitySignal.Completed(
                                  StageOutcome.technicalFailure(error)));
                    }));
  }

  private static java.util.Set<String> requirementRepairPaths(StageExecutionContext context, RequirementDraft draft) {
    var repair = StageRepairEvidence.from(context);
    if (repair == null || repair.typedCauseJson().isBlank()) {
      return java.util.Set.of();
    }
    try {
      var cause = new com.fasterxml.jackson.databind.ObjectMapper()
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .readValue(repair.typedCauseJson(), RecoveryCause.class);
      var paths = new java.util.LinkedHashSet<String>();
      for (var finding : cause.findings()) {
        String path = RequirementDraftStore.requirementRepairPath(draft, finding);
        if (path != null) paths.add(path);
      }
      return java.util.Set.copyOf(paths);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalidEvidence) {
      return java.util.Set.of();
    }
  }

  private CapabilitySignal.Completed completeDiscovery(
      StageExecutionContext context,
      AtomicReference<RequirementDraft> captured) {
    String conversationId = context.conversationId();
    RequirementDiscoveryDirective directive = draftStore.turnDirective(conversationId);
    RequirementDraft draft = draftStore.get(conversationId).orElse(captured.get());
    var preservation = draftStore.validateCurrent(conversationId);
    if (!preservation.isEmpty()) {
      var findings = preservation.stream().map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true,
          MappingValidationDetails.empty(), issue.entityId(), CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "Retain validated requirements from rejected attempts before continuing.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, findings, "")));
    }
    Optional<RequirementRejection> rejection = captureFeedback == null ? Optional.empty()
        : captureFeedback.lastRequirementRejection(conversationId);
    if (rejection.isPresent() && rejection.get().issues().stream()
        .anyMatch(issue -> !"AMBIGUOUS_TARGET_FOLDER".equals(issue.code()))) {
      var findings = rejection.get().issues().stream().map(issue ->
          new PlanValidationFinding(
              issue.code(), issue.path() + ": " + issue.message(), true,
              MappingValidationDetails.empty(),
              issue.entityId() == null || issue.entityId().isBlank() ? "requirement-draft" : issue.entityId(),
              CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
      var cause = new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, findings, "");
      draftStore.recordCaptureRejection(conversationId, cause.formattedFindings());
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "The generated requirement draft was rejected. Repair the reported fields and preserve the requirements.",
          cause));
    }
    RequirementDraft repairDraft = mappingRepairDraft(context);
    if (repairDraft != null && !preservesMappingRepair(context, repairDraft, draft)) {
      draftStore.restoreAccepted(conversationId, repairDraft);
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "REPAIR_FACT_LOSS: Change only the rejected mapping field paths. The selected draft was retained.",
          RecoveryCause.mappingContract(List.of())));
    }
    if (directive == RequirementDiscoveryDirective.STAY) {
      if (draft != null && draft.readyForPlan()) {
        return new CapabilitySignal.Completed(
            StageOutcome.of(
                StageOutcomeClass.NEEDS_INPUT,
                PipelineGates.tag(
                    PipelineGates.REQUIREMENT_DRAFT_READY,
                    "Requirements draft saved. Continue to planning when ready.")));
      }
      return new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    if (draft == null) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "The requirements response omitted captureRequirementDraft. Capture the draft from the saved author input.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, List.of(new PlanValidationFinding(
              "CAPTURE_MISSING", "Capture the requirements draft before finishing the generation turn.", true,
              MappingValidationDetails.empty(), "requirement-draft", "/draft")), "")));
    }
    if (directive == RequirementDiscoveryDirective.NONE && !draftStore.wasCapturedThisTurn(conversationId)) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    // Pending APIHub import hands off to import-stage (ADR 0001) even when the draft
    // is still NEEDS_INPUT with the pinned import-confirm open question. Approved uploaded
    // specs hand off the same way so import can bind catalog-backed interactions that are
    // not in the runtime catalog yet.
    boolean pendingImportHandoff = draft.hasPendingImport() && !draft.facts().isEmpty();
    boolean pendingUploadedSpecHandoff =
        hasAllowedUploadedSpecs(conversationId) && !draft.facts().isEmpty();
    if (draft.decision() == DraftDecision.NEEDS_INPUT
        && draft.idsRequested() == null
        && draft.openQuestions().isEmpty()
        && !draft.facts().isEmpty()
        && !draft.flow().interactions().isEmpty()
        && RequirementFlowValidator.validateBindings(
                draft.flow(), draft.facts(), draft.catalogBindings())
            .isEmpty()
        && !pendingImportHandoff) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.NEEDS_INPUT,
              PipelineGates.tag(PipelineGates.IDS_PATH_CHOICE, "")));
    }
    if (!draft.readyForPlan() && !pendingImportHandoff && !pendingUploadedSpecHandoff) {
      // Blank on purpose: the gather agent already streamed the clarifying question. Emitting an
      // internal draft-state sentence glued it to that text and leaked READY_FOR_PLAN into chat.
      return new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    if (draft.readyForPlan()
        && draft.idsRequested() == null
        && stageDeclaresIdsBypass(context)) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.NEEDS_INPUT,
              PipelineGates.tag(
                  PipelineGates.IDS_PATH_CHOICE,
                  "Show the Integration Design Specification (IDS) for review?")));
    }
    if (draft.facts().isEmpty()) {
      return new CapabilitySignal.Completed(
          StageOutcome.of(
              StageOutcomeClass.CONTRACT_FAILURE,
              "READY_FOR_PLAN draft must include explicit facts"));
    }
    List<ArtifactCandidate> candidates = new ArrayList<>();
    candidates.add(
        new ArtifactCandidate(
            CompilationArtifacts.Kind.REQUIREMENT_DRAFT, draft, context.inputRefs()));
    // Read-only exact catalog hits only — never APIHub or import. Emit hints only when the active
    // stage declares catalog-binding-hint (optionalProduces or produces). create-chain@1 does not,
    // so it stays on REQUIREMENT_DRAFT alone and avoids unknown-candidate CONTRACT_FAILURE.
    if (stageDeclaresCatalogBindingHint(context)) {
      candidates.addAll(exactCatalogBindingHints(draft));
    }
    if (Boolean.FALSE.equals(draft.idsRequested()) && stageDeclaresIdsBypass(context)) {
      candidates.add(
          new ArtifactCandidate(
              CompilationArtifacts.Kind.IDS_BYPASS,
              new IdsBypass(
                  "author-declined",
                  context.profile().profileId(),
                  context.profile().profileVersion()),
              List.of()));
    }
    // create-chain@1 gates on draft approval (CANDIDATE). create-chain@2 has no discovery
    // approval, so SUCCEEDED advances to requirement-analysis without WAITING_FOR_APPROVAL.
    StageOutcomeClass outcomeClass =
        stageRequiresApproval(context)
            ? StageOutcomeClass.CANDIDATE
            : StageOutcomeClass.SUCCEEDED;
    return new CapabilitySignal.Completed(
        new StageOutcome(
            outcomeClass, candidates, "Requirement draft candidate ready", null));
  }

  /** True when the active profile stage declares an approval gate for this capability run. */
  static boolean stageRequiresApproval(StageExecutionContext context) {
    if (context == null || context.profile() == null || context.stageId() == null) {
      // Null profile is the unit-test / legacy path that still expects a draft candidate.
      return true;
    }
    return context.profile().stages().stream()
        .anyMatch(
            stage ->
                context.stageId().equals(stage.stageId()) && stage.approval() != null);
  }

  /**
   * True when the active profile stage declares {@code catalog-binding-hint} as a produced type
   * ({@code optionalProduces} or {@code produces}).
   */
  static boolean stageDeclaresCatalogBindingHint(StageExecutionContext context) {
    if (context == null || context.profile() == null || context.stageId() == null) {
      return false;
    }
    return context.profile().stages().stream()
        .filter(stage -> context.stageId().equals(stage.stageId()))
        .anyMatch(RequirementDiscoveryCapability::declaresCatalogBindingHintProduce);
  }

  /**
   * True when the active stage may produce {@code ids-bypass}. The artifact records that the
   * author declined the specification, and the runtime reads it to keep the document out of chat.
   */
  static boolean stageDeclaresIdsBypass(StageExecutionContext context) {
    if (context == null || context.profile() == null || context.stageId() == null) {
      return false;
    }
    return context.profile().stages().stream()
        .filter(stage -> context.stageId().equals(stage.stageId()))
        .anyMatch(
            stage ->
                declaresKind(stage.optionalProduces(), CompilationArtifacts.Kind.IDS_BYPASS)
                    || declaresKind(stage.produces(), CompilationArtifacts.Kind.IDS_BYPASS));
  }

  private static Boolean idsChoice(String userMessage) {
    String choice = userMessage == null ? "" : userMessage.strip();
    if (!ChatEvent.IDS_PATH_CHOICE_ACTIONS.contains(choice)) {
      return null;
    }
    return "yes".equals(choice);
  }

  private static boolean declaresCatalogBindingHintProduce(ProfileStage stage) {
    if (stage == null) {
      return false;
    }
    return declaresKind(stage.optionalProduces(), CompilationArtifacts.Kind.CATALOG_BINDING_HINT)
        || declaresKind(stage.produces(), CompilationArtifacts.Kind.CATALOG_BINDING_HINT);
  }

  private static boolean declaresKind(
      List<ArtifactTypeRef> refs, CompilationArtifacts.Kind kind) {
    if (refs == null || refs.isEmpty()) {
      return false;
    }
    for (ArtifactTypeRef ref : refs) {
      if (ref != null && ref.matches(kind)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Emits one {@code CATALOG_BINDING_HINT} per bound service call on the approved draft.
   *
   * <p>Drafts store frozen catalog identity on {@link CatalogBindingHint} rows. Discovery copies
   * those hints; it does not search the catalog, rank operations, or read the conversation
   * resolution cache. Restarting between capture and this stage therefore cannot drop a binding
   * that already landed on the draft.
   *
   */
  List<ArtifactCandidate> exactCatalogBindingHints(RequirementDraft draft) {
    if (draft == null) {
      return List.of();
    }
    List<ArtifactCandidate> hints = new ArrayList<>();
    for (CatalogBindingHint hint : draft.catalogBindings()) {
      hints.add(
          new ArtifactCandidate(
              CompilationArtifacts.Kind.CATALOG_BINDING_HINT, hint, List.of()));
    }
    return hints;
  }

  /**
   * True when this conversation already has uploaded API specification keys. CREATE routing asks
   * for import approval before the run starts, so keys here mean the import stage may bind them.
   */
  private boolean hasAllowedUploadedSpecs(String conversationId) {
    if (conversationService == null || conversationId == null || conversationId.isBlank()) {
      return false;
    }
    List<String> keys = conversationService.getAllowedAttachmentKeys(conversationId);
    return keys != null && !keys.isEmpty();
  }

  private Multi<ChatEvent> runGather(
      String conversationId, String userMessage, String responseLocale,
      ProductCapabilityCaptureContext.Binding binding) {
    if (gatherRunner != null) {
      return gatherRunner.apply(conversationId, userMessage);
    }
    if (gatherRequirementsAgent == null) {
      return Multi.createFrom().empty();
    }
    String agentInput =
        promptBuilder != null
            ? promptBuilder.wrap(conversationId, userMessage, responseLocale)
            : (userMessage == null ? "" : userMessage);
    return GatherRequirementsAgentCall.run(gatherRequirementsAgent, conversationId, agentInput,
        ProductCapabilityCaptureContext.invocationParameters(binding));
  }

  private static RequirementDraft mappingRepairDraft(StageExecutionContext context) {
    return StageRepairEvidence.isRepairTurn(context)
        && "true".equals(context.attributeAsString("mappingRepairAutomatic"))
        && RecoveryCauseCode.MAPPING_CONTRACT.name().equals(
            context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR))
        && context.attributes().get("requirementCorrectionDraft") instanceof RequirementDraft draft
        && draft.authoredDraft() != null ? draft : null;
  }

  private static Map<String, java.util.Set<String>> mappingRepairPaths(StageExecutionContext context) {
    var paths = new java.util.HashMap<String, java.util.Set<String>>();
    if (context.attributes().get("mappingRepairPaths") instanceof Map<?, ?> permissions) {
      permissions.forEach((id, fields) -> {
        if (id instanceof String factId && fields instanceof java.util.Set<?> names) {
          paths.put(factId, names.stream().filter(String.class::isInstance).map(String.class::cast)
              .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
      });
    }
    return Map.copyOf(paths);
  }

  static boolean preservesMappingRepair(
      StageExecutionContext context, RequirementDraft original, RequirementDraft candidate) {
    if (candidate == null || candidate.authoredDraft() == null) {
      return false;
    }
    var accepted = original.authoredDraft();
    var proposed = candidate.authoredDraft();
    var editable = mappingRepairPaths(context);
    var retained = new java.util.ArrayList<RequirementCaptureInput.FactInput>();
    for (var fact : proposed.facts()) {
      String factId = fact.sourceFactId();
      var prior = accepted.facts().stream()
          .filter(value -> value.sourceFactId().equals(factId)).findFirst().orElse(null);
      if (prior != null && editable.containsKey(fact.sourceFactId())
          && prior.fieldMapping() != null && fact.fieldMapping() != null) {
        var mapping = fact.fieldMapping();
        fact = new RequirementCaptureInput.FactInput(fact.sourceFactId(), fact.interactionIds(),
            fact.kind(), fact.polarity(), fact.text(), new RequirementCaptureInput.FieldMappingInput(
                mapping.sourceInteractionId(), editable.get(factId).contains("sourcePath")
                    ? prior.fieldMapping().sourcePath() : mapping.sourcePath(), mapping.targetInteractionId(),
                editable.get(factId).contains("targetPath") ? prior.fieldMapping().targetPath() : mapping.targetPath(),
                mapping.expression()), fact.policyConflict(), fact.elementIntent());
      }
      retained.add(fact);
    }
    return accepted.equals(new RequirementCaptureInput.DraftInput(proposed.flow(), retained,
        proposed.capabilities(), proposed.openQuestions(), proposed.settings()));
  }

  private static String tokenText(ChatEvent event) {
    if (event instanceof ChatEvent.Token token) {
      return token.text() == null ? "" : token.text();
    }
    return "";
  }
}
