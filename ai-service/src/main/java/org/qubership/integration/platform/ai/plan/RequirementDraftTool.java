package org.qubership.integration.platform.ai.plan;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationDirection;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogQuery;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;

/**
 * LangChain4j tool that persists the requirement draft for the active chat conversation.
 */
@ApplicationScoped
public class RequirementDraftTool {

  private static final Logger LOG = Logger.getLogger(RequirementDraftTool.class);
  public static final String SOURCE_SKILL_ID = "brainstorming";

  public static final String CAPTURE_REQUIRED_MESSAGE =
      "Gather did not capture a requirement draft. The agent must call captureRequirementDraft"
          + " with the accumulated vision text before finishing.";

  /**
   * Soft-continue guidance when gather ends without a successful capture. Shown to the user
   * instead of {@link #CAPTURE_REQUIRED_MESSAGE} (agent-facing diagnostic).
   */
  public static final String CAPTURE_MISSING_USER_GUIDANCE =
      "I could not capture a requirement draft yet. Please restate the integration goal, trigger,"
          + " response shape, and any hard constraints.";

  static final String FACTS_REQUIRED_OPEN_QUESTION =
      "What must this chain do, and what must it never do or call?";

  static final String FACTS_SOFT_DOWNGRADE_PREFIX =
      "Requirement draft stored as NEEDS_INPUT (not READY_FOR_PLAN): facts were empty. ";

  static final String FACTS_SOFT_DOWNGRADE_HINT =
      "In this same turn, call captureRequirementDraft again with decision=READY_FOR_PLAN and"
          + " facts distilled from assembledText (at least one POSITIVE and one NEGATIVE). Do not"
          + " ask the user for polarity labels or schema jargon; derive facts from the vision."
          + " Reply to the user only after a successful capture, in the user's language.";

  static final String BINDING_REQUIRED_OPEN_QUESTION =
      "Which catalog operation should each unresolved interaction use? Call resolveApiOperation"
          + " for each interactionId after capturing RequirementFlow.";

  static final String BINDING_SOFT_DOWNGRADE_PREFIX =
      "Requirement draft stored as NEEDS_INPUT (not READY_FOR_PLAN): one or more catalog-backed"
          + " interactions"
          + " are unresolved. ";

  static final String BINDING_SOFT_DOWNGRADE_HINT =
      "Call resolveApiOperation for each unresolved interactionId, then recapture the same"
          + " RequirementFlow. Do not author topology facts or invent catalog UUIDs.";

  static final String FLOW_REQUIRED_OPEN_QUESTION =
      "Capture the complete RequirementFlow with stable interactionId values before planning.";

  static final String FLOW_SOFT_DOWNGRADE_PREFIX =
      "Requirement draft stored as NEEDS_INPUT (not READY_FOR_PLAN): flow was empty. ";

  static final String FLOW_SOFT_DOWNGRADE_HINT =
      "Recapture the business interactions and transitions before catalog lookup.";

  static final String ALREADY_READY_STOP_HINT =
      "Requirement draft is already READY_FOR_PLAN for this turn. Do not call"
          + " captureRequirementDraft again and do not repeat the ready-for-planning message."
          + " Stop the tool loop; the server advances to the next phase.";

  static final String IMPORT_PENDING_SOFT_DOWNGRADE_PREFIX =
      "Requirement draft stored as NEEDS_INPUT (not READY_FOR_PLAN): apiHubCandidate is pending"
          + " import. ";

  static final String IMPORT_PENDING_SOFT_DOWNGRADE_HINT =
      "The reader is offered the import as a decision. Do not claim the plan is ready until"
          + " import completes. Do not call captureRequirementDraft again in this turn unless"
          + " openQuestions or assembledText must change.";

  static final String BLOCKED_WITH_CANDIDATE_SOFT_DOWNGRADE_PREFIX =
      "Requirement draft stored as NEEDS_INPUT (not BLOCKED): API Hub candidate is available for"
          + " import. ";

  static final String BLOCKED_WITH_CANDIDATE_SOFT_DOWNGRADE_HINT =
      "The reader is offered the import as a decision. Do not claim the API was missing from API"
          + " Hub. Do not call captureRequirementDraft again in this turn unless openQuestions or"
          + " assembledText must change.";

  private final RequirementDraftStore store;
  private final QipKnowledgePackRepository repository;
  private final ConversationCatalogCache catalogCache;
  private final org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache
      apiHubCache;
  private final ConversationApiResolutions resolutions;
  private final ConversationService conversationService;
  private final CatalogOperationLookup catalogOperationLookup;

  @Inject
  RequirementDraftTool(
      RequirementDraftStore store,
      QipKnowledgePackRepository repository,
      ConversationCatalogCache catalogCache,
      org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache apiHubCache,
      ConversationApiResolutions resolutions,
      ConversationService conversationService,
      CatalogOperationLookup catalogOperationLookup) {
    this.store = store;
    this.repository = repository;
    this.catalogCache = catalogCache;
    this.apiHubCache = apiHubCache;
    this.resolutions = resolutions;
    this.conversationService = conversationService;
    this.catalogOperationLookup = catalogOperationLookup;
  }

  public RequirementDraftTool(RequirementDraftStore store) {
    this(store, null, null, null, null, null, null);
  }

  RequirementDraftTool(RequirementDraftStore store, QipKnowledgePackRepository repository) {
    this(store, repository, null, null, null, null, null);
  }

  RequirementDraftTool(
      RequirementDraftStore store,
      QipKnowledgePackRepository repository,
      ConversationCatalogCache catalogCache,
      org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache apiHubCache,
      ConversationApiResolutions resolutions) {
    this(store, repository, catalogCache, apiHubCache, resolutions, null, null);
  }

  static RequirementDraftTool withCache(
      RequirementDraftStore store, ConversationCatalogCache catalogCache) {
    return new RequirementDraftTool(store, null, catalogCache, null, null, null, null);
  }

