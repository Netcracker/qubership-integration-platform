package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.EditResult;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;

/** Model-facing requirement tools backed by one accepted server-owned draft. */
@ApplicationScoped
public class RequirementCaptureTools {

  private static final Logger LOG = Logger.getLogger(RequirementCaptureTools.class);
  private final RequirementDraftStore store;
  private final RequirementDraftTool legacyBindingAdapter;
  private final ObjectMapper mapper;
  private final CaptureToolOutcomeGateway outcomeGateway;
  private final CaptureAttemptFeedbackStore feedbackStore;
  private final RequirementTargetResolver targetResolver;
  private final CatalogFolders catalogFolders;
  @Inject org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService elementSchemas;
  @Inject org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;

  @Inject
  public RequirementCaptureTools(
      RequirementDraftStore store, RequirementDraftTool legacyBindingAdapter, ObjectMapper mapper,
      CaptureToolOutcomeGateway outcomeGateway, CaptureAttemptFeedbackStore feedbackStore,
      RequirementTargetResolver targetResolver, CatalogFolders catalogFolders) {
    this.store = store;
    this.legacyBindingAdapter = legacyBindingAdapter;
    this.mapper = mapper;
    this.outcomeGateway = outcomeGateway;
    this.feedbackStore = feedbackStore;
    this.targetResolver = targetResolver;
    this.catalogFolders = catalogFolders;
  }

  public RequirementCaptureTools(
      RequirementDraftStore store, RequirementDraftTool legacyBindingAdapter, ObjectMapper mapper,
      CaptureToolOutcomeGateway outcomeGateway, CaptureAttemptFeedbackStore feedbackStore,
      RequirementTargetResolver targetResolver) {
    this(store, legacyBindingAdapter, mapper, outcomeGateway, feedbackStore, targetResolver, null);
  }

  public RequirementCaptureTools(
      RequirementDraftStore store, RequirementDraftTool legacyBindingAdapter, ObjectMapper mapper) {
    this(store, legacyBindingAdapter, mapper, testFeedbackStore());
  }

  private RequirementCaptureTools(
      RequirementDraftStore store, RequirementDraftTool legacyBindingAdapter, ObjectMapper mapper,
      CaptureAttemptFeedbackStore feedbackStore) {
    this(store, legacyBindingAdapter, mapper,
        new CaptureToolOutcomeGateway(feedbackStore.fingerprintStore(), feedbackStore),
        feedbackStore, null);
  }

