package org.qubership.integration.platform.ai.productpipeline.create;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import io.smallrye.mutiny.Context;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore.RequirementRejection;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsAgentCall;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.plan.TargetFolderChoiceResolver;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureTools;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.plan.RequirementCaptureProjection;
import org.qubership.integration.platform.ai.plan.RequirementClarificationCoordinator;
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
import org.qubership.integration.platform.ai.productpipeline.recovery.RequirementRecoveryLadder;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/**
 * Product-pipeline requirement discovery stage. Uses brainstorming capture only; analysis never
 * receives the raw transcript from this capability.
 */
@ApplicationScoped
public class RequirementDiscoveryCapability implements StageCapability {

  public static final String CAPABILITY_ID = "requirement-discovery";
  private static final ObjectMapper MAPPING_REPAIR_MAPPER = new ObjectMapper();

  private final GatherRequirementsAgent gatherRequirementsAgent;
  private final RequirementDraftStore draftStore;
  private final GatherRequirementsPromptBuilder promptBuilder;
  private final BiFunction<String, String, Multi<ChatEvent>> gatherRunner;
  private final ConversationService conversationService;
  private final CatalogBindingMatcher catalogBindingMatcher;
  @Inject CaptureAttemptFeedbackStore captureFeedback;
  @Inject FailureNarrative failureNarrative;
  @Inject TargetFolderChoiceResolver targetFolderChoices;
  @Inject RequirementCaptureTools captureTools;

  /** Lets a cross-package test supply the capture tools the container would inject. */
  public void setCaptureTools(RequirementCaptureTools tools) {
    this.captureTools = tools;
  }
  @Inject org.qubership.integration.platform.ai.plan.UploadedSpecOperations uploadedSpecs;

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