  static RequirementDraftTool withCaches(
      RequirementDraftStore store,
      ConversationCatalogCache catalogCache,
      org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache apiHubCache) {
    return new RequirementDraftTool(store, null, catalogCache, apiHubCache, null, null, null);
  }

  static RequirementDraftTool withResolutions(
      RequirementDraftStore store, ConversationApiResolutions resolutions) {
    return new RequirementDraftTool(store, null, null, null, resolutions, null, null);
  }

  static RequirementDraftTool withLookup(
      RequirementDraftStore store,
      ConversationApiResolutions resolutions,
      CatalogOperationLookup catalogOperationLookup) {
    return new RequirementDraftTool(
        store, null, null, null, resolutions, null, catalogOperationLookup);
  }

  static RequirementDraftTool withConversationService(
      RequirementDraftStore store, ConversationService conversationService) {
    return new RequirementDraftTool(store, null, null, null, null, conversationService, null);
  }

  @Tool("""
      Capture the accumulated requirement draft decision for this conversation in the same turn.
      Do not pass conversationId: the server binds the draft to the current chat session.
      Pass the full accumulated vision text every time. The store replaces the previous draft.
      Set decision to NEEDS_INPUT when one user answer is still required, READY_FOR_PLAN only
      when no open questions remain, or BLOCKED when the request cannot proceed without an
      unavailable artifact or external decision.
      Keep openQuestions empty for READY_FOR_PLAN.
      Capture a complete RequirementFlow before catalog lookup. Each interaction needs a stable
      interactionId, direction INBOUND or OUTBOUND, participant, and operation. Transitions name
      sourceInteractionId and targetInteractionId. List position is not order.
      Do not add publish or subscribe to the business flow; runtime validates those catalog verbs
      against the captured direction. Keep payload constants and mapping rules out of operation
      names.
      Do not author ENDPOINT or SERVICE_CALL topology facts. Java projects those roles from the
      flow. Keep facts as ordinary constraints and native-trigger configuration: polarity, text,
      and optional kind (GOAL, PARAMETER, BEHAVIOR, CONSTRAINT, CAPABILITY, VISIBILITY, ROUTING).
      Native trigger configuration uses a CAPABILITY fact whose sourceFactId matches the inbound
      interactionId (http-trigger httpMethod and path, or kafka-trigger-2 topic).
      Distill facts from assembledText yourself; never ask the user for polarity labels.
      When READY_FOR_PLAN is sent without facts, the server soft-stores NEEDS_INPUT. Retry the
      same turn with facts, or keep NEEDS_INPUT with one open question.
      For every catalog-backed interaction, the server binds a unique local catalog match on
      capture. Call resolveApiOperation only for interactions capture still reports as unresolved.
      Catalog-backed inbound Kafka consumes use the same tool; do not skip them because they are
      INBOUND. It checks the local catalog first and searches API Hub only after a confirmed
      catalog miss. Never invent UUIDs.
      When this conversation has uploaded API specifications already approved for import, capture
      the flow from those documents and set READY_FOR_PLAN without catalog bindings.
      Do not search API Hub for operations the attached spec already defines; import after
      discovery binds them.
      When catalog lookup misses but API Hub returns a match, call selectApiHubCandidate with
      interactionId plus packageId, version, and operationId or documentId from the search hit
      (do not put apiHubCandidate on this capture). Keep decision=NEEDS_INPUT and leave
      openQuestions empty; the server offers the import as a decision card.
      Do not set decision=READY_FOR_PLAN while an API Hub candidate is pending import.
      Set idsRequested from what the author says about the Integration Design Specification:
      true when they ask for one, false when they say they do not want one. Leave it out while
      they have not said either way. If it is still unset when the requirements are otherwise
      ready, ask once whether to produce the specification and record the answer on the next
      capture; do not ask again after that.
      After a successful READY_FOR_PLAN capture in this turn, do not call captureRequirementDraft
      again and do not repeat the ready-for-planning assistant text.
      {
        "complete": true,
        "decision": "READY_FOR_PLAN",
        "assembledText": "Receive an order and create it in the target system.",
        "openQuestions": [],
        "flow": {
          "interactions": [
            {
              "interactionId": "order-received",
              "direction": "INBOUND",
              "participant": "Caller",
              "operation": "POST /orders",
              "description": "The caller submits an order."
            },
            {
              "interactionId": "create-order",
              "direction": "OUTBOUND",
              "participant": "Order System",
              "operation": "createOrder",
              "description": "The chain creates the order."
            }
          ],
          "transitions": [
            {
              "sourceInteractionId": "order-received",
              "targetInteractionId": "create-order"
            }
          ]
        },
        "facts": [
          {
            "sourceFactId": "order-received",
            "polarity": "POSITIVE",
            "kind": "CAPABILITY",
            "capabilityKey": "http-trigger",
            "text": "Expose POST /orders.",
            "httpMethod": "POST",
            "path": "/orders"
          }
        ]
      }""")
  public String captureRequirementDraft(RequirementDraftCapture capture) {
    String conversationId = ChainPlanTool.resolveConversationId();
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        "captureRequirementDraft",
        conversationId,
        "decision="
            + (capture != null ? capture.decision() : null)
            + " preview="
            + AiTraceLog.preview(capture != null ? capture.assembledText() : "null", 80));

