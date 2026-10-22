package org.qubership.integration.platform.ai.productpipeline.create;

import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;
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
import org.qubership.integration.platform.ai.plan.RequirementCaptureProjection;
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
          """.formatted(context.attributes().getOrDefault("mappingRepairFindings", repair.diagnosticText()),
              repair.errorEvidence());
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
        draftStore.associateClarification(conversationId);
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
                        if (completed.outcome().outcomeClass() == StageOutcomeClass.NEEDS_INPUT
                            && PipelineGates.gateOf(completed.outcome().message()).isEmpty()
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
    var preservation = directive == RequirementDiscoveryDirective.DISCUSS
        ? draftStore.validateCurrent(conversationId) : draftStore.validateForContinuation(conversationId);
    Optional<RequirementRejection> rejection = captureFeedback == null ? Optional.empty()
        : captureFeedback.lastRequirementRejection(conversationId);
    if (rejection.isPresent() && !rejection.get().issues().isEmpty() && rejection.get().issues().stream()
        .allMatch(issue -> "AMBIGUOUS_TARGET_FOLDER".equals(issue.code()))) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
          PipelineGates.tag(PipelineGates.STAGE_CLARIFICATION,
              "Which catalog folder should contain this chain? Several folders have the requested name.")));
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
    if (repairDraft != null && !preservesMappingRepair(context, repairDraft, draft)) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "REPAIR_FACT_LOSS: Change only the rejected mapping field paths.",
          RecoveryCause.mappingContract(List.of())));
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
    if (directive == RequirementDiscoveryDirective.DISCUSS) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, ""));
    }
    if (draft.authoredDraft() != null && !draft.openQuestions().equals(RequirementCaptureProjection.toDraft(
        draft.authoredDraft(), draft, draft.catalogBindings(), draft.sourceSkillVersion(), draft.sourceSkillHash(),
        RequirementCaptureProjection.resolvedTargets(draft))
        .openQuestions())) {
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "Review the saved sources and capture a scoped author question before waiting for more input.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, List.of(new PlanValidationFinding(
              "AUTHOR_QUESTION_NOT_SUPPORTED", "Derived pending work does not establish missing author input."
                  + " Read the saved sources, complete the draft, or capture the remaining question with its evidence.",
              true, MappingValidationDetails.empty(), "requirement-draft", "/openQuestions")), "")));
    }
    var questionIssues = draftStore.validateAuthorQuestions(conversationId);
    if (!questionIssues.isEmpty()) {
      var findings = questionIssues.stream().map(issue -> new PlanValidationFinding(issue.code(), issue.message(), true,
          MappingValidationDetails.empty(), issue.entityId(), CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList();
      return new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE,
          "Apply the saved author answer or justify the remaining uncertainty before waiting again.",
          new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE, findings, "")));
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
      String questions = String.join("\n", draft.openQuestions());
      return new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
              questions.isBlank() ? "" : PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, questions)));
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
      return new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT,
              PipelineGates.retag(PipelineGates.STAGE_CLARIFICATION, String.join("\n", draft.openQuestions()))));
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
    if (accepted == null || proposed == null || accepted.facts() == null || proposed.facts() == null
        || accepted.facts().stream().anyMatch(Objects::isNull) || proposed.facts().stream().anyMatch(Objects::isNull)) {
      return false;
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