  public RequirementDiscoveryCapability(
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
    Object correction = context.attributes().get("requirementCorrectionDraft");
    if (correction instanceof RequirementDraft prior && draftStore.get(conversationId).isEmpty()) {
      draftStore.restoreAccepted(conversationId, prior);
    }
    RequirementDraft saved = draftStore.get(conversationId).orElse(null);
    Boolean admittedChoice = idsChoice(userMessage);
    if (saved != null && saved.idsRequested() == null && admittedChoice != null
        && stageDeclaresIdsBypass(context)
        && PipelineGates.IDS_PATH_CHOICE.equals(
            context.attributeAsString(ProductPipelineRunSupport.ADMITTED_INPUT_GATE_ATTR))
        && context.attributes().get(ProductPipelineRunSupport.ADMITTED_INPUT_REVISION_ATTR) instanceof Long) {
      draftStore.beginTurn(conversationId, userMessage);
      draftStore.recordIdsChoice(conversationId, admittedChoice);
      draftStore.markCaptured(conversationId);
      if (captureFeedback != null) {
        captureFeedback.clearRequirement(conversationId);
      }
      RequirementDraft decided = draftStore.get(conversationId).orElseThrow();
      if (StageRepairEvidence.from(context) != null) {
        var attributes = new java.util.HashMap<>(context.attributes());
        attributes.put("userText", "");
        attributes.remove(ProductPipelineRunSupport.ADMITTED_INPUT_GATE_ATTR);
        attributes.remove(ProductPipelineRunSupport.ADMITTED_INPUT_REVISION_ATTR);
        if (attributes.get("requirementCorrectionDraft") instanceof RequirementDraft original) {
          attributes.put("requirementCorrectionDraft", original.withIdsRequested(admittedChoice));
        }
        return execute(new StageExecutionContext(context.runId(), conversationId, context.stageId(),
            context.executionKey(), context.attemptId(), context.profile(), context.runManifest(),
            context.inputRefs(), attributes));
      }
      return Multi.createFrom().item(completeDiscovery(context, new AtomicReference<>(decided)));
    }
    userMessage = focusedRecoveryInput(context, conversationId, userMessage);
    var folderAgain = unresolvedTargetFolder(context, conversationId, userMessage);
    if (folderAgain.isPresent()) {
      return Multi.createFrom().item(folderAgain.get());
    }
    if (admitActiveChainCall(context, conversationId, userMessage)) {
      draftStore.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
      draftStore.markCaptured(conversationId);
      return Multi.createFrom().item(completeDiscovery(context,
          new AtomicReference<>(draftStore.get(conversationId).orElse(null))));
    }
    RequirementDraft mappingRepairDraft = mappingRepairDraft(context);
    if (mappingRepairDraft != null) {
      var repair = StageRepairEvidence.from(context);
      userMessage = """
          Repair the selected draft's field paths using the catalog contract findings below.
          Read the accepted draft, then call updateRequirementDraft for the affected FIELD_MAPPING
          facts. Read readRequirementSource and preserve all fact IDs, text, source and
          target interactions, flow, settings, and unrelated requirements. Change only invalid field
          paths, or add FIELD_MAPPING facts for the unresolved required targets named by the native
          findings. Derive their source fields or constants from the saved author messages.
          If a rejected mapping's derived expression must change with its field binding, implement
          the same saved requirement; preserve explicit author formulas and unrelated expressions.
          Paths are relative to the actual message body. An operation name, resource name, or schema
          title is not a JSON wrapper. For example, a schema with root property Subject requires
          targetPath Subject; use Task.Subject only if its properties actually contain Task.Subject.
          Do not invent a field or remove its mapping to make validation pass. If the contract does
          not determine the correction, ask for clarification. After saving, finish with CONTINUE
          so analysis can present a new brief for approval.
          Contract findings:
          %s
          %s
          Native editable scope:
          %s
          """.formatted(context.attributes().getOrDefault("mappingRepairFindings", repair.diagnosticText()),
              repair.errorEvidence(), mappingRepairScope(mappingRepairPaths(context), missingMappingTargets(context),
                  mappingRepairDraft.authoredDraft()));
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
      var guidance = draftStore.recoveryGuidance(conversationId);
      if (guidance.isPresent()
          && guidance.get().strategy() == RequirementRecoveryLadder.Stage.PROJECTION_REBUILD) {
        userMessage = """
            Capture the requirements again from the author evidence and the semantic baseline.
            Do not restore rejected fields.
            Conflict participants: %s
            Editable paths: %s
            Cause: %s
            Findings:
            %s
            %s
            """.formatted(String.join(", ", guidance.get().participants()),
                String.join(", ", guidance.get().editablePaths()),
                repair.causeCode(), repair.findings(), repair.errorEvidence());
      } else {
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
      RequirementDraft waiting = draftStore.get(conversationId).orElse(null);
      if (shouldOfferIds(context, waiting)) {
        return Multi.createFrom().item(idsChoiceOutcome());
      }
      if (waiting != null && waiting.readyForPlan()) {
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
    RequirementDraft current = draftStore.get(conversationId).orElse(null);
    if (current != null
        && current.readyForPlan()
        && repair == null
        && ChatEvent.CONTINUE_TO_PLANNING_ACTION.equals(userMessage.strip())) {
      draftStore.beginTurn(conversationId);
      draftStore.finishTurn(conversationId, RequirementDiscoveryDirective.CONTINUE);
      return Multi.createFrom().item(completeDiscovery(context, new AtomicReference<>(current)));
    }
    AtomicReference<RequirementDraft> captured = new AtomicReference<>();
    String gatherMessage = userMessage;
    RequirementDraft repairBase = draftStore.get(conversationId).orElse(mappingRepairDraft);
    var nativeRepairPaths = requirementRepairPaths(context, repairBase);
    ToolSession.bind(conversationId);
    Context toolSessionContext = ToolSession.attachedContext();
    java.util.function.Supplier<Context> startCapture = () -> {
      Context installed = ProductCapabilityCaptureContext.bindDiscovery(
        context.runId(), conversationId, mappingRepairDraft == null && nativeRepairPaths.isEmpty() ? null : repairBase,
        mappingRepairPaths(context), nativeRepairPaths, missingMappingTargets(context), payload -> {
          if (payload instanceof RequirementDraft draft) {
            captured.set(draft);
          }
        });
      if (captureFeedback != null) {
        captureFeedback.clearRequirement(conversationId);
      }
      draftStore.beginTurn(conversationId, gatherMessage);
      if (PipelineGates.STAGE_CLARIFICATION.equals(context.attributeAsString(
          ProductPipelineRunSupport.ADMITTED_INPUT_GATE_ATTR))) {
        if (repair == null) draftStore.allowDiscussion(conversationId);
      }
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
            Multi.createFrom().deferred(() -> runGather(
                conversationId,
                gatherMessage,
                context.runManifest() == null ? "en" : context.runManifest().responseLocale(), binding, repair)));
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
                        boolean discuss = draftStore.turnDirective(conversationId)
                            == RequirementDiscoveryDirective.DISCUSS;
                        boolean needsInput = completed.outcome().outcomeClass() == StageOutcomeClass.NEEDS_INPUT;
                        if (needsInput
                            && (discuss || PipelineGates.gateOf(completed.outcome().message()).isEmpty())
                            && (discuss || settledDraft == null || !settledDraft.readyForPlan()
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
                                  StageOutcome.invocationFailure(error)));
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
        if ("REPAIR_FACT_LOSS".equals(finding.code())) continue;
        String path = RequirementDraftStore.requirementRepairPath(draft, finding);
        if (path != null) paths.add(path);
      }
      return java.util.Set.copyOf(paths);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalidEvidence) {
      return java.util.Set.of();
    }
  }

  private String focusedRecoveryInput(StageExecutionContext context, String conversationId, String userMessage) {
    if (userMessage == null || userMessage.isBlank() || captureFeedback == null
        || !PipelineGates.REQUIREMENT_RECOVERY_CLARIFICATION.equals(
            context.attributeAsString(ProductPipelineRunSupport.ADMITTED_INPUT_GATE_ATTR))) {
      return userMessage;
    }
    var rejection = captureFeedback.lastRequirementRejection(conversationId).orElse(null);
    String finding = rejection == null ? "" : structuredFinding(rejection);
    String editable = rejection == null ? "" : rejection.issues().stream().map(issue -> issue.path())
        .filter(path -> path != null && !path.isBlank()).distinct()
        .reduce((left, right) -> left + ", " + right).orElse("");
    return """
        Repair the focused requirement using the author's clarification.
        Original finding:
        %s
        Editable target:
        %s
        Author clarification:
        %s
        """.formatted(finding, editable, userMessage.strip());
  }

  private Optional<CapabilitySignal.Completed> unresolvedTargetFolder(
      StageExecutionContext context, String conversationId, String userMessage) {
    if (captureFeedback == null || !PipelineGates.TARGET_FOLDER_CHOICE.equals(
        context.attributeAsString(ProductPipelineRunSupport.ADMITTED_INPUT_GATE_ATTR))) {
      return Optional.empty();
    }
    if (submittedFolderPath(conversationId, userMessage)) {
      captureFeedback.clearRequirement(conversationId);
      return Optional.empty();
    }
    var rejection = captureFeedback.lastRequirementRejection(conversationId).orElse(null);
    if (rejection == null || rejection.issues().stream().noneMatch(issue ->
        "AMBIGUOUS_TARGET_FOLDER".equals(issue.code()))) {
      return Optional.empty();
    }
    return Optional.of(folderQuestion(context, rejection));
  }

  /** One exact folder path is admitted through {@link TargetFolderChoiceResolver#apply}. */
  private boolean submittedFolderPath(String conversationId, String userMessage) {
    if (targetFolderChoices == null || userMessage == null || userMessage.isBlank()) {
      return false;
    }
    String before = folderPath(draftStore.get(conversationId).orElse(null));
    draftStore.beginTurn(conversationId, userMessage);
    ToolSession.bind(conversationId);
    try {
      if (targetFolderChoices.apply(userMessage) == null) {
        return false;
      }
    } finally {
      ToolSession.clear();
    }
    String after = folderPath(draftStore.get(conversationId).orElse(null));
    return after != null && !after.isBlank() && !after.equals(before);
  }

  private static String folderPath(RequirementDraft draft) {
    if (draft == null || draft.authoredDraft() == null || draft.authoredDraft().settings() == null) {
      return null;
    }
    return draft.authoredDraft().settings().targetFolderPath();
  }

  /**
   * Writes one chain-trigger element id when this answer returns the open requirement offer and
   * that offer is still the active catalog question. Admission is the capture result, not a
   * before-and-after comparison.
   */
  private boolean admitActiveChainCall(StageExecutionContext context, String conversationId, String userMessage) {
    if (captureTools == null || context == null || userMessage == null || userMessage.isBlank()) {
      return false;
    }
    if (!PipelineGates.STAGE_CLARIFICATION.equals(
        context.attributeAsString(ProductPipelineRunSupport.ADMITTED_INPUT_GATE_ATTR))) {
      return false;
    }
    String offered = context.attributeAsString(ProductPipelineRunSupport.ADMITTED_CLARIFICATION_OFFER_ATTR);
    if (!(context.attributes().get(ProductPipelineRunSupport.ADMITTED_INPUT_REVISION_ATTR) instanceof Long revision)
        || offered == null || offered.isBlank()) {
      return false;
    }
    RequirementDraft current = draftStore.get(conversationId).orElse(null);
    var active = selectActiveQuestion(conversationId, current).orElse(null);
    if (active == null || active.question().evidence() == null
        || active.question().evidence().kind() != AuthorQuestionEvidence.Kind.CATALOG_CHOICE) {
      return false;
    }
    if (!offered.equals(new RequirementClarificationCoordinator().createOffer(active, revision).offerId())) {
      return false;
    }
    var update = captureTools.chainCallAnswerUpdate(current.authoredDraft(), active.question(), userMessage);
    if (update.isEmpty()) {
      return false;
    }
    draftStore.beginTurn(conversationId, userMessage);
    ToolSession.bind(conversationId);
    String encoded;
    try {
      encoded = captureTools.updateRequirementDraft(update.get());
    } finally {
      ToolSession.clear();
    }
    return acceptedCapture(encoded);
  }

  private static boolean acceptedCapture(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return false;
    }
    try {
      return MAPPING_REPAIR_MAPPER.readTree(encoded).path("accepted").asBoolean(false);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      return false;
    }
  }