  private static CaptureAttemptFeedbackStore testFeedbackStore() {
    return new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore());
  }

  @Tool("""
      Save the initial requirements known so far. The server owns readiness and the readable review.
      A partial draft is valid. Call once for initialization; later edits use updateRequirementDraft.
      If initialization is rejected, correct only reported fields and preserve unaffected facts.
      For a field adaptation across a direct transition, record one FIELD_MAPPING fact per target
      field. Include both endpoints in interactionIds and exact field paths in fieldMapping.
      Retain earlier event fields through BEHAVIOR naming their source and save-before-call timing.
      Also map every result field on its adjacent delivery transition with sourcePath null and an
      expression explicitly reading the saved original field. Do not copy it from the call response.
      Named catalog outbound operations have no native capability entry; resolve them after capture.
      Never send complete, decision, assembledText, catalog bindings, or revision references.
      """)
  public String captureRequirementDraft(@P("Authored requirement snapshot") DraftInput draft) {
    return traced("captureRequirementDraft", draft, () -> {
      String conversationId = ToolSession.resolveConversationId();
      synchronized (store) {
        RequirementDraft previous = store.get(conversationId).orElse(null);
        if (previous != null && previous.authoredDraft() != null) {
          if (Objects.equals(previous.authoredDraft(), draft)) {
            return publish(conversationId, draft, previous, null);
          }
          return view(previous, false, false, List.of(new Issue(
              "DRAFT_ALREADY_EXISTS", "/draft", null,
              "Read the accepted draft and use updateRequirementDraft for changes.")));
        }
        EditResult edit = RequirementCaptureEditor.initialize(draft);
        if (!edit.accepted()) {
          List<Issue> issues = new ArrayList<>(edit.issues());
          issues.addAll(validateElementEvidence(conversationId, draft, previous));
          store.rememberRejectedCapture(conversationId, draft, issues);
          return view(previous, false, false, issues);
        }
        Issue folderIssue = ambiguousFolder(draft, previous, "/draft/settings/targetFolderPath");
        if (folderIssue != null) {
          return view(previous, false, false, List.of(folderIssue));
        }
        return publish(conversationId, draft, previous, null);
      }
    });
  }

  @Tool("""
      Apply focused requirement changes atomically. Each updated entity is a complete small record.
      Empty lists leave their entity family unchanged. Remove references explicitly when deleting an
      interaction. Preserve existing fact IDs, kind, full text, polarity,
      and mapping values. Reassign fact references when fixing topology; add omitted requirements as
      new facts. A new author message permits only its explicit amendments. For each changed accepted
      value, supply amendments with path, sourceTurnId, and the exact quote authorizing that change.
      Paths use stable IDs, such as /facts/goal/text, /facts/goal for removal, or /capabilities/entry/path. Use /transitions/entry for the outgoing transitions of entry.
      Use /settings/idsRequested for an explicit choice. Never cite automatic repair instructions.
      Use amendments: [] for automatic corrections and additions. Preserve confirmed policy answers;
      do not null them or reopen answered questions.
      A rejected edit retains the accepted draft and bindings.
      """)
  public String updateRequirementDraft(@P("Focused edits to the accepted draft") DraftUpdate changes) {
    return traced("updateRequirementDraft", changes, () -> {
      String conversationId = ToolSession.resolveConversationId();
      synchronized (store) {
        RequirementDraft previous = store.get(conversationId).orElse(null);
        EditResult edit = RequirementCaptureEditor.apply(
            previous == null ? null : previous.authoredDraft(), changes);
        if (!edit.accepted()) {
          if (edit.candidate() != null && edit.issues().stream()
              .noneMatch(issue -> "POLICY_RESPONSE_REQUIRED".equals(issue.code()))) {
            var issues = new ArrayList<>(edit.issues());
            issues.addAll(validateElementEvidence(conversationId, edit.candidate(), previous));
            store.rememberRejectedCapture(conversationId, edit.candidate(), issues);
          }
          return view(previous, false, false, edit.issues());
        }
        Issue folderIssue =
            ambiguousFolder(edit.draft(), previous, "/changes/settings/targetFolderPath");
        if (folderIssue != null) {
          return view(previous, false, false, List.of(folderIssue));
        }
        return publish(conversationId, edit.draft(), previous, changes);
      }
    });
  }

  /**
   * Rejects a folder name that several catalog folders share, so the author is asked instead of the
   * chain landing in a folder chosen for them.
   *
   * <p>Checked only when the folder changed. A catalog that cannot be read does not block capture:
   * creation resolves the folder again and fails there if the name is still ambiguous.
   */
  private Issue ambiguousFolder(DraftInput draft, RequirementDraft previous, String path) {
    String folder = draft.settings() == null ? null : draft.settings().targetFolderPath();
    String before = previous == null || previous.authoredDraft() == null
        ? null : previous.authoredDraft().settings().targetFolderPath();
    if (catalogFolders == null || folder == null || folder.isBlank()
        || Objects.equals(folder, before)) {
      return null;
    }
    try {
      if (CatalogFolders.resolve(folder, catalogFolders.tree())
          instanceof CatalogFolders.Resolution.Ambiguous ambiguous) {
        return new Issue("AMBIGUOUS_TARGET_FOLDER", path, null,
            "Folder name '" + folder.strip() + "' matches several catalog folders: "
                + String.join(", ", CatalogFolders.paths(ambiguous.candidates()))
                + ". Set targetFolderPath to null and add an open question asking the user which"
                + " of these folders they mean.");
      }
    } catch (RuntimeException catalogUnavailable) {
      LOG.warnf(catalogUnavailable, "Could not check target folder '%s' against the catalog", folder);
    }
    return null;
  }

  public record SourceRead(String turnId, int offset, int maxChars) {}

  @Tool("Read source evidence from this chat by its supplied turn id. Use an offset and at most 16000 characters.")
  public String readRequirementSource(SourceRead source) {
    return store.readSourceTurn(ToolSession.resolveConversationId(), source.turnId(), source.offset(), source.maxChars());
  }

  @Tool("Read the complete accepted requirements, resolution state, defaults, and remaining work.")
  public String readRequirementDraft() {
    return traced("readRequirementDraft", () ->
        view(refreshCatalogBindings(ToolSession.resolveConversationId()), true, false, List.of()));
  }

  private RequirementDraft refreshCatalogBindings(String conversationId) {
    synchronized (store) {
      RequirementDraft current = store.get(conversationId).orElse(null);
      if (current == null || current.authoredDraft() == null) {
        return current;
      }
      List<CatalogBindingHint> bindings = legacyBindingAdapter.canonicalBindings(
          current.authoredDraft(), current, conversationId);
      if (!bindings.equals(current.catalogBindings())) {
        current = RequirementCaptureProjection.toDraft(
            current.authoredDraft(), current, bindings,
            current.sourceSkillVersion(), current.sourceSkillHash());
        store.put(conversationId, current);
      }
      return current;
    }
  }

  @Tool("""
      Finish the requirement-discovery turn once, after captures and lookups. Use STAY for discussion
      and CONTINUE only when the author asked to proceed and the saved draft has no missing fields.
      CONTINUE is refused while the draft is partial. Do not tell the user the draft is ready until
      CONTINUE is accepted. This does not mutate or approve the accepted draft.
      """)
  public String finishRequirementDiscoveryTurn(
      @P("STAY for discussion; CONTINUE for an authorized next step")
          RequirementDiscoveryDirective directive) {
    return traced("finishRequirementDiscoveryTurn", directive, () -> {
      String conversationId = ToolSession.resolveConversationId();
      var rejection = feedbackStore.lastRequirementRejection(conversationId);
      if (rejection.filter(value -> value.feedback().failureClass() == CaptureFailureClass.IDENTICAL_SPAM).isPresent()) {
        store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
        var issues = new ArrayList<>(rejection.orElseThrow().issues().stream()
            .map(issue -> new Issue(issue.code(), issue.path(), issue.entityId(), issue.message())).toList());
        issues.add(new Issue("REPEATED_REJECTION", "/", null,
            "Requirement capture stopped after a repeated rejection."));
        return view(store.get(conversationId).orElse(null), false, false, issues);
      }
      if (directive == null || directive == RequirementDiscoveryDirective.NONE) {
        return view(store.get(conversationId).orElse(null), false, false,
            List.of(new Issue("INVALID_ENUM", "/directive", null,
                "Use STAY or CONTINUE.")));
      }
      RequirementDraft current = refreshCatalogBindings(conversationId);
      if (directive == RequirementDiscoveryDirective.CONTINUE) {
        var preservation = store.validateCurrent(conversationId);
        if (!preservation.isEmpty()) {
          store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
          return view(current, false, false, preservation);
        }
        Optional<String> refusal = continueRefusal(current);
        if (refusal.isPresent()) {
          store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
          return view(current, false, false, List.of(new Issue(
              "CONTINUATION_NOT_AUTHORIZED", "/directive", null, refusal.get())));
        }
      }
      store.finishTurn(conversationId, directive);
      return view(current, true, false, List.of());
    });
  }

  private static Optional<String> continueRefusal(RequirementDraft draft) {
    if (draft == null) {
      return Optional.of(
          "CONTINUE refused. No requirement draft is saved. Call captureRequirementDraft with the"
              + " known flow. Do not tell the user the draft is ready.");
    }
    List<RequirementCaptureProjection.PendingWork> pending = draft.authoredDraft() == null
        ? List.of()
        : RequirementCaptureProjection.pending(
            draft.authoredDraft(),
            draft.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(draft));
    if (draft.readyForPlan() && pending.isEmpty()) {
      return Optional.empty();
    }
    String gaps = pending.stream()
        .map(RequirementCaptureProjection.PendingWork::message)
        .collect(Collectors.joining(" "));
    String reason = gaps.isBlank()
        ? draft.readinessError().orElse("The saved draft is still partial.")
        : gaps;
    return Optional.of(
        "CONTINUE refused. The saved draft is still partial. Use updateRequirementDraft"
            + " and fill the missing fields. Do not tell the user the draft is ready. "
            + reason);
  }

  private RequirementCaptureResult publish(
      String conversationId, DraftInput input, RequirementDraft previous, DraftUpdate changes) {
    var expectedBase = store.latestRevision(conversationId).map(value -> value.reference()).orElse(null);
    List<Issue> preferenceIssues = RequirementPreferenceEvidence.validate(
        input, previous, store.currentAuthorText(conversationId).orElse(null),
        previous == null || previous.authoredDraft() == null ? "/draft" : "/changes");
    preferenceIssues = new ArrayList<>(preferenceIssues);
    preferenceIssues.addAll(validateElementEvidence(conversationId, input, previous));
    if (!preferenceIssues.isEmpty()) {
      store.rememberRejectedCapture(conversationId, input, preferenceIssues);
      return view(previous, false, false, preferenceIssues);
    }
    RequirementTargetResolver.Resolution targetResolution = targetResolver == null
        ? new RequirementTargetResolver.Resolution(Map.of(), List.of())
        : targetResolver.resolve(input);
    if (!targetResolution.issues().isEmpty()) {
      store.rememberRejectedCapture(conversationId, input, targetResolution.issues());
      return view(previous, false, false, targetResolution.issues());
    }
    List<CatalogBindingHint> bindings =
        legacyBindingAdapter.canonicalBindings(input, previous, conversationId);
    RequirementDraft draft = RequirementCaptureProjection.toDraft(
        input, previous, bindings,
        legacyBindingAdapter.sourceSkillVersion(conversationId),
        legacyBindingAdapter.sourceSkillHash(conversationId), targetResolution.targetIds());
    var admissionIssues = store.admitCapture(conversationId, expectedBase, draft, changes,
        mappingRepairPaths(conversationId));
    if (!admissionIssues.isEmpty()) {
      store.rememberRejectedCapture(conversationId, input, admissionIssues);
      return view(previous, false, false, withPreservedFacts(admissionIssues,
          previous, store.rejectedCaptureFacts(conversationId)));
    }
    store.recordCaptureAttempt(conversationId, true);
    feedbackStore.clearRequirement(conversationId);
    store.markCaptured(conversationId);
    ProductCapabilityCaptureContext.offerDraft(draft);
    return view(draft, true, !Objects.equals(expectedBase,
        store.latestRevision(conversationId).map(value -> value.reference()).orElse(null)), List.of());
  }

  private List<Issue> validateElementEvidence(String conversationId, DraftInput input, RequirementDraft previous) {
    List<Issue> issues = new ArrayList<>();
    if (input == null || input.facts() == null) {
      return issues;
    }
    String authorText = store.currentAuthorText(conversationId).orElse(null);
    if (conversations != null) {
      authorText = conversations.archive().read(conversationId).activeTurns().stream()
          .filter(turn -> turn.message().role()
              == org.qubership.integration.platform.ai.chat.conversation.ConversationMessage.Role.USER)
          .map(turn -> turn.message().content()).collect(Collectors.joining("\n"));
    }
    for (FactInput fact : input.facts()) {
      if (fact == null || fact.text() == null) {
        continue;
      }
      var intent = fact.elementIntent();
      if (intent == null || intent.elementType() == null || intent.placement() == null
          || intent.requestedProperties() == null || intent.exactValues() == null) {
        continue;
      }
      String path = "/draft/facts/" + fact.sourceFactId() + "/elementIntent";
      if (intent.placement() != RequirementElementIntent.Placement.CHAIN
          && !RequirementCaptureSchemas.COMPILER_ELEMENT_TYPES.contains(intent.elementType())) {
        issues.add(new Issue("UNSUPPORTED_COMPILER_ELEMENT_TYPE", path, fact.sourceFactId(),
            "Use an element type from the capture schema's compiler contract enum. A legacy schema's existence"
                + " does not make that type an execution step. Branch containers come from semantic regions; capture their"
                + " policy as facts on the owning control element. Preserve the fact and correct its elementIntent."));
        continue;
      }
      if (elementSchemas != null && intent.placement() != RequirementElementIntent.Placement.CHAIN) {
        if (!elementSchemas.hasElementSchema(intent.elementType())) {
          issues.add(new Issue("UNKNOWN_ELEMENT_TYPE", path, fact.sourceFactId(),
              "Use an existing compiler element type."));
          continue;
        }
        var keys = elementSchemas.allowedPatchPropertyKeys(intent.elementType());
        var used = new java.util.HashSet<String>();
        for (var property : intent.requestedProperties()) {
          String propertyPath = path + "/requestedProperties"
              + (property == null || property.key() == null ? ""
                  : "/" + property.key().replace("~", "~0").replace("/", "~1"));
          if (property == null || !keys.contains(property.key()) || !used.add(property.key())
              || property.key().endsWith("Id") && property.key().startsWith("integration")) {
            issues.add(new Issue("INVALID_REQUESTED_PROPERTY", propertyPath, fact.sourceFactId(),
                "Use unique runtime property keys: " + String.join(", ", new java.util.TreeSet<>(keys))
                    + ". Catalog identity belongs to the resolver. Correct the key or encoding without dropping"
                    + " the author setting."
                    + ("async-api-trigger".equals(intent.elementType())
                        ? " For Kafka consumer options, use integrationOperationAsyncProperties with a JSON object"
                            + " value, for example {\"groupId\":\"CIP\"}." : "")));
          } else {
            elementSchemas.validateCapturePropertyValue(intent.elementType(), property.key(),
                mapper.valueToTree(elementSchemas.coercePatchPropertyValue(
                    intent.elementType(), property.key(), property.value())))
                .ifPresent(error -> issues.add(new Issue("INVALID_REQUESTED_PROPERTY", propertyPath,
                    fact.sourceFactId(), error)));
          }
        }
      }
      boolean unchanged = previous != null && previous.authoredDraft() != null
          && previous.authoredDraft().facts().stream().anyMatch(prior ->
              prior.sourceFactId().equals(fact.sourceFactId()) && Objects.equals(prior.elementIntent(), intent));
      if (authorText != null && !unchanged) {
        for (int i = 0; i < intent.exactValues().size(); i++) {
          String literal = intent.exactValues().get(i);
          if (literal != null && !fact.text().contains(literal)) {
            issues.add(new Issue("ELEMENT_LITERAL_FACT_MISMATCH", path + "/exactValues/" + i, fact.sourceFactId(),
                "Keep each exactValues literal unchanged in this fact text."));
          }
        }
        List<String> literals = new ArrayList<>(intent.exactValues());
        if (intent.requestedName() != null) {
          literals.add(intent.requestedName());
        }
        for (int i = 0; i < literals.size(); i++) {
          String literal = literals.get(i);
          if (literal == null || literal.isBlank() || !authorText.contains(literal)) {
            String field = i < intent.exactValues().size() ? "/exactValues/" + i : "/requestedName";
            issues.add(new Issue("ELEMENT_LITERAL_SOURCE_MISMATCH", path + field, fact.sourceFactId(),
                "Copy names and exactValues verbatim from an author source. Generated suggestions are not requested names."));
          }
        }
      }
    }
    return issues;
  }

  private RequirementCaptureResult view(
      RequirementDraft draft, boolean accepted, boolean changed, List<Issue> issues) {
    Reference reference = store.latestRevision(ToolSession.resolveConversationId())
        .map(revision -> revision.reference()).orElse(null);
    return RequirementCaptureResult.view(
        draft, reference, accepted, changed, issues, store.turnDirective(ToolSession.resolveConversationId()));
  }

  private List<Issue> withPreservedFacts(List<Issue> issues, RequirementDraft accepted, List<FactInput> facts) {
    return issues.stream().map(issue -> new Issue(
        issue.code(), issue.path(), issue.entityId(), issue.message()
            + " Accepted fact records: "
            + mapper.valueToTree(accepted == null || accepted.authoredDraft() == null ? List.of()
                : accepted.authoredDraft().facts().stream()
                    .filter(fact -> Objects.equals(fact.sourceFactId(), issue.entityId())).toList())
            + ". Validated fields from rejected inputs: "
            + mapper.valueToTree(facts.stream()
                .filter(fact -> Objects.equals(fact.sourceFactId(), issue.entityId())).toList())
            + ". Rejected-input records are partial preservation constraints, not replacement records."
            + " Null does not instruct you to clear an accepted value. Retain accepted values and validated"
            + " pending fields; correct only reported invalid fields or explicitly authorized amendments."))
        .toList();
  }

  private java.util.Map<String, java.util.Set<String>> mappingRepairPaths(String conversationId) {
    return ProductCapabilityCaptureContext.binding(conversationId)
        .filter(binding -> binding.mode() == ProductCapabilityCaptureContext.Mode.DISCOVERY)
        .map(ProductCapabilityCaptureContext.Binding::mappingRepairPaths).orElse(java.util.Map.of());
  }

  private RequirementCaptureResult recordOutcome(String toolName, Object arguments, RequirementCaptureResult result) {
    String conversationId = ToolSession.resolveConversationId();
    store.recordCaptureOutcome(conversationId, toolName, arguments, result);
    if ("finishRequirementDiscoveryTurn".equals(toolName) && result.issues().stream()
        .anyMatch(issue -> "REPEATED_REJECTION".equals(issue.code()))) {
      return result;
    }
    if (!result.accepted() && result.issues().stream()
        .noneMatch(issue -> "CONTINUATION_NOT_AUTHORIZED".equals(issue.code()))) {
      store.recordCaptureAttempt(conversationId, false);
      String scope = store.captureMutationScope(conversationId);
      boolean repeated = outcomeGateway.repeatedRequirementIssue(
          conversationId, scope, result.issues().stream()
              .map(issue -> new CaptureToolOutcomeGateway.TypedIssue(
                  issue.code(), issue.path(), issue.message(), issue.entityId())).toList());
      if (repeated) {
        store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
        List<RequirementCaptureResult.CaptureIssue> terminal = new ArrayList<>(result.issues());
        terminal.add(new RequirementCaptureResult.CaptureIssue("REPEATED_REJECTION", "/", null, null,
            "The same requirement issue was rejected again. Stop automatic repair."));
        return new RequirementCaptureResult(false, result.changed(), result.revision(), result.readiness(),
            RequirementCaptureResult.NextAction.STOP, List.copyOf(terminal), result.pendingWork(), result.draft(),
            result.resolutions(), result.defaults());
      }
    }
    return result;
  }

  private String encode(RequirementCaptureResult result) {
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize requirement capture result", e);
    }
  }

  String rejectArguments(String toolName, String arguments, Issue issue) {
    return traced(toolName, arguments, () -> view(
        store.get(ToolSession.resolveConversationId()).orElse(null),
        false, false, List.of(issue)));
  }

  private String traced(String toolName, Supplier<RequirementCaptureResult> action) {
    return traced(toolName, null, action);
  }

  private String traced(String toolName, Object arguments, Supplier<RequirementCaptureResult> action) {
    String conversationId = ToolSession.resolveConversationId();
    long started = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, toolName, conversationId, "structured arguments");
    try {
      RequirementCaptureResult result;
      result = store.withWriteAuthority(conversationId, () -> {
        store.captureMutationScope(conversationId);
        return recordOutcome(toolName, arguments, action.get());
      });
      String encoded = encode(result);
      String summary = "accepted=" + result.accepted() + " changed=" + result.changed()
          + " readiness=" + result.readiness() + " issues="
          + result.issues().stream().map(RequirementCaptureResult.CaptureIssue::code).toList();
      ToolTraceLog.logToolComplete(
          LOG, toolName, conversationId, System.currentTimeMillis() - started, summary);
      return encoded;
    } catch (RuntimeException error) {
      ToolTraceLog.logToolFailed(
          LOG, toolName, conversationId, System.currentTimeMillis() - started, error);
      throw error;
    }
  }
}
