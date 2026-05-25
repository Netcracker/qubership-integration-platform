package org.qubership.integration.platform.ai.chat.chainplan;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertyKeysValidator;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Conversation-scoped working state: latest captured
 * {@link ChainImplementationPlan} JSON plus a
 * short archive of superseded revisions. Delegates storage, prompt appendix,
 * open-debt merge, and
 * user intent.
 */
@ApplicationScoped
public class ActiveChainPlanService {

  private static final Logger LOG = Logger.getLogger(ActiveChainPlanService.class);
  private static final String FIELD_API_HUB_REQUIRED = "apiHubRequired";
  private static final String FIELD_API_HUB_REASON = "apiHubReason";

  private final ObjectMapper objectMapper;
  private final ChainPlanPropertyKeysValidator chainPlanPropertyKeysValidator;
  private final ConversationPlanningDiaryService planningDiaryService;
  private final ConversationPlanStateStore stateStore;
  private final ChainPlanPromptAppendix promptAppendix;
  private final PlanApprovalGate planApprovalGate;
  private final ChainPlanBindingPreflightService bindingPreflightService;

  @Inject
  public ActiveChainPlanService(
      ObjectMapper objectMapper,
      ChainPlanPropertyKeysValidator chainPlanPropertyKeysValidator,
      ConversationPlanningDiaryService planningDiaryService,
      ConversationPlanStateStore stateStore,
      ChainPlanPromptAppendix promptAppendix,
      PlanApprovalGate planApprovalGate,
      ChainPlanBindingPreflightService bindingPreflightService) {
    this.objectMapper = objectMapper;
    this.chainPlanPropertyKeysValidator = chainPlanPropertyKeysValidator;
    this.planningDiaryService = planningDiaryService;
    this.stateStore = stateStore;
    this.promptAppendix = promptAppendix;
    this.planApprovalGate = planApprovalGate;
    this.bindingPreflightService = bindingPreflightService;
  }

  public void onUserMessage(String conversationId, String userText) {
    if (conversationId == null
        || conversationId.isBlank()
        || userText == null
        || userText.isBlank()) {
      return;
    }
    // Attachment bodies (inlined IDS) may contain "implement chain …" examples; do
    // not treat them
    // as a new user request.
    String userIntentLine = PlanApprovalGate.extractIntent(userText);
    stateStore.compute(
        conversationId,
        (id, existing) -> {
          ConversationPlanStateStore.ConversationPlanState s = existing != null ? existing : stateStore.newState();
          Optional<String> mentioned = ChainPlanUserIntent.extractChainNameCandidate(userIntentLine);
          if (s.active != null
              && ChainPlanUserIntent.shouldArchiveForNewChain(
                  userIntentLine, s.active.chainName(), mentioned)) {
            stateStore.archiveActive(s);
          }
          tryApproveFromChat(conversationId, s, userText);
          return s;
        });
  }

  public void applyHitlAgreeOptionChosen(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    stateStore.compute(
        conversationId,
        (id, existing) -> {
          ConversationPlanStateStore.ConversationPlanState s = existing != null ? existing : stateStore.newState();
          if (s.active != null && !s.approved) {
            tryMarkPlanApproved(conversationId, s);
          } else if (s.active == null && !s.approved) {
            s.pendingHitlPlanApproval = true;
            LOG.infof(
                "HITL Agree deferred until plan capture: conversationId=%s",
                conversationId);
          }
          return s;
        });
  }

  /**
   * Publishes a {@link ChainImplementationPlan} from raw JSON (fenced markdown or
   * normalized JSON).
   * Normalizes legacy root-level {@code name}/{@code description} into
   * {@code chain}.
   */
  public PlanPublicationOutcome publishFromJson(String conversationId, String planJson) {
    Optional<PlanPublicationOutcome> argsFailure = publishArgsFailure(conversationId, planJson);
    if (argsFailure.isPresent()) {
      return argsFailure.get();
    }
    String jsonPayload = unwrapPlanJson(planJson);
    try {
      PreparedPlanPublication prepared = preparePlanPublication(conversationId, jsonPayload);
      if (!prepared.isValid()) {
        return prepared.validationFailure();
      }
      return storePreparedPlan(conversationId, jsonPayload, prepared);
    } catch (Exception e) {
      LOG.warnf(
          e, "Chain plan publish failed for conversationId=%s", conversationId);
      return PlanPublicationOutcome.failure(
          "PLAN_VALIDATION_ERROR", "Invalid ChainImplementationPlan JSON: " + e.getMessage());
    }
  }