  private CapabilitySignal.Completed folderQuestion(StageExecutionContext context, RequirementRejection rejection) {
    var wording = clarificationWording(context, "targetFolderPath", structuredFinding(rejection));
    if (wording.isEmpty()) {
      return internalClarification(context.conversationId(), rejection);
    }
    return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
        PipelineGates.tag(PipelineGates.TARGET_FOLDER_CHOICE, wording.get())));
  }

  private CapabilitySignal.Completed recoveryQuestion(StageExecutionContext context, RequirementRejection rejection) {
    var wording = clarificationWording(context, "requirement", structuredFinding(rejection));
    if (wording.isEmpty()) {
      return internalClarification(context.conversationId(), rejection);
    }
    return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
        PipelineGates.tag(PipelineGates.REQUIREMENT_RECOVERY_CLARIFICATION, wording.get())));
  }

  private CapabilitySignal.Completed internalClarification(String conversationId, RequirementRejection rejection) {
    String message = "Requirement capture failed internally. This is not an author question.";
    draftStore.recordCaptureRejection(conversationId, message);
    var findings = rejection.issues().stream().map(issue -> new PlanValidationFinding(
        issue.code(), issue.path(), true, MappingValidationDetails.empty(),
        issue.entityId() == null || issue.entityId().isBlank() ? "requirement-draft" : issue.entityId(),
        CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
    var evidence = new CaptureFailureEvidence(rejection.feedback(), null, null, List.of());
    return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.INTERNAL_FAILURE, message,
        RecoveryCause.captureFailure(rejection.feedback(), findings, evidence)));
  }

  private Optional<String> clarificationWording(StageExecutionContext context, String requestedFact,
      String structuredFinding) {
    if (failureNarrative == null || context == null) {
      return Optional.empty();
    }
    String locale = context.runManifest() == null || context.runManifest().responseLocale() == null
        ? "en" : context.runManifest().responseLocale();
    return failureNarrative.askClarification(context.runId(), locale, requestedFact, context.stageId(),
        structuredFinding);
  }

  private static String structuredFinding(RequirementRejection rejection) {
    return rejection.issues().stream().map(issue -> issue.code() + " " + issue.path())
        .reduce((left, right) -> left + "\n" + right).orElse("");
  }

  private Optional<CapabilitySignal.Completed> ladderDiscoveryOutcome(StageExecutionContext context,
      String conversationId, RequirementRejection rejection, List<Issue> preservation) {
    var stage = rejection.feedback().ladderStage();
    if (stage == null || stage == RequirementRecoveryLadder.Stage.FOCUSED_REPAIR) {
      return Optional.empty();
    }
    if (stage == RequirementRecoveryLadder.Stage.INTERNAL_FAILURE
        && rejection.issues().stream().anyMatch(issue ->
            CaptureFailureClass.clarificationRepresentation(issue.code()))) {
      String message = "Requirement capture failed internally. This is not an author question.";
      draftStore.recordCaptureRejection(conversationId, message);
      return Optional.of(new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.INTERNAL_FAILURE, message)));
    }
    var codes = rejection.issues().stream().map(issue -> issue.code()).toList();
    if (stage == RequirementRecoveryLadder.Stage.ASK_AUTHOR && RequirementRecoveryLadder.asksAuthor(codes)) {
      return Optional.of(recoveryQuestion(context, rejection));
    }
    String message = stage == RequirementRecoveryLadder.Stage.PROJECTION_REBUILD
        ? "Capture the requirements again from the author evidence and the semantic baseline."
        : stage == RequirementRecoveryLadder.Stage.INTERNAL_FAILURE
            ? rejection.issues().stream().map(issue -> issue.message())
                .filter(text -> text != null && !text.isBlank()).findFirst()
                .orElse("Requirement capture failed internally. This is not an author question.")
            : "Requirement capture failed internally. This is not an author question.";
    var findings = rejection.issues().stream().map(issue -> new PlanValidationFinding(
        issue.code(), issue.path() + ": " + issue.message(), true, MappingValidationDetails.empty(),
        issue.entityId() == null || issue.entityId().isBlank() ? "requirement-draft" : issue.entityId(),
        CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
    var consequences = preservation.stream().map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true,
        MappingValidationDetails.empty(), issue.entityId(),
        CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
    var acceptedBase = draftStore.latestRevision(conversationId).map(revision -> revision.reference()).orElse(null);
    var evidence = new CaptureFailureEvidence(rejection.feedback(), acceptedBase, null, consequences);
    draftStore.recordCaptureRejection(conversationId, message);
    return Optional.of(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, message,
        RecoveryCause.captureFailure(rejection.feedback(), findings, evidence))));
  }

  private CapabilitySignal.Completed completeDiscovery(
      StageExecutionContext context,
      AtomicReference<RequirementDraft> captured) {
    String conversationId = context.conversationId();
    RequirementDiscoveryDirective directive = draftStore.turnDirective(conversationId);
    RequirementDraft draft = draftStore.get(conversationId).orElse(captured.get());
    var preservation = directive == RequirementDiscoveryDirective.DISCUSS
        ? draftStore.validateCurrent(conversationId) : draftStore.validateForContinuation(conversationId);
    Optional<RequirementRejection> rejection = captureFeedback == null ? Optional.empty()
        : captureFeedback.lastRequirementRejection(conversationId);
    if (rejection.isPresent() && !rejection.get().issues().isEmpty() && rejection.get().issues().stream()
        .allMatch(issue -> "AMBIGUOUS_TARGET_FOLDER".equals(issue.code()))) {
      return folderQuestion(context, rejection.get());
    }
    if (preservation.isEmpty() && rejection.isPresent() && !rejection.get().issues().isEmpty()
        && rejection.get().issues().stream().allMatch(issue ->
        "POLICY_RESPONSE_REQUIRED".equals(issue.code())) && draft != null && draft.authoredDraft() != null
        && draft.facts().stream().anyMatch(fact -> fact.policyConflict() != null
            && fact.policyConflict().decision() == null
            && draft.authoredDraft().openQuestions().stream().anyMatch(question ->
                question.questionId().equals(fact.policyConflict().questionId())))
        && draftStore.validateAuthorQuestions(conversationId).isEmpty()) {
      captureFeedback.clearRequirement(conversationId);
      rejection = Optional.empty();
    }
    if (preservation.isEmpty() && rejection.isPresent() && !rejection.get().issues().isEmpty()
        && rejection.get().issues().stream().allMatch(issue -> "POLICY_RESPONSE_REQUIRED".equals(issue.code()))) {
      var replacement = draftStore.pendingPolicyReplacement(conversationId);
      if (replacement.isPresent()) {
        return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
            PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, replacement.get().question().text())));
      }
    }
    if (rejection.isPresent() && rejection.get().issues().stream()
        .anyMatch(issue -> !"AMBIGUOUS_TARGET_FOLDER".equals(issue.code()))) {
      var ladderOutcome = ladderDiscoveryOutcome(context, conversationId, rejection.get(), preservation);
      if (ladderOutcome.isPresent()) {
        return ladderOutcome.get();
      }
      var findings = rejection.get().issues().stream().map(issue ->
          new PlanValidationFinding(
              issue.code(), issue.path() + ": " + issue.message(), true,
              MappingValidationDetails.empty(),
              issue.entityId() == null || issue.entityId().isBlank() ? "requirement-draft" : issue.entityId(),
              CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
      var consequences = preservation.stream().map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true,
          MappingValidationDetails.empty(), issue.entityId(), CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
      var rejected = draftStore.rejectedCaptureRevision(conversationId);
      var evidence = new CaptureFailureEvidence(
          rejection.get().feedback(), rejected.flatMap(revision -> revision.inputs().stream()
              .filter(ref -> ref.kind() == CompilationArtifacts.Kind.REQUIREMENT_DRAFT).findFirst()).orElse(null),
          rejected.map(revision -> revision.reference()).orElse(null), consequences);
      var cause = RecoveryCause.captureFailure(rejection.get().feedback(), findings, evidence);
      draftStore.recordCaptureRejection(conversationId, cause.formattedFindings());
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "The generated requirement draft was rejected. Repair the reported fields and preserve the requirements.",
          cause));
    }
    if (!preservation.isEmpty()) {
      var findings = preservation.stream().map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true,
          MappingValidationDetails.empty(), issue.entityId(), CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "Complete and retain validated requirements before continuing.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, findings, "")));
    }
    RequirementDraft repairDraft = mappingRepairDraft(context);
    if (repairDraft != null) repairDraft = ProductCapabilityCaptureContext.binding(conversationId)
        .map(ProductCapabilityCaptureContext.Binding::approvedDraft).orElse(repairDraft);
    var mappingIssues = repairDraft == null ? List.<Issue>of()
        : mappingRepairIssues(mappingRepairPaths(context), missingMappingTargets(context), repairDraft.authoredDraft(),
            draft == null ? null : draft.authoredDraft());
    if (!mappingIssues.isEmpty()) {
      var findings = mappingIssues.stream().map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true,
          MappingValidationDetails.empty(), issue.entityId(), issue.path())).toList();
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "REPAIR_FACT_LOSS: Change only the rejected mapping field paths.",
          RecoveryCause.mappingContract(findings)));
    }
    if (draft == null) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "The requirements response omitted captureRequirementDraft. Capture the draft from the saved author input.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, List.of(new PlanValidationFinding(
              "CAPTURE_MISSING", "Capture the requirements draft before finishing the generation turn.", true,
              MappingValidationDetails.empty(), "requirement-draft", "/draft")), "")));
    }
    if (directive == RequirementDiscoveryDirective.DISCUSS && !draftStore.discussionAllowed(conversationId)) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "Complete the producer work before finishing. Discussion requires a new author turn at a paused draft.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, List.of(new PlanValidationFinding(
              "DISCUSSION_NOT_AUTHORIZED", "An automatic producer turn cannot become an author discussion.", true)), "")));
    }
    if (draft.authoredDraft() != null && !draft.openQuestions().equals(RequirementCaptureProjection.toDraft(
        draft.authoredDraft(), draft, draft.catalogBindings(), draft.sourceSkillVersion(), draft.sourceSkillHash(),
        RequirementCaptureProjection.resolvedTargets(draft))
        .openQuestions())) {
      return focusedAuthorQuestionRepair();
    }
    if (queueHeadMissesNewerSourceTurns(conversationId, draft)) {
      return focusedAuthorQuestionRepair();
    }
    var questionIssues = draftStore.validateAuthorQuestions(conversationId);
    if (!questionIssues.isEmpty()) {
      var findings = questionIssues.stream().map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true,
          MappingValidationDetails.empty(), issue.entityId(), CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "Apply the saved author answer or justify the remaining uncertainty before waiting again.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, findings, "")));
    }
    if (directive == RequirementDiscoveryDirective.DISCUSS && legacyProjectedQuestion(draft)) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    if (shouldOfferIds(context, draft)) {
      return idsChoiceOutcome();
    }
    var uploadedInteractions = uploadedSpecs == null ? java.util.Set.<String>of()
        : uploadedSpecs.pendingInteractionIds(conversationId, draft);
    var pending = draft.authoredDraft() == null ? List.<RequirementCaptureProjection.PendingWork>of()
        : RequirementCaptureProjection.pending(
            draft.authoredDraft(), draft.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(draft), uploadedInteractions);
    boolean onlyUploadedImports = draft.authoredDraft() == null
        ? !uploadedInteractions.isEmpty() : RequirementCaptureProjection.onlyUploadedSpecImportsRemain(pending);
    if (!draft.readyForPlan() && !draft.hasPendingImport() && !onlyUploadedImports
        && draft.openQuestions().isEmpty()) {
      var findings = pending.stream().map(work -> new PlanValidationFinding(work.code(), work.message(), true,
          MappingValidationDetails.empty(), work.interactionId() == null ? "requirement-draft" : work.interactionId(),
          work.interactionId() == null ? "/draft" : "/interactions/" + work.interactionId())).toList();
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "Requirement discovery left unfinished work without an author question. Complete the saved draft and catalog lookups.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, findings, "")));
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
      return presentClarification(conversationId, draft, true);
    }
    if (directive == RequirementDiscoveryDirective.NONE && !draftStore.wasCapturedThisTurn(conversationId)) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    // Pending APIHub import hands off to import-stage (ADR 0001) even when the draft
    // is still NEEDS_INPUT with the pinned import-confirm open question. Approved uploaded
    // specs hand off the same way so import can bind catalog-backed interactions that are
    // not in the runtime catalog yet.
    boolean pendingImportHandoff = draft.hasPendingImport() && !draft.facts().isEmpty();
    boolean pendingUploadedSpecHandoff = onlyUploadedImports && !draft.facts().isEmpty();
    if (!draft.readyForPlan() && !pendingImportHandoff && !pendingUploadedSpecHandoff) {
      return presentClarification(conversationId, draft, false);
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

  private CapabilitySignal.Completed presentClarification(String conversationId, RequirementDraft draft,
      boolean blankWhenAbsent) {
    if (legacyProjectedQuestion(draft)) {
      return focusedAuthorQuestionRepair();
    }
    var active = selectActiveQuestion(conversationId, draft);
    if (active.isPresent()) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
          PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, active.get().question().text())));
    }
    if (blankWhenAbsent) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
        PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, "")));
  }

  private Optional<RequirementClarificationCoordinator.ActiveQuestion> selectActiveQuestion(String conversationId,
      RequirementDraft draft) {
    var revision = draftStore.latestRevision(conversationId).orElse(null);
    if (revision == null) {
      return Optional.empty();
    }
    String generation = "";
    if (conversationService != null) {
      String recorded = conversationService.archive().read(conversationId).generation();
      generation = recorded == null ? "" : recorded;
    }
    return new RequirementClarificationCoordinator().activeQuestion(revision, draft, generation);
  }

  private boolean queueHeadMissesNewerSourceTurns(String conversationId, RequirementDraft draft) {
    if (draft == null || draft.authoredDraft() == null || draft.authoredDraft().openQuestions().isEmpty()) {
      return false;
    }
    QuestionInput head = draft.authoredDraft().openQuestions().getFirst();
    if (head == null || head.evidence() == null) {
      return false;
    }
    var reviewed = new HashSet<>(head.evidence().reviewedInputIds());
    return readySourceTurnIds(conversationId).stream().anyMatch(id -> !reviewed.contains(id));
  }

  private List<String> readySourceTurnIds(String conversationId) {
    if (conversationService == null) {
      return List.of();
    }
    return conversationService.archive().read(conversationId).activeTurns().stream()
        .filter(turn -> turn.message().role() == ConversationMessage.Role.USER && "READY".equals(turn.status()))
        .map(turn -> turn.id())
        .toList();
  }

  private static boolean legacyProjectedQuestion(RequirementDraft draft) {
    if (draft == null || draft.openQuestions().isEmpty()) {
      return false;
    }
    var authored = draft.authoredDraft();
    return authored == null || authored.openQuestions().stream().allMatch(question -> question == null);
  }

  private static CapabilitySignal.Completed focusedAuthorQuestionRepair() {
    return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
        "Review the saved sources and capture a scoped author question before waiting for more input.",
        new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, List.of(new PlanValidationFinding(
            "AUTHOR_QUESTION_NOT_SUPPORTED", "Derived pending work does not establish missing author input."
                + " Read the saved sources, complete the draft, or capture the remaining question with its evidence.",
            true, MappingValidationDetails.empty(), "requirement-draft", "/openQuestions")), "")));
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

  private static boolean shouldOfferIds(StageExecutionContext context, RequirementDraft draft) {
    if (draft == null || draft.idsRequested() != null || !stageDeclaresIdsBypass(context)
        || draft.hasPendingImport()) {
      return false;
    }
    return draft.readyForPlan()
        || draft.authoredDraft() != null && draft.authoredDraft().openQuestions().stream()
            .anyMatch(question -> PipelineGates.IDS_PATH_CHOICE.equals(question.questionId()))
        || draft.decision() == DraftDecision.NEEDS_INPUT && draft.openQuestions().isEmpty()
            && !draft.facts().isEmpty() && !draft.flow().interactions().isEmpty()
            && (draft.authoredDraft() == null || RequirementCaptureProjection.pending(
                draft.authoredDraft(), draft.catalogBindings(), RequirementCaptureProjection.resolvedTargets(draft)).isEmpty())
            && RequirementFlowValidator.validateBindings(draft.flow(), draft.facts(), draft.catalogBindings()).isEmpty();
  }

  private static CapabilitySignal.Completed idsChoiceOutcome() {
    return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
        PipelineGates.tag(PipelineGates.IDS_PATH_CHOICE,
            "Show the Integration Design Specification (IDS) for review?")));
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

  private Multi<ChatEvent> runGather(
      String conversationId, String userMessage, String responseLocale,
      ProductCapabilityCaptureContext.Binding binding, StageRepairEvidence repair) {
    if (repair != null) {
      userMessage = (userMessage == null ? "" : userMessage)
          + "\n\n<current-native-repair-evidence>\n"
          + "Current server validation for this repair turn; this is not an author message or amendment authority.\n"
          + repair.diagnosticText()
          + "\n</current-native-repair-evidence>\n";
    }
    userMessage = (userMessage == null ? "" : userMessage)
        + org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.block(
            draftStore.rejectedProposalHistory(conversationId));
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
        ProductCapabilityCaptureContext.invocationParameters(binding), repair != null);
  }

  private static RequirementDraft mappingRepairDraft(StageExecutionContext context) {
    return StageRepairEvidence.isRepairTurn(context)
        && "true".equals(context.attributeAsString("mappingRepairAutomatic"))
        && (RecoveryCauseCode.MAPPING_CONTRACT.name().equals(
            context.attributeAsString(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR))
            || context.attributes().containsKey("mappingRepairFindings"))
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
    return preservesMappingRepair(mappingRepairPaths(context), missingMappingTargets(context), original, candidate);
  }

  private static List<MappingValidationDetails> missingMappingTargets(StageExecutionContext context) {
    Object value = context.attributes().get("missingMappingTargets");
    return value instanceof List<?> targets ? targets.stream().filter(MappingValidationDetails.class::isInstance)
        .map(MappingValidationDetails.class::cast).toList() : List.of();
  }

  public static boolean preservesMappingRepair(
      Map<String, java.util.Set<String>> editable, RequirementDraft original, RequirementDraft candidate) {
    return preservesMappingRepair(editable, List.of(), original, candidate);
  }

  public static boolean preservesMappingRepair(Map<String, java.util.Set<String>> editable,
      List<MappingValidationDetails> missingTargets, RequirementDraft original, RequirementDraft candidate) {
    if (candidate == null || candidate.authoredDraft() == null) {
      return false;
    }
    return preservesMappingRepair(editable, missingTargets, original.authoredDraft(), candidate.authoredDraft());
  }

  public static boolean preservesMappingRepair(Map<String, java.util.Set<String>> editable,
      List<MappingValidationDetails> missingTargets, RequirementCaptureInput.DraftInput accepted,
      RequirementCaptureInput.DraftInput proposed) {
    return mappingRepairIssues(editable, missingTargets, accepted, proposed).isEmpty();
  }

  /** Reports exactly the protected differences left after applying the existing mapping authority. */
  public static List<Issue> mappingRepairIssues(
      Map<String, java.util.Set<String>> editable, List<MappingValidationDetails> missingTargets,
      RequirementCaptureInput.DraftInput accepted, RequirementCaptureInput.DraftInput proposed) {
    var retained = retainMappingRepair(editable, missingTargets, accepted, proposed);
    if (retained != null && accepted.equals(retained)) return List.of();
    var issues = new ArrayList<Issue>();
    if (retained != null) {
      mappingRepairDifferences(mappingRepairTree(accepted), mappingRepairTree(retained), "/draft", null, issues);
      var beforeIds = accepted.facts().stream().map(RequirementCaptureInput.FactInput::sourceFactId).toList();
      var afterIds = retained.facts().stream().map(RequirementCaptureInput.FactInput::sourceFactId).toList();
      if (!beforeIds.equals(afterIds) && beforeIds.size() == afterIds.size()
          && new java.util.HashSet<>(beforeIds).equals(new java.util.HashSet<>(afterIds))) {
        mappingRepairDifferences(MAPPING_REPAIR_MAPPER.valueToTree(beforeIds), MAPPING_REPAIR_MAPPER.valueToTree(afterIds),
            "/draft/facts", null, issues);
      }
    }
    if (issues.isEmpty()) issues.add(new Issue(
        "REPAIR_FACT_LOSS", "/draft", null, "Preserve the complete selected draft and submit a valid mapping correction."));
    var first = issues.getFirst();
    issues.set(0, new Issue(first.code(), first.path(),
        first.entityId(), first.message() + " " + mappingRepairScope(editable, missingTargets, accepted)));
    return List.copyOf(issues);
  }

  private static ObjectNode mappingRepairTree(RequirementCaptureInput.DraftInput draft) {
    ObjectNode tree = MAPPING_REPAIR_MAPPER.valueToTree(draft);
    var facts = tree.putObject("facts");
    draft.facts().forEach(fact -> facts.set(fact.sourceFactId(), MAPPING_REPAIR_MAPPER.valueToTree(fact)));
    return tree;
  }

  private static void mappingRepairDifferences(JsonNode before,
      JsonNode after, String path, String factId,
      List<Issue> issues) {
    if (before.equals(after)) return;
    if (before.isObject() && after.isObject()) {
      var fields = new java.util.TreeSet<String>();
      before.fieldNames().forEachRemaining(fields::add);
      after.fieldNames().forEachRemaining(fields::add);
      for (String field : fields) mappingRepairDifferences(before.path(field), after.path(field),
          path + "/" + field.replace("~", "~0").replace("/", "~1"), "/draft/facts".equals(path) ? field : factId, issues);
      return;
    }
    if (before.isArray() && after.isArray()) {
      for (int i = 0; i < Math.max(before.size(), after.size()); i++)
        mappingRepairDifferences(before.path(i), after.path(i), path + "/" + i, factId, issues);
      return;
    }
    String reason = after.isMissingNode() ? "A protected requirement was removed."
        : before.isMissingNode() ? "This addition is outside the authorized missing mapping targets."
            : "This protected value is outside the editable mapping fields.";
    issues.add(new Issue("REPAIR_FACT_LOSS", path, factId,
        reason + " Accepted value: " + (before.isMissingNode() ? "<absent>" : before)
            + ". Rejected value: " + (after.isMissingNode() ? "<absent>" : after) + "."));
  }

  private static String mappingRepairScope(Map<String, java.util.Set<String>> editable,
      List<MappingValidationDetails> missingTargets, RequirementCaptureInput.DraftInput accepted) {
    var paths = new java.util.TreeSet<String>();
    if (accepted != null && accepted.facts() != null) accepted.facts().stream().filter(Objects::nonNull)
        .filter(fact -> fact.fieldMapping() != null).forEach(fact -> {
          for (String field : editable.getOrDefault(fact.sourceFactId(), java.util.Set.of())) {
            if (java.util.Set.of("sourcePath", "targetPath", "expression").contains(field))
              paths.add("/draft/facts/" + fact.sourceFactId().replace("~", "~0").replace("/", "~1") + "/fieldMapping/" + field);
          }
        });
    var additions = missingTargets.stream().map(target -> MAPPING_REPAIR_MAPPER.createObjectNode()
        .put("sourceInteractionId", target.sourceRef()).put("targetInteractionId", target.targetRef())
        .put("targetPath", target.targetPath())).toList();
    return "Allowed existing mapping field paths: " + MAPPING_REPAIR_MAPPER.valueToTree(paths)
        + ". Add only positive FIELD_MAPPING facts for these source/target/path contracts: "
        + MAPPING_REPAIR_MAPPER.valueToTree(additions) + ". Preserve all other values and fact order.";
  }

  private static RequirementCaptureInput.DraftInput retainMappingRepair(Map<String, java.util.Set<String>> editable,
      List<MappingValidationDetails> missingTargets, RequirementCaptureInput.DraftInput accepted,
      RequirementCaptureInput.DraftInput proposed) {
    if (accepted == null || proposed == null || accepted.facts() == null || proposed.facts() == null
        || accepted.facts().stream().anyMatch(Objects::isNull) || proposed.facts().stream().anyMatch(Objects::isNull)) {
      return null;
    }
    var retained = new java.util.ArrayList<RequirementCaptureInput.FactInput>();
    for (var fact : proposed.facts()) {
      String factId = fact.sourceFactId();
      var prior = accepted.facts().stream()
          .filter(value -> value.sourceFactId().equals(factId)).findFirst().orElse(null);
      var addedMapping = fact.fieldMapping();
      if (prior == null && fact.kind() == RequirementCaptureInput.FactKind.FIELD_MAPPING
          && fact.polarity() == RequirementCaptureInput.Polarity.POSITIVE && fact.fieldMapping() != null
          && missingTargets.stream().anyMatch(target ->
              target.sourceRef().equals(addedMapping.sourceInteractionId())
                  && target.targetRef().equals(addedMapping.targetInteractionId())
                  && target.targetPath().equals(org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract
                      .canonicalPath(addedMapping.targetPath())))) continue;
      if (prior != null && editable.containsKey(fact.sourceFactId())
          && prior.fieldMapping() != null && fact.fieldMapping() != null) {
        var mapping = fact.fieldMapping();
        fact = new RequirementCaptureInput.FactInput(fact.sourceFactId(), fact.interactionIds(),
            fact.kind(), fact.polarity(), fact.text(), new RequirementCaptureInput.FieldMappingInput(
                mapping.sourceInteractionId(), editable.get(factId).contains("sourcePath")
                    ? prior.fieldMapping().sourcePath() : mapping.sourcePath(), mapping.targetInteractionId(),
                editable.get(factId).contains("targetPath") ? prior.fieldMapping().targetPath() : mapping.targetPath(),
                editable.get(factId).contains("expression") ? prior.fieldMapping().expression()
                    : mapping.expression()), fact.policyConflict(), fact.elementIntent());
      }
      retained.add(fact);
    }
    return new RequirementCaptureInput.DraftInput(proposed.flow(), retained,
        proposed.capabilities(), proposed.openQuestions(), proposed.settings());
  }

  private static String tokenText(ChatEvent event) {
    if (event instanceof ChatEvent.Token token) {
      return token.text() == null ? "" : token.text();
    }
    return "";
  }
}
