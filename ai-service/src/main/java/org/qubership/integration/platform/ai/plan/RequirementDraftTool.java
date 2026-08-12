package org.qubership.integration.platform.ai.plan;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;
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
      "Which catalog operation should this chain call? Set catalogBinding from listCatalogOperations.";

  static final String BINDING_SOFT_DOWNGRADE_PREFIX =
      "Requirement draft stored as NEEDS_INPUT (not READY_FOR_PLAN): catalogBinding was missing"
          + " after catalog operations were loaded. ";

  static final String BINDING_SOFT_DOWNGRADE_HINT =
      "In this same turn, call captureRequirementDraft again with decision=READY_FOR_PLAN and"
          + " catalogBinding (systemId, specificationId, specificationGroupId,"
          + " integrationOperationId) taken from searchCatalogSystems / getApiSpecifications /"
          + " listCatalogOperations tool results. Do not invent UUIDs.";

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

  @Inject
  RequirementDraftTool(
      RequirementDraftStore store,
      QipKnowledgePackRepository repository,
      ConversationCatalogCache catalogCache,
      org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache apiHubCache) {
    this.store = store;
    this.repository = repository;
    this.catalogCache = catalogCache;
    this.apiHubCache = apiHubCache;
  }

  RequirementDraftTool(RequirementDraftStore store) {
    this(store, null, null, null);
  }

  RequirementDraftTool(RequirementDraftStore store, QipKnowledgePackRepository repository) {
    this(store, repository, null, null);
  }

  static RequirementDraftTool withCache(
      RequirementDraftStore store, ConversationCatalogCache catalogCache) {
    return new RequirementDraftTool(store, null, catalogCache, null);
  }

  static RequirementDraftTool withCaches(
      RequirementDraftStore store,
      ConversationCatalogCache catalogCache,
      org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache apiHubCache) {
    return new RequirementDraftTool(store, null, catalogCache, apiHubCache);
  }

  @Tool("""
      Capture the accumulated requirement draft decision for this conversation in the same turn.
      Do not pass conversationId: the server binds the draft to the current chat session.
      Pass the full accumulated vision text every time. The store replaces the previous draft.
      Set decision to NEEDS_INPUT when one user answer is still required, READY_FOR_PLAN only
      when no open questions remain, or BLOCKED when the request cannot proceed without an
      unavailable artifact or external decision.
      Keep openQuestions empty for READY_FOR_PLAN.
      READY_FOR_PLAN requires facts: each item needs polarity (POSITIVE or NEGATIVE) and text.
      Distill facts from assembledText yourself; never ask the user for polarity labels.
      When READY_FOR_PLAN is sent without facts, the server soft-stores NEEDS_INPUT — retry the
      same turn with facts, or keep NEEDS_INPUT with one open question.
      Optional fact fields: kind (GOAL, ENDPOINT, PARAMETER, BEHAVIOR, CONSTRAINT, CAPABILITY,
      VISIBILITY, ROUTING, SERVICE_CALL), capabilityKey, sourceFactId.
      When catalog tools return a single clear system/spec/operation match, set catalogBinding
      with systemId, specificationId, specificationGroupId, and integrationOperationId from those
      tool results (never invent UUIDs). catalogBinding allows READY_FOR_PLAN.
      When catalog lookup misses but API Hub returns a match, call selectApiHubCandidate with
      packageId, version, and operationId or documentId from the search hit (do not put
      apiHubCandidate on this capture). Keep decision=NEEDS_INPUT and leave openQuestions empty;
      the server offers the import as a decision card.
      Do not set decision=READY_FOR_PLAN while an API Hub candidate is pending import.
      After a successful READY_FOR_PLAN capture in this turn, do not call captureRequirementDraft
      again and do not repeat the ready-for-planning assistant text.
      {
        "complete": true,
        "decision": "READY_FOR_PLAN",
        "assembledText": "HTTP GET /greetings returns Hello world via script; no service calls.",
        "openQuestions": [],
        "facts": [
          {"polarity": "POSITIVE", "kind": "ENDPOINT", "text": "GET /greetings"},
          {"polarity": "NEGATIVE", "kind": "CONSTRAINT", "text": "No service calls"}
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
      String duplicateFactError = validateUniqueFacts(facts);
      if (duplicateFactError != null) {
        LOG.warnf(
            "captureRequirementDraft: validation failed conversationId=%s reason=%s",
            conversationId, duplicateFactError);
        return finish(conversationId, startMs, duplicateFactError);
      }

      ResolvedCatalogBinding binding =
          ResolvedCatalogBinding.enrichFromCache(
              catalogCache, conversationId, resolveCatalogBinding(capture, previous));
      String bindingError = validateCatalogBinding(conversationId, binding);
      if (bindingError != null) {
        // ADR 0001: prefer API Hub candidate (capture / previous / cache backfill) over a bad
        // catalogBinding. Agents often pass catalogBinding after searchApiOperations instead of
        // apiHubCandidate; rejecting the whole capture blocked IMPORT_PENDING.
        ApiHubRequirementRefs recoverable =
            resolveApiHubCandidate(capture, previous, conversationId);
        if (recoverable != null) {
          LOG.warnf(
              "captureRequirementDraft: ignoring invalid catalogBinding conversationId=%s"
                  + " reason=%s (preferring apiHubCandidate packageId=%s)",
              conversationId, bindingError, recoverable.packageId());
          binding = null;
        } else {
          LOG.warnf(
              "captureRequirementDraft: catalogBinding rejected conversationId=%s reason=%s",
              conversationId, bindingError);
          return finish(conversationId, startMs, bindingError);
        }
      }
      ApiHubRequirementRefs candidate =
          binding != null ? null : resolveApiHubCandidate(capture, previous, conversationId);

      if (decision == DraftDecision.BLOCKED && candidate != null && binding == null) {
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

      if (candidate != null && binding == null) {
        // The pending import is offered as a decision, so it is not an open question to answer.
        openQuestions = List.of();
      }

      if (decision == DraftDecision.READY_FOR_PLAN && candidate != null && binding == null) {
        softDowngradedForImport = true;
        decision = DraftDecision.NEEDS_INPUT;
        LOG.warnf(
            "captureRequirementDraft: soft-downgraded READY_FOR_PLAN with pending apiHubCandidate"
                + " conversationId=%s packageId=%s",
            conversationId,
            candidate.packageId());
      }

      if (decision == DraftDecision.READY_FOR_PLAN
          && binding == null
          && catalogCache != null
          && catalogCache.hasRememberedOperations(conversationId)) {
        softDowngradedForBinding = true;
        decision = DraftDecision.NEEDS_INPUT;
        if (openQuestions.isEmpty()) {
          openQuestions = List.of(BINDING_REQUIRED_OPEN_QUESTION);
        }
        LOG.warnf(
            "captureRequirementDraft: soft-downgraded READY_FOR_PLAN without catalogBinding"
                + " after catalog operations were loaded conversationId=%s",
            conversationId);
      }

      String invalidDecision = validateDecision(decision, openQuestions, candidate, binding);
      if (invalidDecision != null) {
        LOG.warnf(
            "captureRequirementDraft: validation failed conversationId=%s reason=%s",
            conversationId, invalidDecision);
        return finish(conversationId, startMs, invalidDecision);
      }

      boolean importIntent =
          binding != null
              ? false
              : (candidate != null || (previous != null && previous.importIntent()));

      RequirementDraft draft =
          new RequirementDraft(
              softDowngradedForFacts
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
              binding,
              false,
              facts,
              importIntent);
      store.put(conversationId, draft);
      store.markCaptured(conversationId);
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
          .offerDraft(draft);

      LOG.infof(
          "captureRequirementDraft: stored draft conversationId=%s decision=%s complete=%s"
              + " openQuestions=%d facts=%d sourceSkill=%s sourceVersion=%s sourceHash=%s textChars=%d"
              + " hasCatalogBinding=%s hasApiHubCandidate=%s softDowngradedForFacts=%s"
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
          draft.catalogBinding() != null,
          draft.apiHubCandidate() != null,
          softDowngradedForFacts,
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
            BINDING_SOFT_DOWNGRADE_PREFIX + BINDING_SOFT_DOWNGRADE_HINT + " " + storedPreview);
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
      ResolvedCatalogBinding binding) {
    boolean pendingImport = candidate != null && binding == null;
    if (decision == DraftDecision.NEEDS_INPUT && openQuestions.isEmpty() && !pendingImport) {
      return "openQuestions is required when decision=NEEDS_INPUT";
    }
    if (decision == DraftDecision.READY_FOR_PLAN && !openQuestions.isEmpty()) {
      return "openQuestions must be empty when decision=READY_FOR_PLAN";
    }
    if (candidate != null && !candidate.hasImportableRefs()) {
      return "apiHubCandidate must include packageId, version, and operationId or documentId";
    }
    if (decision == DraftDecision.READY_FOR_PLAN && candidate != null && binding == null) {
      return "READY_FOR_PLAN is not allowed while apiHubCandidate is pending import;"
          + " use NEEDS_INPUT until the user imports the specification";
    }
    return null;
  }

  private static String validateUniqueFacts(List<RequirementFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return null;
    }
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (RequirementFact fact : facts) {
      if (fact == null) {
        return "facts must not contain null entries";
      }
      if (!seen.add(fact.sourceFactId())) {
        return "duplicate sourceFactId in facts: " + fact.sourceFactId();
      }
    }
    return null;
  }

  private String validateCatalogBinding(String conversationId, ResolvedCatalogBinding binding) {
    if (binding == null) {
      return null;
    }
    String systemId = CatalogStrings.blankToNull(binding.systemId());
    String specificationId = CatalogStrings.blankToNull(binding.specificationId());
    if (systemId == null || specificationId == null) {
      return "catalogBinding requires systemId and specificationId from catalog tools";
    }
    if (catalogCache == null) {
      return null;
    }
    if (!catalogCache.hasRememberedSystems(conversationId)) {
      return "catalogBinding rejected: call searchCatalogSystems first, then set IDs from tool"
          + " results (do not invent UUIDs)";
    }
    if (catalogCache.findSystem(conversationId, systemId).isEmpty()) {
      return "catalogBinding.systemId was not returned by searchCatalogSystems in this"
          + " conversation";
    }
    if (catalogCache.hasRememberedSpecifications(conversationId)
        && !catalogCache.isKnownSpecificationId(conversationId, specificationId)) {
      return "catalogBinding.specificationId was not returned by getApiSpecifications in this"
          + " conversation";
    }
    String operationId = CatalogStrings.blankToNull(binding.integrationOperationId());
    if (operationId != null
        && catalogCache.hasRememberedSpecifications(conversationId)
        && catalogCache.findOperation(conversationId, operationId).isEmpty()) {
      return "catalogBinding.integrationOperationId was not returned by listCatalogOperations"
          + " in this conversation";
    }
    String systemType = CatalogStrings.blankToNull(binding.systemType());
    if (systemType == null) {
      return "catalogBinding.systemType could not be resolved from catalog tools;"
          + " call searchCatalogSystems first";
    }
    if (!ResolvedCatalogBinding.isAllowedSystemType(systemType)) {
      return "catalogBinding.systemType must be INTERNAL, EXTERNAL, or IMPLEMENTED";
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

  private static ResolvedCatalogBinding resolveCatalogBinding(
      RequirementDraftCapture capture, RequirementDraft previous) {
    if (capture.catalogBinding() != null) {
      return capture.catalogBinding();
    }
    if (previous == null) {
      return null;
    }
    ApiHubRequirementRefs candidate =
        capture.apiHubCandidate() != null
            ? capture.apiHubCandidate()
            : previous.apiHubCandidate();
    if (candidate != null
        && previous.apiHubCandidate() != null
        && !Objects.equals(previous.apiHubCandidate().packageId(), candidate.packageId())) {
      return null;
    }
    return previous.catalogBinding();
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