    try {
      if (conversationId == null || conversationId.isBlank()) {
        String message = "conversationId is required (no active chat session)";
        LOG.warnf("captureRequirementDraft: %s", message);
        return finish(conversationId, startMs, message);
      }
      if (capture == null) {
        String message = "capture is required";
        LOG.warnf("captureRequirementDraft: %s conversationId=%s", message, conversationId);
        return finish(conversationId, startMs, message);
      }
      if (capture.assembledText() == null || capture.assembledText().isBlank()) {
        String message = "assembledText cannot be blank";
        LOG.warnf("captureRequirementDraft: validation failed conversationId=%s", conversationId);
        return finish(conversationId, startMs, message);
      }

      DraftDecision decision =
          capture.decision() != null
              ? capture.decision()
              : (capture.complete() ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT);
      RequirementDraft previous = store.get(conversationId).orElse(null);
      if (decision == DraftDecision.READY_FOR_PLAN
          && previous != null
          && previous.readyForPlan()
          && store.wasCapturedThisTurn(conversationId)) {
        LOG.infof(
            "captureRequirementDraft: skipping duplicate READY_FOR_PLAN conversationId=%s",
            conversationId);
        return finish(conversationId, startMs, ALREADY_READY_STOP_HINT);
      }
      List<String> openQuestions =
          capture.openQuestions() == null ? List.of() : capture.openQuestions();
      List<RequirementFact> facts = capture.facts() == null ? List.of() : capture.facts();
      boolean softDowngradedForFacts = false;
      boolean softDowngradedForFlow = false;
      boolean softDowngradedForImport = false;
      boolean softDowngradedForBinding = false;
      boolean softDowngradedBlockedWithCandidate = false;
      if (decision == DraftDecision.READY_FOR_PLAN && facts.isEmpty()) {
        // Soft-advance: keep the draft instead of rejecting the turn (no CAPTURE_REQUIRED leak).
        softDowngradedForFacts = true;
        decision = DraftDecision.NEEDS_INPUT;
        if (openQuestions.isEmpty()) {
          openQuestions = List.of(FACTS_REQUIRED_OPEN_QUESTION);
        }
        LOG.warnf(
            "captureRequirementDraft: soft-downgraded READY_FOR_PLAN without facts"
                + " conversationId=%s",
            conversationId);
      }
      String missingTextError = validateFactText(facts);
      if (missingTextError != null) {
        LOG.warnf(
            "captureRequirementDraft: validation failed conversationId=%s reason=%s",
            conversationId, missingTextError);
        return finish(conversationId, startMs, missingTextError);
      }
      String duplicateFactError = validateUniqueFacts(facts);
      if (duplicateFactError != null) {
        LOG.warnf(
            "captureRequirementDraft: validation failed conversationId=%s reason=%s",
            conversationId, duplicateFactError);
        store.recordCaptureRejection(conversationId, duplicateFactError);
        return finish(conversationId, startMs, duplicateFactError);
      }
      RequirementFlow capturedFlow =
          capture.flow() == null ? RequirementFlow.EMPTY : capture.flow();
      if (!capturedFlow.interactions().isEmpty()) {
        Optional<String> structureError = RequirementFlowValidator.validateStructure(capturedFlow);
        if (structureError.isPresent()) {
          LOG.warnf(
              "captureRequirementDraft: validation failed conversationId=%s reason=%s",
              conversationId, structureError.get());
          return finish(conversationId, startMs, structureError.get());
        }
        String topologyError = rejectTopologyFacts(facts);
        if (topologyError != null) {
          LOG.warnf(
              "captureRequirementDraft: validation failed conversationId=%s reason=%s",
              conversationId, topologyError);
          return finish(conversationId, startMs, topologyError);
        }
      }
      final List<RequirementFact> capturedFacts = facts;
      String duplicateCallError = validateUniqueServiceCallIds(facts);
      if (duplicateCallError != null) {
        LOG.warnf(
            "captureRequirementDraft: validation failed conversationId=%s reason=%s",
            conversationId, duplicateCallError);
        return finish(conversationId, startMs, duplicateCallError);
      }

      ApiHubRequirementRefs candidate =
          resolveApiHubCandidate(capture, previous, conversationId);

      if (decision == DraftDecision.BLOCKED && candidate != null) {
        // Agents often set BLOCKED after an empty catalog search even when searchApiOperations
        // already returned a clear package (candidate backfilled into ConversationApiHubCache).
        softDowngradedBlockedWithCandidate = true;
        decision = DraftDecision.NEEDS_INPUT;
        LOG.warnf(
            "captureRequirementDraft: soft-downgraded BLOCKED with recoverable apiHubCandidate"
                + " conversationId=%s packageId=%s",
            conversationId,
            candidate.packageId());
      }

      if (candidate != null) {
        // The pending import is offered as a decision, so it is not an open question to answer.
        openQuestions = List.of();
      }

      if (decision == DraftDecision.READY_FOR_PLAN && candidate != null) {
        softDowngradedForImport = true;
        decision = DraftDecision.NEEDS_INPUT;
        LOG.warnf(
            "captureRequirementDraft: soft-downgraded READY_FOR_PLAN with pending apiHubCandidate"
                + " conversationId=%s packageId=%s",
            conversationId,
            candidate.packageId());
      }

      if (decision == DraftDecision.READY_FOR_PLAN
          && openQuestions.isEmpty()
          && capturedFlow.interactions().isEmpty()
          && positiveServiceCalls(facts).isEmpty()) {
        softDowngradedForFlow = true;
        decision = DraftDecision.NEEDS_INPUT;
        openQuestions = List.of(FLOW_REQUIRED_OPEN_QUESTION);
        LOG.warnf(
            "captureRequirementDraft: soft-downgraded READY_FOR_PLAN without RequirementFlow"
                + " conversationId=%s",
            conversationId);
      }

      List<RequirementFact> positiveCalls = positiveServiceCalls(facts);
      recordAssessmentsFromListedOperations(positiveCalls, conversationId);
      resolveUnboundFromCatalog(capturedFlow, capturedFacts, conversationId);
      List<RequirementServiceCall> reconciledCalls =
          reconcileServiceCalls(facts, previous, conversationId);
      List<CatalogBindingHint> catalogBindings =
          capturedFlow.interactions().isEmpty()
              ? hintsFromResolvedCalls(reconciledCalls)
              : reconcileCatalogBindings(capturedFlow, previous, conversationId);
      List<RequirementServiceCall> unresolvedCalls =
          reconciledCalls.stream().filter(call -> call.catalogBinding() == null).toList();
      // Assessments decide whenever the draft names its service calls. The catalog-cache heuristic
      // below stays for drafts whose facts carry no SERVICE_CALL kind at all.
      boolean bindingMissing =
          positiveCalls.isEmpty()
              && requiresResolvedCatalogBinding(facts, catalogCache, conversationId);
      if (decision == DraftDecision.READY_FOR_PLAN
          && !capturedFlow.interactions().isEmpty()) {
        Optional<String> bindingError =
            RequirementFlowValidator.validateBindings(capturedFlow, facts, catalogBindings);
        if (bindingError.isPresent()) {
          softDowngradedForBinding = true;
          decision = DraftDecision.NEEDS_INPUT;
          if (openQuestions.isEmpty()) {
            openQuestions = List.of(bindingError.get());
          }
        }
      }

      if (decision == DraftDecision.READY_FOR_PLAN
          && (!unresolvedCalls.isEmpty() || bindingMissing)
          && !hasAllowedUploadedSpecs(conversationId)
          && capturedFlow.interactions().isEmpty()) {
        softDowngradedForBinding = true;
        decision = DraftDecision.NEEDS_INPUT;
        if (openQuestions.isEmpty()) {
          openQuestions = List.of(bindingOpenQuestion(unresolvedCalls, conversationId));
        }
        LOG.warnf(
            "captureRequirementDraft: soft-downgraded READY_FOR_PLAN with %d unresolved service"
                + " call(s) conversationId=%s",
            unresolvedCalls.size(),
            conversationId);
      }

      boolean catalogBindCheckpoint =
          catalogBindCheckpoint(capturedFlow, capturedFacts, conversationId);
      String invalidDecision =
          validateDecision(decision, openQuestions, candidate, catalogBindCheckpoint);
      if (invalidDecision != null) {
        LOG.warnf(
            "captureRequirementDraft: validation failed conversationId=%s reason=%s",
            conversationId, invalidDecision);
        return finish(conversationId, startMs, invalidDecision);
      }

      boolean importIntent = candidate != null || (previous != null && previous.importIntent());

      String owningInteractionId = null;
      if (candidate != null) {
        if (previous != null && previous.apiHubCandidateInteractionId() != null) {
          owningInteractionId = previous.apiHubCandidateInteractionId();
        } else if (!capturedFlow.interactions().isEmpty()) {
          List<String> unresolved =
              capturedFlow.interactions().stream()
                  .filter(
                      interaction ->
                          RequirementFlowValidator.requiresCatalogBinding(interaction, capturedFacts))
                  .map(RequirementFlow.Interaction::interactionId)
                  .filter(
                      id ->
                          catalogBindings.stream()
                              .noneMatch(hint -> id.equals(hint.interactionId())))
                  .toList();
          if (unresolved.size() == 1) {
            owningInteractionId = unresolved.getFirst();
          }
        } else if (reconciledCalls.size() == 1) {
          owningInteractionId = reconciledCalls.getFirst().serviceCallId();
        }
      }
      RequirementDraft draft =
          new RequirementDraft(
              softDowngradedForFacts
                      || softDowngradedForFlow
                      || softDowngradedForImport
                      || softDowngradedForBinding
                      || softDowngradedBlockedWithCandidate
                  ? false
                  : capture.complete(),
              capture.assembledText(),
              decision,
              openQuestions,
              SOURCE_SKILL_ID,
              sourceSkillVersion(conversationId),
              sourceSkillHash(conversationId),
              candidate,
              false,
              facts,
              importIntent,
              owningInteractionId,
              idsRequested(capture, previous),
              capturedFlow,
              catalogBindings);
      store.put(conversationId, draft);
      store.markCaptured(conversationId);
      if (resolutions != null) {
        Set<String> retained = new LinkedHashSet<>();
        if (!capturedFlow.interactions().isEmpty()) {
          for (RequirementFlow.Interaction interaction : capturedFlow.interactions()) {
            retained.add(interaction.interactionId());
          }
        } else {
          for (RequirementServiceCall call : reconciledCalls) {
            retained.add(call.serviceCallId());
          }
        }
        resolutions.retainInteractions(conversationId, retained);
      }
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
          .offerDraft(draft);

      LOG.infof(
          "captureRequirementDraft: stored draft conversationId=%s decision=%s complete=%s"
              + " openQuestions=%d facts=%d sourceSkill=%s sourceVersion=%s sourceHash=%s textChars=%d"
              + " hasApiHubCandidate=%s softDowngradedForFacts=%s"
              + " softDowngradedForFlow=%s"
              + " softDowngradedForImport=%s softDowngradedForBinding=%s"
              + " softDowngradedBlockedWithCandidate=%s",
          conversationId,
          draft.decision(),
          draft.complete(),
          draft.openQuestions().size(),
          draft.facts().size(),
          draft.sourceSkillId(),
          draft.sourceSkillVersion(),
          draft.sourceSkillHash(),
          draft.assembledText().length(),
          draft.apiHubCandidate() != null,
          softDowngradedForFacts,
          softDowngradedForFlow,
          softDowngradedForImport,
          softDowngradedForBinding,
          softDowngradedBlockedWithCandidate);
      String storedPreview =
          "Requirement draft captured (decision="
              + draft.decision()
              + ", complete="
              + draft.complete()
              + "): "
              + AiTraceLog.preview(draft.assembledText(), 160);
      if (softDowngradedBlockedWithCandidate) {
        return finish(
            conversationId,
            startMs,
            BLOCKED_WITH_CANDIDATE_SOFT_DOWNGRADE_PREFIX
                + BLOCKED_WITH_CANDIDATE_SOFT_DOWNGRADE_HINT
                + " "
                + storedPreview);
      }
      if (softDowngradedForImport) {
        return finish(
            conversationId,
            startMs,
            IMPORT_PENDING_SOFT_DOWNGRADE_PREFIX
                + IMPORT_PENDING_SOFT_DOWNGRADE_HINT
                + " "
                + storedPreview);
      }
      if (softDowngradedForBinding) {
        return finish(
            conversationId,
            startMs,
            BINDING_SOFT_DOWNGRADE_PREFIX
                + (capturedFlow.interactions().isEmpty()
                    ? describeUnresolvedCalls(unresolvedCalls)
                    : describeUnresolvedInteractions(
                        capturedFlow, facts, catalogBindings))
                + BINDING_SOFT_DOWNGRADE_HINT
                + " "
                + storedPreview);
      }
      if (softDowngradedForFlow) {
        return finish(
            conversationId,
            startMs,
            FLOW_SOFT_DOWNGRADE_PREFIX
                + FLOW_SOFT_DOWNGRADE_HINT
                + " "
                + storedPreview);
      }
      if (softDowngradedForFacts) {
        return finish(
            conversationId,
            startMs,
            FACTS_SOFT_DOWNGRADE_PREFIX + FACTS_SOFT_DOWNGRADE_HINT + " " + storedPreview);
      }
      return finish(conversationId, startMs, storedPreview);
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, "captureRequirementDraft", conversationId, System.currentTimeMillis() - startMs, e);
      return "Error capturing requirement draft: " + e.getMessage();
    }
  }

  private String finish(String conversationId, long startMs, String result) {
    ToolTraceLog.logToolComplete(
        LOG, "captureRequirementDraft", conversationId, System.currentTimeMillis() - startMs, result);
    return result;
  }

  private static String validateDecision(
      DraftDecision decision,
      List<String> openQuestions,
      ApiHubRequirementRefs candidate,
      boolean catalogBindCheckpoint) {
    boolean emptyQuestionsAllowed = candidate != null || catalogBindCheckpoint;
    if (decision == DraftDecision.NEEDS_INPUT
        && openQuestions.isEmpty()
        && !emptyQuestionsAllowed) {
      return "openQuestions is required when decision=NEEDS_INPUT";
    }
    if (decision == DraftDecision.READY_FOR_PLAN && !openQuestions.isEmpty()) {
      return "openQuestions must be empty when decision=READY_FOR_PLAN";
    }
    if (candidate != null && !candidate.hasImportableRefs()) {
      return "apiHubCandidate must include packageId, version, and operationId or documentId";
    }
    if (decision == DraftDecision.READY_FOR_PLAN && candidate != null) {
      return "READY_FOR_PLAN is not allowed while apiHubCandidate is pending import;"
          + " use NEEDS_INPUT until the user imports the specification";
    }
    return null;
  }

  private boolean catalogBindCheckpoint(
      RequirementFlow flow, List<RequirementFact> facts, String conversationId) {
    if (flow == null || flow.interactions().isEmpty()) {
      return false;
    }
    if (facts == null || facts.isEmpty()) {
      return false;
    }
    boolean catalogBacked = false;
    for (RequirementFlow.Interaction interaction : flow.interactions()) {
      InteractionAssessment assessment =
          resolutions == null
              ? null
              : resolutions.forInteraction(conversationId, interaction.interactionId()).orElse(null);
      if (assessment != null
          && (assessment.outcome() == InteractionAssessment.Outcome.INCOMPLETE
              || assessment.outcome() == InteractionAssessment.Outcome.AMBIGUOUS)) {
        return false;
      }
      if (RequirementFlowValidator.requiresCatalogBinding(interaction, facts)) {
        catalogBacked = true;
      }
    }
    return catalogBacked;
  }

  /**
   * Rebuilds the durable call list from the current facts. Order follows the facts; identity and
   * readiness use {@code serviceCallId}.
   */
  List<RequirementServiceCall> reconcileServiceCalls(
      List<RequirementFact> facts, RequirementDraft previous, String conversationId) {
    List<RequirementFact> calls = positiveServiceCalls(facts);
    Map<String, CatalogBindingHint> previousById = new LinkedHashMap<>();
    Map<String, RequirementFact> previousFactsById = new HashMap<>();
    if (previous != null) {
      for (CatalogBindingHint hint : previous.catalogBindings()) {
        previousById.put(hint.interactionId(), hint);
      }
      for (RequirementFact fact : previous.facts()) {
        if (fact != null
            && fact.polarity() == RequirementFactPolarity.POSITIVE
            && fact.needsCatalogBinding()) {
          previousFactsById.put(fact.serviceCallId(), fact);
        }
      }
    }
    List<RequirementServiceCall> reconciled = new ArrayList<>();
    for (RequirementFact fact : calls) {
      CatalogBindingHint priorHint = previousById.get(fact.serviceCallId());
      RequirementFact priorFact = previousFactsById.get(fact.serviceCallId());
      InteractionAssessment assessment =
          resolutions == null
              ? null
              : resolutions.forInteraction(conversationId, fact.serviceCallId()).orElse(null);
      CatalogBindingHint hint = bindingFor(fact, priorHint, priorFact, assessment);
      reconciled.add(
          new RequirementServiceCall(
              fact.serviceCallId(),
              fact.sourceFactId(),
              fact.participant(),
              fact.operation(),
              hint));
    }
    return List.copyOf(reconciled);
  }

  /**
   * The author's IDS answer, kept across turns. A capture that says nothing does not erase an
   * answer an earlier turn already recorded.
   */
  private static Boolean idsRequested(RequirementDraftCapture capture, RequirementDraft previous) {
    if (capture != null && capture.idsRequested() != null) {
      return capture.idsRequested();
    }
    return previous == null ? null : previous.idsRequested();
  }

  private static CatalogBindingHint bindingFor(
      RequirementFact fact,
      CatalogBindingHint priorHint,
      RequirementFact priorFact,
      InteractionAssessment assessment) {
    boolean identityUnchanged = priorFact != null && sameCallIdentity(fact, priorFact);
    if (assessment != null
        && assessment.isResolved()
        && assessment.binding() != null
        && (priorFact == null || sameCallIdentity(fact, priorFact))) {
      if (priorHint != null && sameCatalogIdentity(priorHint, assessment.binding())) {
        return priorHint;
      }
      return CatalogBindingHint.from(
          new RequirementServiceCall(
              fact.serviceCallId(),
              fact.sourceFactId(),
              fact.participant(),
              fact.operation()),
          assessment.binding(),
          "catalog",
          assessment.observedAt());
    }
    if (priorHint != null && identityUnchanged) {
      return priorHint;
    }
    return null;
  }

  private static boolean sameCallIdentity(RequirementFact left, RequirementFact right) {
    return sameField(left.participant(), right.participant())
        && sameField(left.operation(), right.operation())
        && sameField(left.httpMethod(), right.httpMethod())
        && sameField(left.path(), right.path());
  }

  private static boolean sameCatalogIdentity(
      CatalogBindingHint hint, CatalogMatch match) {
    return sameField(hint.systemId(), match.systemId())
        && sameField(hint.specificationGroupId(), match.specificationGroupId())
        && sameField(hint.specificationId(), match.specificationId())
        && sameField(hint.integrationOperationId(), match.integrationOperationId());
  }

  private static boolean sameField(String left, String right) {
    String a = left == null ? "" : left.trim();
    String b = right == null ? "" : right.trim();
    return a.equals(b);
  }

  /**
   * Records one resolved assessment per service-call fact when {@code listCatalogOperations} already
   * returned a unique match. Two outbound calls may share the same catalog operation; each fact
   * still gets its own assessment.
   */
  private void recordAssessmentsFromListedOperations(
      List<RequirementFact> calls, String conversationId) {
    if (resolutions == null || catalogCache == null || calls.isEmpty()) {
      return;
    }
    List<CatalogRestClient.OperationDto> listed = catalogCache.rememberedOperations(conversationId);
    if (listed.isEmpty()) {
      return;
    }
    for (RequirementFact call : calls) {
      if (resolutions
          .forInteraction(conversationId, call.serviceCallId())
          .filter(InteractionAssessment::isResolved)
          .isPresent()) {
        continue;
      }
      List<CatalogRestClient.OperationDto> matches = new ArrayList<>();
      for (CatalogRestClient.OperationDto operation : listed) {
        if (operation == null) {
          continue;
        }
        if (listedOperationMatches(conversationId, call, operation)) {
          matches.add(operation);
        }
      }
      if (matches.size() != 1) {
        continue;
      }
      CatalogRestClient.OperationDto operation = matches.getFirst();
      catalogMatchFromListed(conversationId, operation)
          .ifPresent(
              match ->
                  resolutions.remember(
                      conversationId,
                      InteractionAssessment.resolved(
                          call.serviceCallId(),
                          intentFrom(call),
                          match)));
    }
  }

  private boolean listedOperationMatches(
      String conversationId,
      RequirementFact call,
      CatalogRestClient.OperationDto operation) {
    String participant = CatalogStrings.blankToNull(call.participant());
    if (participant != null) {
      Optional<CatalogRestClient.SystemDto> system =
          catalogCache
              .findSpecificationOwnerSystemId(conversationId, operation.modelId())
              .flatMap(systemId -> catalogCache.findSystem(conversationId, systemId));
      if (system.isEmpty() || !serviceNameAgrees(participant, system.get().name())) {
        return false;
      }
    }
    String hint = CatalogStrings.percentDecode(CatalogStrings.blankToNull(call.operation()));
    if (hint == null) {
      hint = call.text();
    }
    return operationHintAgrees(hint, operation);
  }

  private Optional<CatalogMatch> catalogMatchFromListed(
      String conversationId, CatalogRestClient.OperationDto operation) {
    String specificationId = CatalogStrings.blankToNull(operation.modelId());
    if (specificationId == null) {
      return Optional.empty();
    }
    CatalogRestClient.SpecificationDto specification =
        catalogCache.findSpecification(conversationId, specificationId).orElse(null);
    String systemId =
        catalogCache
            .findSpecificationOwnerSystemId(conversationId, specificationId)
            .orElse(null);
    if (specification == null
        || CatalogStrings.blankToNull(specification.specificationGroupId()) == null
        || CatalogStrings.blankToNull(systemId) == null) {
      return Optional.empty();
    }
    CatalogRestClient.SystemDto system =
        catalogCache.findSystem(conversationId, systemId).orElse(null);
    return Optional.of(
        new CatalogMatch(
            systemId,
            specification.specificationGroupId(),
            specificationId,
            operation.id(),
            system == null ? "" : system.name(),
            system == null ? "" : system.protocol(),
            operation.method(),
            operation.path(),
            operation.name(),
            "catalog-listed:" + systemId + "/" + specificationId + "/" + operation.id()));
  }

  private static InteractionAssessment.Intent intentFrom(RequirementFact call) {
    return new InteractionAssessment.Intent(
        call.text(),
        call.participant(),
        call.operation(),
        call.httpMethod(),
        call.path());
  }

  private static boolean serviceNameAgrees(String required, String catalogName) {
    if (CatalogStrings.blankToNull(catalogName) == null) {
      return false;
    }
    String left = required.trim().toLowerCase(Locale.ROOT);
    String right = catalogName.trim().toLowerCase(Locale.ROOT);
    return left.equals(right) || right.contains(left) || left.contains(right);
  }

  private static boolean operationHintAgrees(
      String hint, CatalogRestClient.OperationDto operation) {
    String needle = CatalogStrings.percentDecode(hint);
    if (needle == null) {
      return false;
    }
    if (equalsIgnoreCase(needle, operation.id()) || equalsIgnoreCase(needle, operation.name())) {
      return true;
    }
    String lower = needle.toLowerCase(Locale.ROOT);
    String name = CatalogStrings.blankToNull(operation.name());
    return name != null && lower.contains(name.toLowerCase(Locale.ROOT));
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    return left != null && right != null && left.equalsIgnoreCase(right);
  }

  private static List<RequirementFact> positiveServiceCalls(List<RequirementFact> facts) {
    if (facts == null) {
      return List.of();
    }
    return facts.stream()
        .filter(Objects::nonNull)
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(RequirementFact::needsCatalogBinding)
        .toList();
  }

  /**
   * What to ask about the calls that are not resolved yet.
   *
   * <p>The question follows the outcome: a call whose intent is incomplete needs the fields it
   * lacks, and an ambiguous one needs a choice between candidates the catalog already returned.
   * Asking "which operation" for either of those wastes the turn the reader spends answering it.
   */
  private String bindingOpenQuestion(
      List<RequirementServiceCall> unresolvedCalls, String conversationId) {
    if (unresolvedCalls.isEmpty()) {
      return BINDING_REQUIRED_OPEN_QUESTION;
    }
    List<String> clarifications =
        unresolvedCalls.stream()
            .map(call -> describeUnresolvedCall(call) + ". " + clarification(call, conversationId))
            .toList();
    return String.join(" ", clarifications);
  }

  private static String describeUnresolvedCalls(List<RequirementServiceCall> unresolvedCalls) {
    if (unresolvedCalls.isEmpty()) {
      return "";
    }
    StringBuilder body = new StringBuilder("Unresolved service calls: ");
    for (int i = 0; i < unresolvedCalls.size(); i++) {
      if (i > 0) {
        body.append("; ");
      }
      body.append(describeUnresolvedCall(unresolvedCalls.get(i)));
    }
    return body.append(". ").toString();
  }

  private static String describeUnresolvedInteractions(
      RequirementFlow flow,
      List<RequirementFact> facts,
      List<CatalogBindingHint> catalogBindings) {
    Set<String> resolvedIds = new LinkedHashSet<>();
    for (CatalogBindingHint binding : catalogBindings) {
      resolvedIds.add(binding.interactionId());
    }
    List<RequirementFlow.Interaction> unresolved =
        flow.interactions().stream()
            .filter(interaction -> RequirementFlowValidator.requiresCatalogBinding(interaction, facts))
            .filter(interaction -> !resolvedIds.contains(interaction.interactionId()))
            .toList();
    if (unresolved.isEmpty()) {
      return "";
    }
    StringBuilder body = new StringBuilder("Unresolved interactions: ");
    for (int i = 0; i < unresolved.size(); i++) {
      if (i > 0) {
        body.append("; ");
      }
      RequirementFlow.Interaction interaction = unresolved.get(i);
      body.append("interactionId=")
          .append(interaction.interactionId())
          .append(", participant=")
          .append(interaction.participant())
          .append(", operation=")
          .append(interaction.operation());
    }
    return body.append(". ").toString();
  }

  private static String describeUnresolvedCall(RequirementServiceCall call) {
    return "serviceCallId="
        + call.serviceCallId()
        + ", participant="
        + call.participant()
        + ", operation="
        + call.operation();
  }

  private String clarification(RequirementServiceCall call, String conversationId) {
    InteractionAssessment assessment =
        resolutions == null
            ? null
            : resolutions.forInteraction(conversationId, call.serviceCallId()).orElse(null);
    String label = call.operation().isBlank() ? call.serviceCallId() : call.operation();
    if (assessment == null) {
      return "Which catalog operation should this chain call for \""
          + label
          + "\"? Resolve it in the local catalog before searching API Hub.";
    }
    return switch (assessment.outcome()) {
      case INCOMPLETE ->
          "For \""
              + label
              + "\", which "
              + String.join(", ", assessment.missingIntentFields())
              + " should the chain use?";
      case AMBIGUOUS ->
          "For \""
              + label
              + "\", which catalog operation is meant: "
              + String.join(", ", assessment.candidateOperationIds())
              + "?";
      default ->
          "Which catalog operation should this chain call for \""
              + label
              + "\"? Resolve it in the local catalog before searching API Hub.";
    };
  }

  private static boolean requiresResolvedCatalogBinding(
      List<RequirementFact> facts, ConversationCatalogCache catalogCache, String conversationId) {
    if (catalogCache != null && catalogCache.hasRememberedOperations(conversationId)) {
      return true;
    }
    if (facts == null) {
      return false;
    }
    return facts.stream()
        .anyMatch(
            fact ->
                fact != null
                    && fact.polarity() == RequirementFactPolarity.POSITIVE
                    && fact.kind() == RequirementFactKind.SERVICE_CALL);
  }

  private boolean hasAllowedUploadedSpecs(String conversationId) {
    if (conversationService == null || conversationId == null || conversationId.isBlank()) {
      return false;
    }
    List<String> keys = conversationService.getAllowedAttachmentKeys(conversationId);
    return keys != null && !keys.isEmpty();
  }

  private static String rejectTopologyFacts(List<RequirementFact> facts) {
    if (facts == null) {
      return null;
    }
    for (RequirementFact fact : facts) {
      if (fact == null) {
        continue;
      }
      if (fact.kind() == RequirementFactKind.ENDPOINT
          || fact.kind() == RequirementFactKind.SERVICE_CALL) {
        return "requirement facts must not include kind=ENDPOINT or kind=SERVICE_CALL";
      }
    }
    return null;
  }

  private static List<CatalogBindingHint> hintsFromResolvedCalls(
      List<RequirementServiceCall> calls) {
    List<CatalogBindingHint> hints = new ArrayList<>();
    for (RequirementServiceCall call : calls) {
      if (call.catalogBinding() != null) {
        hints.add(call.catalogBinding());
      }
    }
    return List.copyOf(hints);
  }

  /**
   * Binds unique local catalog matches before {@link #reconcileCatalogBindings} copies them onto
   * the draft. Does not search API Hub; unresolved outbound interactions still soft-downgrade.
   */
  private void resolveUnboundFromCatalog(
      RequirementFlow flow, List<RequirementFact> facts, String conversationId) {
    if (catalogOperationLookup == null
        || resolutions == null
        || conversationId == null
        || conversationId.isBlank()
        || flow.interactions().isEmpty()) {
      return;
    }
    List<String> named =
        flow.interactions().stream()
            .map(RequirementFlow.Interaction::operation)
            .filter(operation -> !operation.isBlank())
            .toList();
    for (RequirementFlow.Interaction interaction : flow.interactions()) {
      if (resolutions
          .forInteraction(conversationId, interaction.interactionId())
          .filter(InteractionAssessment::isResolved)
          .isPresent()) {
        continue;
      }
      if (RequirementFlowValidator.hasNativeInboundTriggerFact(interaction, facts)) {
        continue;
      }
      String capability =
          interaction.description().isBlank() ? interaction.operation() : interaction.description();
      InteractionAssessment.Intent intent =
          new InteractionAssessment.Intent(
              capability, interaction.participant(), interaction.operation(), null, null);
      if (!intent.missingFields().isEmpty()) {
        continue;
      }
      CatalogLookupResult result =
          catalogOperationLookup.resolve(
              new CatalogQuery(
                  intent.systemHint(),
                  null,
                  null,
                  intent.method(),
                  intent.path(),
                  intent.operationHint(),
                  null,
                  named));
      if (!(result instanceof CatalogLookupResult.Exact exact)) {
        continue;
      }
      Optional<CatalogOperationDirection> catalogDirection =
          CatalogOperationDirection.from(exact.match().protocol(), exact.match().method());
      if (catalogDirection.isEmpty()) {
        continue;
      }
      CatalogOperationDirection expected =
          interaction.direction() == RequirementFlow.Direction.INBOUND
              ? CatalogOperationDirection.PRODUCED_BY_SYSTEM
              : CatalogOperationDirection.CONSUMED_BY_SYSTEM;
      if (catalogDirection.get() != expected) {
        continue;
      }
      resolutions.remember(
          conversationId,
          InteractionAssessment.resolved(interaction.interactionId(), intent, exact.match()));
    }
  }

  private List<CatalogBindingHint> reconcileCatalogBindings(
      RequirementFlow flow, RequirementDraft previous, String conversationId) {
    if (flow.interactions().isEmpty()) {
      return previous == null ? List.of() : previous.catalogBindings();
    }
    Map<String, CatalogBindingHint> prior = new LinkedHashMap<>();
    if (previous != null) {
      for (CatalogBindingHint hint : previous.catalogBindings()) {
        prior.put(hint.interactionId(), hint);
      }
    }
    List<CatalogBindingHint> hints = new ArrayList<>();
    for (RequirementFlow.Interaction interaction : flow.interactions()) {
      InteractionAssessment assessment =
          resolutions == null
              ? null
              : resolutions.forInteraction(conversationId, interaction.interactionId()).orElse(null);
      if (assessment != null && assessment.isResolved() && assessment.binding() != null) {
        try {
          hints.add(
              CatalogBindingHint.from(
                  interaction, assessment.binding(), "catalog", assessment.observedAt()));
          continue;
        } catch (IllegalArgumentException ignored) {
          // Unknown catalog direction stays unbound so capture can return NEEDS_INPUT.
        }
      }
      CatalogBindingHint existing = prior.get(interaction.interactionId());
      if (existing != null) {
        hints.add(existing);
      }
    }
    return List.copyOf(hints);
  }

  private static String validateFactText(List<RequirementFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return null;
    }
    for (RequirementFact fact : facts) {
      if (fact != null && fact.text().isBlank()) {
        return "text is required for every fact";
      }
    }
    return null;
  }

  private static String validateUniqueFacts(List<RequirementFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return null;
    }
    Map<String, RequirementFact> firstById = new LinkedHashMap<>();
    for (RequirementFact fact : facts) {
      if (fact == null) {
        return "facts must not contain null entries";
      }
      RequirementFact previous = firstById.putIfAbsent(fact.sourceFactId(), fact);
      if (previous != null) {
        return "duplicate sourceFactId in facts: "
            + previous.kind()
            + " sourceFactId="
            + previous.sourceFactId()
            + " and "
            + fact.kind()
            + " sourceFactId="
            + fact.sourceFactId()
            + ". Call captureRequirementDraft again with unique sourceFactId values. Rename one"
            + " colliding fact or drop the duplicate.";
      }
    }
    return null;
  }

  private static String validateUniqueServiceCallIds(List<RequirementFact> facts) {
    Set<String> seen = new LinkedHashSet<>();
    for (RequirementFact fact : positiveServiceCalls(facts)) {
      String id = fact.serviceCallId();
      if (id == null || id.isBlank()) {
        return "serviceCallId is required for every SERVICE_CALL fact";
      }
      if (!seen.add(id)) {
        return "duplicate serviceCallId in facts: " + id;
      }
    }
    return null;
  }

  private ApiHubRequirementRefs resolveApiHubCandidate(
      RequirementDraftCapture capture, RequirementDraft previous, String conversationId) {
    if (capture.apiHubCandidate() != null) {
      return capture.apiHubCandidate();
    }
    if (previous != null && previous.apiHubCandidate() != null) {
      return previous.apiHubCandidate();
    }
    // ConversationApiHubCache is capture-time backfill only (ADR 0001 decision 1); draft
    // candidate remains the source of truth after this write.
    if (apiHubCache == null) {
      return null;
    }
    return apiHubCache.latestCandidate(conversationId).orElse(null);
  }

  private String sourceSkillVersion(String conversationId) {
    return store
        .get(conversationId)
        .map(RequirementDraft::sourceSkillVersion)
        .filter(version -> version != null && !version.isBlank())
        .orElseGet(this::activePackVersion);
  }

  private String sourceSkillHash(String conversationId) {
    return store
        .get(conversationId)
        .map(RequirementDraft::sourceSkillHash)
        .filter(hash -> hash != null && !hash.isBlank() && !"unknown".equals(hash))
        .orElseGet(this::activeSkillHash);
  }

  private String activePackVersion() {
    if (repository == null || repository.activeVersion() == null) {
      return "unknown";
    }
    return repository.activeVersion().normalized();
  }

  private String activeSkillHash() {
    if (repository == null) {
      return "unknown";
    }
    String sourcePath = "skills/" + SOURCE_SKILL_ID + "/SKILL.md";
    try {
      return repository
          .loadManifest()
          .fileChecksums()
          .getOrDefault(sourcePath, "unknown");
    } catch (RuntimeException e) {
      LOG.warnf(e, "Failed to resolve requirement draft source skill hash source=%s", sourcePath);
      return "unknown";
    }
  }
}