  private Optional<PlanPublicationOutcome> publishArgsFailure(
      String conversationId, String planJson) {
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.of(
          PlanPublicationOutcome.failure("INVALID_ARGUMENT", "conversationId is required"));
    }
    if (planJson == null || planJson.isBlank()) {
      return Optional.of(
          PlanPublicationOutcome.failure("INVALID_ARGUMENT", "planJson is required"));
    }
    if (unwrapPlanJson(planJson).isEmpty()) {
      return Optional.of(
          PlanPublicationOutcome.failure("INVALID_ARGUMENT", "planJson is empty"));
    }
    return Optional.empty();
  }

  private static String unwrapPlanJson(String planJson) {
    return ChainPlanJsonNormalizer.unwrapMarkdownFences(planJson).trim();
  }

  private PreparedPlanPublication preparePlanPublication(
      String conversationId, String jsonPayload) throws Exception {
    JsonNode root = parsePlanRoot(jsonPayload);
    ChainImplementationPlan plan = objectMapper.treeToValue(root, ChainImplementationPlan.class);
    Optional<PlanPublicationOutcome> validationFailure = validateParsedPlan(plan);
    if (validationFailure.isPresent()) {
      return PreparedPlanPublication.invalid(validationFailure.get());
    }
    enrichSourceIdsFromPlanningDiary(conversationId, plan);
    ChainPlanBindingPreflightService.PreflightResult bindingPreflight = bindingPreflightService
        .enrichOperationBindings(plan);
    String chainName = plan.getChain().getName();
    List<PlanOpenItem> mergedOpen = mergePlanOpenDebt(plan, bindingPreflight);
    ActiveChainPlanSnapshot snapshot = new ActiveChainPlanSnapshot(
        UUID.randomUUID().toString(),
        chainName,
        optionalRootBoolean(root, FIELD_API_HUB_REQUIRED),
        optionalRootText(root, FIELD_API_HUB_REASON),
        plan,
        Instant.now(),
        List.of(),
        mergedOpen);
    return new PreparedPlanPublication(null, snapshot, plan, chainName, mergedOpen);
  }

  private JsonNode parsePlanRoot(String jsonPayload) throws Exception {
    JsonFactory jsonFactory = objectMapper.getFactory();
    try (JsonParser parser = jsonFactory.createParser(jsonPayload)) {
      parser.enable(JsonParser.Feature.ALLOW_COMMENTS);
      JsonNode root = objectMapper.readTree(parser);
      return ChainPlanJsonNormalizer.normalizeRoot(objectMapper, root);
    }
  }

  private static Optional<PlanPublicationOutcome> validateParsedPlan(
      ChainImplementationPlan plan) {
    if (!isSubstantivePlan(plan)) {
      return Optional.of(
          PlanPublicationOutcome.failure(
              "PLAN_VALIDATION_ERROR",
              "ChainImplementationPlan must include a non-empty elements array"));
    }
    if (plan.getChain() == null
        || plan.getChain().getName() == null
        || plan.getChain().getName().isBlank()) {
      return Optional.of(
          PlanPublicationOutcome.failure(
              "PLAN_VALIDATION_ERROR", "chain.name is required and must be non-empty"));
    }
    return Optional.empty();
  }

  private List<PlanOpenItem> mergePlanOpenDebt(
      ChainImplementationPlan plan,
      ChainPlanBindingPreflightService.PreflightResult bindingPreflight) {
    List<PlanOpenItem> openFromSanitize = chainPlanPropertyKeysValidator.sanitizeAndCollectUnknownKeys(plan);
    return ChainPlanOpenDebtMerge.mergeDistinctOpenItems(
        openFromSanitize,
        ChainPlanOpenDebtMerge.mergeDistinctOpenItems(
            ChainPlanOpenDebtMerge.mergeDistinctOpenItems(
                ChainPlanOpenDebtMerge.collectServiceBindingUnresolvedItems(plan),
                bindingPreflight.openItems()),
            ChainPlanOpenDebtMerge.collectMissingRuntimeConnectionsItems(plan)));
  }

  private PlanPublicationOutcome storePreparedPlan(
      String conversationId,
      String jsonPayload,
      PreparedPlanPublication prepared) {
    ActiveChainPlanSnapshot snapshot = prepared.snapshot();
    ChainImplementationPlan plan = prepared.plan();
    String chainName = prepared.chainName();

    AtomicBoolean storedNewCapture = new AtomicBoolean(false);
    AtomicReference<PlanPublicationOutcome> outcomeRef = new AtomicReference<>();
    stateStore.compute(
        conversationId,
        (id, state) -> applyPlanCaptureToState(
            conversationId,
            jsonPayload,
            prepared,
            state != null ? state : stateStore.newState(),
            storedNewCapture,
            outcomeRef));
    PlanPublicationOutcome outcome = outcomeRef.get();
    if (outcome == null) {
      return PlanPublicationOutcome.failure(
          "TOOL_EXECUTION_ERROR", "Failed to store active plan");
    }
    if (storedNewCapture.get()) {
      LOG.infof(
          "Captured ChainImplementationPlan: conversationId=%s, planId=%s, chain=%s, elements=%d",
          conversationId,
          snapshot.planId(),
          chainName,
          plan.getElements().size());
    }
    return outcome;
  }

  private ConversationPlanStateStore.ConversationPlanState applyPlanCaptureToState(
      String conversationId,
      String jsonPayload,
      PreparedPlanPublication prepared,
      ConversationPlanStateStore.ConversationPlanState state,
      AtomicBoolean storedNewCapture,
      AtomicReference<PlanPublicationOutcome> outcomeRef) {
    ActiveChainPlanSnapshot snapshot = prepared.snapshot();
    ChainImplementationPlan plan = prepared.plan();
    String chainName = prepared.chainName();
    List<PlanOpenItem> mergedOpen = prepared.mergedOpen();

    if (jsonPayload.equals(state.lastCapturedPlanJson)
        && state.active != null
        && !state.approved) {
      applyPendingHitlPlanApproval(conversationId, state);
      ActiveChainPlanSnapshot active = state.active;
      int openCount = countNonDismissedOpen(active.openItems());
      outcomeRef.set(
          PlanPublicationOutcome.success(
              active.planId(),
              active.chainName(),
              active.plan().getElements() != null ? active.plan().getElements().size() : 0,
              openCount));
      return state;
    }
    if (state.active != null) {
      stateStore.pushArchive(state, state.active);
    }
    if (state.approved) {
      state.approved = false;
      LOG.infof(
          "Plan approval revoked (new plan capture): conversationId=%s, newPlanId=%s, chain=%s",
          conversationId, snapshot.planId(), chainName);
    }
    resetImplementGateFlags(state);
    state.lastMergedOpenDebtFingerprint = null;
    state.active = snapshot;
    state.lastCapturedPlanJson = jsonPayload;
    storedNewCapture.set(true);
    applyPendingHitlPlanApproval(conversationId, state);
    int openCount = countNonDismissedOpen(mergedOpen);
    outcomeRef.set(
        PlanPublicationOutcome.success(
            snapshot.planId(), chainName, plan.getElements().size(), openCount));
    return state;
  }

  private static Boolean optionalRootBoolean(JsonNode root, String field) {
    return root.has(field) && !root.get(field).isNull() ? root.get(field).asBoolean() : null;
  }

  private static String optionalRootText(JsonNode root, String field) {
    return root.has(field) && !root.get(field).isNull() ? root.get(field).asText() : null;
  }

  private record PreparedPlanPublication(
      PlanPublicationOutcome validationFailure,
      ActiveChainPlanSnapshot snapshot,
      ChainImplementationPlan plan,
      String chainName,
      List<PlanOpenItem> mergedOpen) {

    boolean isValid() {
      return validationFailure == null;
    }

    static PreparedPlanPublication invalid(PlanPublicationOutcome failure) {
      return new PreparedPlanPublication(failure, null, null, null, null);
    }
  }

  /**
   * Captures a {@link ChainImplementationPlan} from assistant markdown when a
   * fenced JSON block is
   * present. Safe to call while the SSE stream is still open (skips identical
   * JSON re-capture).
   */
  public void captureFromAssistantText(String conversationId, String assistantText) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    Optional<String> json = ChainImplementationPlanCapture.findLatestPlanJson(assistantText);
    if (json.isEmpty()) {
      return;
    }
    PlanPublicationOutcome outcome = publishFromJson(conversationId, json.get());
    if (!outcome.captured()) {
      LOG.debugf(
          "Markdown plan capture skipped for conversationId=%s: %s",
          conversationId, outcome.failureMessage());
    }
  }

  private static int countNonDismissedOpen(List<PlanOpenItem> openItems) {
    if (openItems == null) {
      return 0;
    }
    return (int) openItems.stream().filter(o -> !o.dismissedByUser()).count();
  }

  private void applyPendingHitlPlanApproval(
      String conversationId, ConversationPlanStateStore.ConversationPlanState state) {
    if (!state.pendingHitlPlanApproval || state.approved || state.active == null) {
      return;
    }
    state.pendingHitlPlanApproval = false;
    if (tryMarkPlanApproved(conversationId, state)) {
      LOG.infof(
          "Applied deferred HITL plan approval: conversationId=%s, planId=%s",
          conversationId, state.active.planId());
    }
  }

  public Optional<ActiveChainPlanSnapshot> getActive(String conversationId) {
    return stateStore.get(conversationId).map(s -> s.active).filter(a -> a != null);
  }

  public boolean updateActivePlan(String conversationId, ChainImplementationPlan mutatedPlan) {
    if (conversationId == null || conversationId.isBlank() || mutatedPlan == null) {
      return false;
    }
    AtomicBoolean updated = new AtomicBoolean(false);
    stateStore.computeIfPresent(
        conversationId,
        (id, state) -> {
          if (state.active == null) {
            return state;
          }
          ActiveChainPlanSnapshot prev = state.active;
          state.active = new ActiveChainPlanSnapshot(
              prev.planId(),
              prev.chainName(),
              prev.apiHubRequired(),
              prev.apiHubReason(),
              mutatedPlan,
              Instant.now(),
              prev.rejectionErrors(),
              prev.openItems());
          updated.set(true);
          return state;
        });
    return updated.get();
  }

  public boolean isApproved(String conversationId) {
    return stateStore.get(conversationId).map(s -> s.approved).orElse(false);
  }

  public boolean needsImplementGateHitl(String conversationId) {
    return stateStore
        .get(conversationId)
        .map(
            s -> s.active != null
                && s.approved
                && s.implementGatePending
                && !s.implementGateAcknowledged)
        .orElse(false);
  }

  /**
   * @return true when Gate 2 was acknowledged; false when blocked by open plan
   *         debt
   */
  public boolean acknowledgeImplementGate(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return false;
    }
    AtomicBoolean acked = new AtomicBoolean(false);
    stateStore.computeIfPresent(
        conversationId,
        (id, state) -> {
          if (state.active != null && ChainPlanOpenDebtMerge.hasBlockingOpenDebt(state.active)) {
            LOG.warnf(
                "Implement gate blocked (open plan debt): conversationId=%s planId=%s fp=%s",
                conversationId,
                state.active.planId(),
                ChainPlanOpenDebtMerge.fingerprintNonDismissedOpenItems(state.active.openItems()));
            return state;
          }
          state.implementGateAcknowledged = true;
          state.implementGatePending = false;
          acked.set(true);
          LOG.infof(
              "Implement gate acknowledged: conversationId=%s, planId=%s",
              conversationId, state.active != null ? state.active.planId() : "(none)");
          return state;
        });
    return acked.get();
  }

  public void clearImplementGatePending(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    stateStore.computeIfPresent(
        conversationId,
        (id, state) -> {
          state.implementGatePending = false;
          LOG.infof(
              "Implement gate cleared (modify plan): conversationId=%s, planId=%s",
              conversationId, state.active != null ? state.active.planId() : "(none)");
          return state;
        });
  }

  public boolean approvePlanForBuild(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return false;
    }
    AtomicBoolean ok = new AtomicBoolean(false);
    stateStore.computeIfPresent(
        conversationId,
        (id, state) -> {
          if (state.active == null) {
            return state;
          }
          if (ChainPlanOpenDebtMerge.hasBlockingOpenDebt(state.active)) {
            LOG.warnf(
                "Plan approval for build blocked (open plan debt): conversationId=%s planId=%s"
                    + " fp=%s",
                conversationId,
                state.active.planId(),
                ChainPlanOpenDebtMerge.fingerprintNonDismissedOpenItems(state.active.openItems()));
            return state;
          }
          state.approved = true;
          state.implementGatePending = false;
          state.implementGateAcknowledged = true;
          ok.set(true);
          LOG.infof(
              "Plan approved for build (UI): conversationId=%s, planId=%s, chainName=%s",
              conversationId,
              state.active.planId(),
              state.active.chainName() != null ? state.active.chainName() : "(unnamed)");
          return state;
        });
    return ok.get();
  }

  public ChainPlanStatus getPlanStatus(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return new ChainPlanStatus(false, false, null, null, null, 0);
    }
    Optional<ConversationPlanStateStore.ConversationPlanState> stateOpt = stateStore.get(conversationId);
    if (stateOpt.isEmpty() || stateOpt.get().active == null) {
      return new ChainPlanStatus(false, false, null, null, null, 0);
    }
    ConversationPlanStateStore.ConversationPlanState state = stateOpt.get();
    ActiveChainPlanSnapshot snap = state.active;
    int openCount = snap.openItems() == null
        ? 0
        : (int) snap.openItems().stream().filter(o -> !o.dismissedByUser()).count();
    return new ChainPlanStatus(
        true, state.approved, snap.planId(), snap.chainName(), snap.updatedAt(), openCount);
  }

  public boolean isImplementGatePending(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return false;
    }
    return stateStore.get(conversationId).map(s -> s.implementGatePending).orElse(false);
  }

  public String formatPromptAppendix(String conversationId) {
    Optional<ActiveChainPlanSnapshot> snap = getActive(conversationId);
    if (snap.isEmpty()) {
      return "";
    }
    ConversationPlanStateStore.ConversationPlanState planState = stateStore.get(conversationId).orElse(null);
    return promptAppendix.format(conversationId, snap.get(), planState);
  }

  public String describeActiveForLog(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return "(none)";
    }
    Optional<ConversationPlanStateStore.ConversationPlanState> stateOpt = stateStore.get(conversationId);
    if (stateOpt.isEmpty() || stateOpt.get().active == null) {
      return "(none)";
    }
    ConversationPlanStateStore.ConversationPlanState state = stateOpt.get();
    ActiveChainPlanSnapshot s = state.active;
    return "planId="
        + s.planId()
        + ",chain="
        + (s.chainName() != null ? s.chainName() : "?")
        + ",apiHub="
        + (s.apiHubRequired() != null ? s.apiHubRequired() : "?")
        + ",approved="
        + state.approved
        + ",implementGatePending="
        + state.implementGatePending
        + ",implementGateAck="
        + state.implementGateAcknowledged
        + ",openDebtFp="
        + ChainPlanOpenDebtMerge.fingerprintNonDismissedOpenItems(s.openItems());
  }

  Optional<ActiveChainPlanSnapshot> peekLatestArchiveForTest(String conversationId) {
    return stateStore.peekLatestArchiveForTest(conversationId);
  }

  public void reconcileActiveSnapshotOpenItems(
      String conversationId,
      ChainImplementationPlan materializedPlan,
      boolean applyUnknownSanitize,
      List<PlanOpenItem> sanitizeUnknownItems,
      CreateElementsByJsonReport report) {
    if (conversationId == null || conversationId.isBlank() || materializedPlan == null) {
      return;
    }
    stateStore.computeIfPresent(
        conversationId,
        (id, state) -> {
          if (state.active == null) {
            return state;
          }
          ActiveChainPlanSnapshot prev = state.active;
          List<PlanOpenItem> merged = ChainPlanOpenDebtMerge.mergeOpenItems(
              prev.openItems(), applyUnknownSanitize, sanitizeUnknownItems, report);
          merged = ChainPlanOpenDebtMerge.replaceNonDismissedServiceBindingOpenItems(
              merged, materializedPlan);
          state.active = new ActiveChainPlanSnapshot(
              prev.planId(),
              prev.chainName(),
              prev.apiHubRequired(),
              prev.apiHubReason(),
              materializedPlan,
              Instant.now(),
              prev.rejectionErrors(),
              merged);
          return state;
        });
  }

  public void markAllOpenPlanItemsDismissedByUser(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    stateStore.computeIfPresent(
        conversationId,
        (id, state) -> {
          if (state.active == null) {
            return state;
          }
          ActiveChainPlanSnapshot prev = state.active;
          List<PlanOpenItem> next = prev.openItems().stream()
              .map(
                  o -> o.dismissedByUser()
                      ? o
                      : new PlanOpenItem(
                          o.itemId(),
                          o.kind(),
                          o.clientId(),
                          o.elementId(),
                          o.elementType(),
                          o.message(),
                          o.removedKeys(),
                          true))
              .toList();
          state.active = new ActiveChainPlanSnapshot(
              prev.planId(),
              prev.chainName(),
              prev.apiHubRequired(),
              prev.apiHubReason(),
              prev.plan(),
              Instant.now(),
              prev.rejectionErrors(),
              next);
          return state;
        });
  }

  String openDebtFingerprintForTest(String conversationId) {
    return getActive(conversationId)
        .map(s -> ChainPlanOpenDebtMerge.fingerprintNonDismissedOpenItems(s.openItems()))
        .orElse("");
  }

  private void enrichSourceIdsFromPlanningDiary(
      String conversationId, ChainImplementationPlan plan) {
    if (conversationId == null || conversationId.isBlank() || plan == null) {
      return;
    }
    if (isUnset(plan.getSourceIdsDocumentId())) {
      planningDiaryService
          .firstRecordedIdsDocumentId(conversationId)
          .ifPresent(plan::setSourceIdsDocumentId);
    }
    if (isUnset(plan.getSourceIdsAttachmentObjectKey())) {
      planningDiaryService
          .firstRecordedAttachmentObjectKey(conversationId)
          .ifPresent(plan::setSourceIdsAttachmentObjectKey);
    }
  }

  private static boolean isUnset(String value) {
    return value == null || value.isBlank();
  }

  private static boolean isSubstantivePlan(ChainImplementationPlan plan) {
    return plan != null && plan.getElements() != null && !plan.getElements().isEmpty();
  }

  private void tryApproveFromChat(
      String conversationId,
      ConversationPlanStateStore.ConversationPlanState state,
      String userText) {
    if (state.active == null || state.approved) {
      return;
    }
    if (!planApprovalGate.isPlanApproved(conversationId, userText)) {
      return;
    }
    tryMarkPlanApproved(conversationId, state);
  }

  /**
   * @return true when approval was applied; false when blocked by unresolved
   *         service binding debt
   */
  private boolean tryMarkPlanApproved(
      String conversationId, ConversationPlanStateStore.ConversationPlanState state) {
    if (state.active == null) {
      return false;
    }
    if (ChainPlanOpenDebtMerge.hasBlockingOpenDebt(state.active)) {
      LOG.warnf(
          "Plan approval blocked (open plan debt): conversationId=%s planId=%s fp=%s",
          conversationId,
          state.active.planId(),
          ChainPlanOpenDebtMerge.fingerprintNonDismissedOpenItems(state.active.openItems()));
      return false;
    }
    markPlanApprovedPendingImplementGateOnState(conversationId, state);
    return true;
  }

  private static void markPlanApprovedPendingImplementGateOnState(
      String conversationId, ConversationPlanStateStore.ConversationPlanState state) {
    state.approved = true;
    state.implementGatePending = true;
    state.implementGateAcknowledged = false;
    LOG.infof(
        "Plan approved, implement gate pending: conversationId=%s, planId=%s, chainName=%s",
        conversationId,
        state.active.planId(),
        state.active.chainName() != null ? state.active.chainName() : "(unnamed)");
  }

  private static void resetImplementGateFlags(
      ConversationPlanStateStore.ConversationPlanState state) {
    state.implementGatePending = false;
    state.implementGateAcknowledged = false;
  }
}
