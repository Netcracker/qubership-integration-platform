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
import org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.EditResult;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;
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
  private final UploadedSpecOperations uploadedSpecs;
  @Inject org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService elementSchemas;
  @Inject org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl recoveryControl;
  org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations;

  @Inject
  public RequirementCaptureTools(
      RequirementDraftStore store, RequirementDraftTool legacyBindingAdapter, ObjectMapper mapper,
      CaptureToolOutcomeGateway outcomeGateway, CaptureAttemptFeedbackStore feedbackStore,
      RequirementTargetResolver targetResolver, CatalogFolders catalogFolders, UploadedSpecOperations uploadedSpecs,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations) {
    this.store = store;
    this.legacyBindingAdapter = legacyBindingAdapter;
    this.mapper = mapper;
    this.outcomeGateway = outcomeGateway;
    this.feedbackStore = feedbackStore;
    this.targetResolver = targetResolver;
    this.catalogFolders = catalogFolders;
    this.uploadedSpecs = uploadedSpecs;
    this.conversations = conversations;
  }

  public RequirementCaptureTools(
      RequirementDraftStore store, RequirementDraftTool legacyBindingAdapter, ObjectMapper mapper,
      CaptureToolOutcomeGateway outcomeGateway, CaptureAttemptFeedbackStore feedbackStore,
      RequirementTargetResolver targetResolver, CatalogFolders catalogFolders) {
    this(store, legacyBindingAdapter, mapper, outcomeGateway, feedbackStore, targetResolver, catalogFolders, null, null);
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
      A partial draft is valid. Initialize with captureRequirementDraft until a draft is accepted.
      After initialization is accepted, later edits use updateRequirementDraft.
      If initialization is rejected, correct the full rejected candidate and call captureRequirementDraft again,
      or readRequirementDraft and use updateRequirementDraft with the exact pendingProposal.token for focused initial repair.
      Change only reported fields and preserve unaffected facts.
      A new claim requires evidence: sourceTurnId and an exact quote from that user turn.
      The quote must be an exact substring of the named turn. A fact already in the saved
      semantic baseline, and a legacy accepted fact, may omit the quote.
      For a field adaptation across a direct transition, record one FIELD_MAPPING fact per target
      field. Include both endpoints in interactionIds and exact field paths in fieldMapping.
      Retain earlier event fields through BEHAVIOR naming their source and save-before-call timing.
      Also map every result field on its adjacent delivery transition with sourcePath null and an
      expression explicitly reading the saved original field. Do not copy it from the call response.
      Named catalog outbound operations have no native capability entry; resolve them after capture.
      When nextAction is RUN_IMPORT, finish with CONTINUE to import uploaded specs before binding.
      Capture explicit HTTP trigger accessControlType and roles in ANCHOR elementIntent requestedProperties.
      RBAC needs the author's explicit role choice. Save a partial draft and ask for missing roles;
      role names from examples do not authorize access to this chain.
      Never send complete, decision, assembledText, catalog bindings, or revision references.
      """)
  public String captureRequirementDraft(@P("Authored requirement snapshot") DraftInput draft) {
    String conversation = ToolSession.resolveConversationId();
    DraftInput proposal = recoveryControl != null && recoveryControl.needsLiteralFault(conversation)
        && RequirementCaptureEditor.initialize(draft).accepted()
        && validateElementEvidence(conversation, draft, store.get(conversation).orElse(null)).isEmpty()
        ? recoveryControl.requirements(conversation, draft) : draft;
    return traced("captureRequirementDraft", proposal, () -> {
      String conversationId = ToolSession.resolveConversationId();
      synchronized (store) {
        RequirementDraft previous = store.get(conversationId).orElse(null);
        if (previous != null && previous.authoredDraft() != null) {
          if (Objects.equals(previous.authoredDraft(), proposal)) {
            return publish(conversationId, proposal, previous, null, List.of());
          }
          return view(previous, false, false, List.of(new Issue(
              "DRAFT_ALREADY_EXISTS", "/draft", null,
              "Read the accepted draft and use updateRequirementDraft for changes.")));
        }
        EditResult edit = RequirementCaptureEditor.initialize(proposal);
        return publish(conversationId, proposal, previous, null, edit.issues());
      }
    });
  }

  @Tool("""
      Apply focused requirement changes atomically. If no draft is accepted, first readRequirementDraft and copy
      the exact current pendingProposal.token into changes.pendingProposalToken to edit that unaccepted initial proposal.
      This does not approve it or authorize changing protected facts; the full initial validation still applies.
      Omit pendingProposalToken or set it null when editing an accepted draft. Each updated entity is a complete small record.
      Empty lists leave their entity family unchanged. Remove references explicitly when deleting an
      interaction. Preserve accepted fact IDs, kind, full text, polarity,
      and mapping values. Reassign fact references when fixing topology; add omitted requirements as
      new facts.       A rejected FIELD_MAPPING classification may become BEHAVIOR when no business
      transition is intended; retain its fact ID, complete author text, polarity, and valid references.
      A new claim requires evidence: sourceTurnId and an exact quote from that user turn.
      The quote must be an exact substring of the named turn. A fact already in the saved
      semantic baseline, and a legacy accepted fact, may omit the quote.
      Deleting or changing claim text or polarity requires a new source-linked amendment.
      When the claim is already in the saved semantic baseline, changing kind, interaction IDs,
      mapping, element placement, or literal binding does not.
      A new author message permits only its explicit amendments. For each changed accepted
      value, supply amendments with path, sourceTurnId, and the exact quote authorizing that change.
      Paths use stable IDs, such as /facts/goal/text, /facts/goal for removal, /capabilities/entry/path,
      or /interactions/entry/operation. Use /transitions/entry for the outgoing transitions of entry.
      Apply the amendment to all affected representations in one update. A URL change includes
      capability.path and any operation, description, or fact text repeating that URL. Cite the same
      source quote with a separate exact path for each changed field; retain unrelated content.
      Use /settings/idsRequested for an explicit choice. Never cite automatic repair instructions.
      Use amendments: [] for automatic corrections and additions. Preserve confirmed policy answers;
      do not null them or reopen answered questions.
      A rejected edit retains the accepted draft and bindings.
      """)
  public String updateRequirementDraft(@P("Focused edits to the accepted draft") DraftUpdate changes) {
    return traced("updateRequirementDraft", changes, pending -> {
      String conversationId = ToolSession.resolveConversationId();
      synchronized (store) {
        RequirementDraft previous = store.get(conversationId).orElse(null);
        EditResult edit = pending != null ? pending : RequirementCaptureEditor.apply(
            previous == null ? null : previous.authoredDraft(), changes);
        return publish(conversationId, edit.accepted() ? edit.draft() : edit.candidate(), previous, changes, edit.issues());
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

  @Tool("""
      Read the complete accepted requirements, resolution state, defaults, and remaining work.
      pendingProposal, when present, is the complete rejected candidate with its source/base/attempt and findings.
      It is not accepted and grants no amendment authority. If no accepted draft exists, correct it using
      captureRequirementDraft, or copy pendingProposal.token into changes.pendingProposalToken for focused initial repair;
      otherwise use updateRequirementDraft against the accepted draft.
      """)
  public String readRequirementDraft() {
    return traced("readRequirementDraft", () -> {
      String conversationId = ToolSession.resolveConversationId();
      return view(refreshCatalogBindings(conversationId), true, false, List.of())
          .withPendingProposal(store.pendingProposal(conversationId).orElse(null));
    });
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
      Finish the requirement-discovery turn once, after captures and lookups. Use DISCUSS only to answer a question about the paused draft without changing it;
      use STAY for a justified remaining author question and CONTINUE only when the author asked to proceed and the saved draft has no missing fields.
      A partial draft with nextAction RUN_IMPORT may CONTINUE to the upload import stage. Other
      partial drafts refuse CONTINUE. Do not tell the user the draft is ready until
      CONTINUE is accepted. Each selected native capability needs a positive ANCHOR elementIntent
      with its interaction ID and type; requestedName may be null when no name was supplied.
      This does not mutate or approve the accepted draft.
      """)
  public String finishRequirementDiscoveryTurn(
      @P("STAY for missing author data; DISCUSS for a question about the draft; CONTINUE for an authorized next step")
          RequirementDiscoveryDirective directive) {
    return traced("finishRequirementDiscoveryTurn", directive, () -> {
      String conversationId = ToolSession.resolveConversationId();
      var rejection = feedbackStore.lastRequirementRejection(conversationId);
      if (rejection.filter(value -> value.feedback().noProgress()).isPresent()) {
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
                "Use STAY, DISCUSS, or CONTINUE.")));
      }
      RequirementDraft current = refreshCatalogBindings(conversationId);
      if (directive == RequirementDiscoveryDirective.DISCUSS && !store.discussionAllowed(conversationId)) {
        return view(current, false, false, List.of(new Issue("DISCUSSION_NOT_AUTHORIZED", "/directive", null,
            "Complete the producer work. DISCUSS requires a new author question about a paused draft.")));
      }
      if (directive != RequirementDiscoveryDirective.DISCUSS) {
        var preservation = store.validateForContinuation(conversationId);
        if (!preservation.isEmpty()) {
          store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
          return view(current, false, false, preservation);
        }
      }
      if (directive == RequirementDiscoveryDirective.CONTINUE) {
        Optional<String> refusal = continueRefusal(current);
        if (refusal.isPresent()) {
          store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
          return view(current, false, false, List.of(new Issue(
              "CONTINUATION_NOT_AUTHORIZED", "/directive", null, refusal.get())));
        }
      }
      var questions = directive == RequirementDiscoveryDirective.DISCUSS ? List.<Issue>of()
          : store.validateAuthorQuestions(conversationId);
      if (!questions.isEmpty()) return view(current, false, false, questions);
      store.finishTurn(conversationId, directive);
      return view(current, true, false, List.of());
    });
  }

  private Optional<String> continueRefusal(RequirementDraft draft) {
    if (draft == null) {
      return Optional.of(
          "CONTINUE refused. No requirement draft is saved. Call captureRequirementDraft with the"
              + " known flow. Do not tell the user the draft is ready.");
    }
    var consistency = RequirementFailureConsistency.issues(draft);
    if (!consistency.isEmpty()) return Optional.of(consistency.getFirst().message());
    List<RequirementCaptureProjection.PendingWork> pending = draft.authoredDraft() == null
        ? List.of()
        : RequirementCaptureProjection.pending(
            draft.authoredDraft(),
            draft.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(draft), uploadedSpecInteractions(draft));
    if (draft.readyForPlan() && pending.isEmpty()
        || RequirementCaptureProjection.onlyUploadedSpecImportsRemain(pending)) {
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
      String conversationId, DraftInput input, RequirementDraft previous, DraftUpdate changes, List<Issue> editIssues) {
    if (input == null) return reject(conversationId, null, previous, editIssues);
    Issue folderIssue = input.settings() == null ? null : ambiguousFolder(input, previous,
        previous == null ? "/draft/settings/targetFolderPath" : "/changes/settings/targetFolderPath");
    if (folderIssue != null && editIssues.isEmpty()) return view(previous, false, false, List.of(folderIssue));
    var expectedBase = store.latestRevision(conversationId).map(value -> value.reference()).orElse(null);
    var issues = new ArrayList<>(editIssues);
    if (folderIssue != null) issues.add(folderIssue);
    if (input.settings() != null) {
      issues.addAll(RequirementPreferenceEvidence.validate(input, previous,
          store.currentAuthorText(conversationId).orElse(null), previous == null ? "/draft" : "/changes"));
    }
    issues.addAll(validateElementEvidence(conversationId, input, previous));
    RequirementTargetResolver.Resolution targetResolution = targetResolver != null && input.flow() != null
        && input.flow().interactions() != null && input.flow().interactions().stream().allMatch(Objects::nonNull)
        && input.capabilities() != null && input.capabilities().stream().allMatch(Objects::nonNull)
        ? targetResolver.resolve(input) : new RequirementTargetResolver.Resolution(Map.of(), List.of());
    issues.addAll(targetResolution.issues());
    issues.addAll(validatePolicyApplicability(input, issues));
    var repair = ProductCapabilityCaptureContext.binding(conversationId)
        .filter(binding -> binding.mode() == ProductCapabilityCaptureContext.Mode.DISCOVERY
            && binding.approvedDraft() != null && store.activeRequirementRepairPaths(conversationId).isEmpty());
    var scopeIssues = repair.map(binding -> org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability
        .mappingRepairIssues(binding.mappingRepairPaths(), binding.missingMappingTargets(),
            binding.approvedDraft().authoredDraft(), input)).orElse(List.of());
    boolean outsideRepairScope = !scopeIssues.isEmpty();
    issues.addAll(scopeIssues);
    if (!issues.isEmpty()) {
      // Rejected content outside the repair scope must not become protected repair facts.
      return reject(conversationId, outsideRepairScope ? null : input, previous, issues.stream().distinct().toList());
    }
    List<CatalogBindingHint> bindings =
        legacyBindingAdapter.canonicalBindings(input, previous, conversationId);
    var consistency = RequirementFailureConsistency.issues(input, bindings);
    if (!consistency.isEmpty()) {
      store.rememberRejectedCapture(conversationId, input, consistency, bindings);
      return view(previous, false, false, withPreservedFacts(consistency, previous, store.rejectedCaptureFacts(conversationId)));
    }
    DraftInput admitted = input;
    RequirementDraft draft = RequirementCaptureProjection.toDraft(
        admitted, previous, bindings,
        legacyBindingAdapter.sourceSkillVersion(conversationId),
        legacyBindingAdapter.sourceSkillHash(conversationId), targetResolution.targetIds());
    store.revalidateRejectedModeCorrection(conversationId, admitted, bindings);
    var admissionIssues = store.admitCapture(conversationId, expectedBase, draft, changes,
        mappingRepairPaths(conversationId));
    if (!admissionIssues.isEmpty()) {
      var restored = store.restoreProtectedCandidate(conversationId, admitted, bindings, admissionIssues);
      if (restored != null) {
        var preservedValues = store.copyRejectedCaptureValues(conversationId);
        store.noteNamedModeCorrection(conversationId, restored, bindings);
        var restoredDraft = RequirementCaptureProjection.toDraft(
            restored, previous, bindings,
            legacyBindingAdapter.sourceSkillVersion(conversationId),
            legacyBindingAdapter.sourceSkillHash(conversationId), targetResolution.targetIds());
        var retry = store.admitCapture(conversationId, expectedBase, restoredDraft, changes,
            mappingRepairPaths(conversationId));
        if (retry.isEmpty()) {
          admitted = restored;
          draft = restoredDraft;
          admissionIssues = retry;
        } else {
          store.replaceRejectedCaptureValues(conversationId, preservedValues);
        }
      }
    }
    if (!admissionIssues.isEmpty()) {
      return reject(conversationId, admitted, previous, admissionIssues);
    }
    store.recordCaptureAttempt(conversationId, true);
    feedbackStore.clearRequirement(conversationId);
    store.markCaptured(conversationId);
    ProductCapabilityCaptureContext.offerDraft(draft);
    return view(draft, true, !Objects.equals(expectedBase,
        store.latestRevision(conversationId).map(value -> value.reference()).orElse(null)), List.of());
  }

  private RequirementCaptureResult reject(String conversationId, DraftInput input,
      RequirementDraft previous, List<Issue> findings) {
    var issues = findings.stream().distinct().toList();
    if (input != null && issues.stream().noneMatch(issue -> "POLICY_RESPONSE_REQUIRED".equals(issue.code()))) {
      store.rememberRejectedCapture(conversationId, input, issues);
    }
    return view(previous, false, false, withPreservedFacts(issues, previous, store.rejectedCaptureFacts(conversationId)));
  }

  private List<Issue> validatePolicyApplicability(DraftInput input, List<Issue> nativeIssues) {
    if (input.facts() == null) return List.of();
    var findings = new ArrayList<Issue>();
    for (int i = 0; i < input.facts().size(); i++) {
      var fact = input.facts().get(i);
      if (fact == null || fact.policyConflict() == null
          || !org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator.EXTERNAL_RBAC_POLICY
              .equals(fact.policyConflict().policyId())) continue;
      String owner = fact.interactionIds() != null && fact.interactionIds().size() == 1
          ? fact.interactionIds().getFirst() : null;
      var anchors = input.facts().stream().filter(Objects::nonNull)
          .filter(value -> owner != null && value.polarity() == RequirementCaptureInput.Polarity.POSITIVE
              && value.interactionIds() != null && value.interactionIds().contains(owner))
          .filter(value -> value.elementIntent() != null && owner != null
              && owner.equals(value.elementIntent().ownerInteractionId())
              && owner.equals(value.elementIntent().elementRef())
              && "http-trigger".equals(value.elementIntent().elementType())
              && value.elementIntent().placement() == RequirementElementIntent.Placement.ANCHOR).toList();
      boolean checked = owner != null && input.flow() != null && input.flow().interactions() != null
          && input.flow().interactions().stream().filter(Objects::nonNull)
              .filter(value -> owner.equals(value.interactionId())).count() == 1
          && input.flow().interactions().stream().filter(Objects::nonNull).anyMatch(value ->
              owner.equals(value.interactionId())
                  && value.direction() == org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND)
          && input.capabilities() != null && input.capabilities().stream().filter(Objects::nonNull)
              .filter(value -> owner.equals(value.interactionId())).count() == 1
          && input.capabilities().stream().filter(Objects::nonNull).anyMatch(value ->
              owner.equals(value.interactionId()) && "http-trigger".equals(value.capabilityKey()))
          && elementSchemas != null && elementSchemas.hasElementSchema("http-trigger")
          && elementSchemas.allowedPatchPropertyKeys("http-trigger").contains("externalRoute");
      for (int dependency = 0; dependency < input.facts().size(); dependency++) {
        var value = input.facts().get(dependency);
        if (value == null || value.elementIntent() == null || owner == null
            || !owner.equals(value.elementIntent().elementRef())) continue;
        String stable = "/draft/facts/" + value.sourceFactId() + "/elementIntent";
        String indexed = "/draft/facts/" + dependency + "/elementIntent";
        checked &= nativeIssues.stream().noneMatch(issue -> issue.path().equals(stable)
            || issue.path().startsWith(stable + "/") || issue.path().equals(indexed)
            || issue.path().startsWith(indexed + "/"));
      }
      var observed = new ArrayList<String>();
      var values = new java.util.HashSet<Boolean>();
      for (var anchor : anchors) {
        var properties = anchor.elementIntent().requestedProperties();
        if (properties == null) { checked = false; continue; }
        var flags = properties.stream().filter(Objects::nonNull)
            .filter(property -> "externalRoute".equals(property.key())).toList();
        if (flags.size() > 1) checked = false;
        for (var flag : flags) {
          observed.add(flag.value());
          if (elementSchemas == null) { checked = false; continue; }
          var coerced = elementSchemas.coercePatchPropertyValue("http-trigger", "externalRoute", flag.value());
          if (!(coerced instanceof Boolean value)
              || elementSchemas.validateCapturePropertyValue("http-trigger", "externalRoute",
                  mapper.valueToTree(coerced)).isPresent()) {
            checked = false;
          } else values.add(value);
        }
      }
      if (checked && values.equals(java.util.Set.of(true))) continue;
      findings.add(new Issue("INVALID_POLICY_CONFLICT", "/draft/facts/" + fact.sourceFactId() + "/policyConflict",
          fact.sourceFactId(), (checked && values.equals(java.util.Set.of(false))
              ? "The external-route RBAC recommendation does not apply to this internal route. "
              : "The external-route RBAC recommendation has no proven native applicability. ")
              + "Its condition requires a valid positive http-trigger ANCHOR on the same owner with explicit externalRoute=true."
              + " Observed externalRoute values: " + mapper.valueToTree(observed)
              + ". Review the saved requirements and capture the actual exposure before offering this policy choice."
              + " Correct only the invalid policy metadata and its question; preserve the fact text, targets, names,"
              + " properties, literals, and every other requirement. Do not choose a policy answer automatically."));
    }
    return findings;
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
    var source = conversations == null
        ? new AuthorSourceEvidence.Packet(store.writeAuthority(conversationId).sourceGeneration(),
            authorText == null ? List.of() : List.of(new AuthorSourceEvidence.Input(
                store.writeAuthority(conversationId).sourceTurnId(), authorText)))
        : AuthorSourceEvidence.packet(conversations.archive().read(conversationId));
    var bodyOwners = input.facts().stream().filter(Objects::nonNull).map(FactInput::elementIntent)
        .filter(Objects::nonNull).toList();
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
        var keys = new java.util.HashSet<>(elementSchemas.allowedPatchPropertyKeys(intent.elementType()));
        keys.removeIf(key -> key.endsWith("Id") && key.startsWith("integration")
            || "chain-call-2".equals(intent.elementType()) && "elementId".equals(key));
        var used = new java.util.HashSet<String>();
        for (var property : intent.requestedProperties()) {
          String propertyPath = path + "/requestedProperties"
              + (property == null || property.key() == null ? ""
                  : "/" + property.key().replace("~", "~0").replace("/", "~1"));
          if (property == null || !keys.contains(property.key()) || !used.add(property.key())) {
            issues.add(new Issue("INVALID_REQUESTED_PROPERTY", propertyPath, fact.sourceFactId(),
                "Use unique runtime property keys: " + String.join(", ", new java.util.TreeSet<>(keys))
                    + ". Catalog identity belongs to the resolver. Correct the key or encoding without dropping"
                    + " the author setting."
                    + ("async-api-trigger".equals(intent.elementType())
                        ? " The topic and classifier come from the selected catalog operation, not a standalone"
                            + " runtime property. Retain the literal in the fact text and exactValues; remove only the"
                            + " invalid property, keeping the fact ID and the remaining elementIntent fields."
                            + " For Kafka consumer options, use integrationOperationAsyncProperties with a JSON object"
                            + " value, for example {\"groupId\":\"CIP\"}." : "")));
          } else {
            elementSchemas.validateCapturePropertyValue(intent.elementType(), property.key(),
                mapper.valueToTree(elementSchemas.coercePatchPropertyValue(
                    intent.elementType(), property.key(), property.value())))
                .ifPresent(error -> issues.add(new Issue("INVALID_REQUESTED_PROPERTY", propertyPath,
                    fact.sourceFactId(), error
                        + ". The tool's property value remains a string. Keep runtime strings verbatim;"
                        + " encode runtime arrays and objects as JSON text inside that string. Preserve the"
                        + " author-supplied content while correcting its representation. Current runtime schema: "
                        + elementSchemas.describeElementProperty(intent.elementType(), property.key()))));
          }
        }
      }
      if (RequirementBodySources.ownsBody(intent, "script")) {
        var authority = RequirementBodySources.resolve(intent, bodyOwners, source);
        if (authority.error() != null) {
          issues.add(new Issue("BODY_SOURCE_UNPROVEN", path + "/bodyBinding", fact.sourceFactId(), authority.error()));
        } else {
          if (!Objects.equals(authority.binding(), intent.bodyBinding())) {
            issues.add(new Issue(intent.bodyBinding() == null ? "BODY_BINDING_REQUIRED" : "BODY_SOURCE_MISMATCH",
                path + "/bodyBinding", fact.sourceFactId(),
                "Native source classification differs from bodyBinding. Retain the fact and other configuration;"
                    + " copy this source-derived binding: " + mapper.valueToTree(authority.binding())
                    + ". Generated candidates guide generation and cannot overwrite a validated compiler body."));
          }
          if (authority.body() != null && intent.requestedProperties().stream().noneMatch(property ->
              property != null && "script".equals(property.key()) && authority.body().equals(property.value()))) {
            issues.add(new Issue("BODY_SOURCE_MISMATCH", path + "/requestedProperties/script", fact.sourceFactId(),
                "Copy the complete source code value without trimming or rewriting any characters."
                    + " The source-derived binding is " + mapper.valueToTree(authority.binding())
                    + "; the exact native script value is " + mapper.valueToTree(authority.body()) + "."));
          }
        }
      } else if (intent.bodyBinding() != null) {
        issues.add(new Issue("BODY_SOURCE_MISMATCH", path + "/bodyBinding", fact.sourceFactId(),
            "This element does not own the native script body identified by bodyBinding."));
      }
      boolean unchanged = previous != null && previous.authoredDraft() != null
          && previous.authoredDraft().facts().stream().anyMatch(prior ->
              prior.sourceFactId().equals(fact.sourceFactId()) && Objects.equals(prior.elementIntent(), intent));
      if (authorText != null && !unchanged) {
        if ("http-trigger".equals(intent.elementType())) {
          for (var property : intent.requestedProperties()) {
            if (property == null || !"roles".equals(property.key()) || property.value() == null) { continue; }
            try {
              var roles = mapper.readTree(property.value());
              if (roles == null || !roles.isArray()) { continue; }
              for (var role : roles) {
                if (role.isTextual() && !role.asText().isBlank() && !authorText.contains(role.asText())) {
                  issues.add(new Issue("ELEMENT_LITERAL_SOURCE_MISMATCH", path + "/requestedProperties/roles",
                      fact.sourceFactId(), "Use roles explicitly supplied by the author for this HTTP trigger."
                          + " Ask which roles may access it; knowledge examples do not authorize a role choice."));
                }
              }
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalidRoles) {
              // Property validation and readiness handle invalid array encoding.
            }
          }
        }
        for (int i = 0; i < intent.exactValues().size(); i++) {
          String literal = intent.exactValues().get(i);
          if (literal != null && !fact.text().contains(literal)) {
            issues.add(new Issue("ELEMENT_LITERAL_FACT_MISMATCH", path + "/exactValues/" + i, fact.sourceFactId(),
                "Keep only exactValues literals already present in this fact text and the author source."
                    + " Correct the rejected exactValues list. Do not rewrite protected fact text to insert literals;"
                    + " retain configuration in requestedProperties and its separate facts."));
          }
        }
        List<String> literals = new ArrayList<>(intent.exactValues());
        if (intent.requestedName() != null) {
          literals.add(intent.requestedName());
        }
        for (int i = 0; i < literals.size(); i++) {
          String literal = literals.get(i);
          if (literal == null || literal.isBlank() || !authorContainsLiteral(authorText, literal)) {
            String field = i < intent.exactValues().size() ? "/exactValues/" + i : "/requestedName";
            issues.add(new Issue("ELEMENT_LITERAL_SOURCE_MISMATCH", path + field, fact.sourceFactId(),
                literal == null || literal.isBlank()
                    ? "Use nonempty author literals in exactValues and requestedName. Remove blank exactValues entries;"
                        + " retain explicitly empty configuration values in requestedProperties."
                    : "Copy names and exactValues verbatim from an author source. Generated suggestions are not requested names."));
          }
        }
      }
    }
    if (RequirementCaptureEditor.validate(input).isEmpty()) {
    for (var fact : RequirementCaptureProjection.facts(input)) {
      var unbound = org.qubership.integration.platform.ai.qipknowledge.validation.RequiredLiteralBindings.unbound(
          fact, RequirementCaptureProjection.facts(input), RequirementCaptureProjection.flow(input).interactions());
      if (!unbound.isEmpty()) issues.add(new Issue("UNBOUND_REQUIRED_LITERAL",
          "/draft/facts/" + fact.sourceFactId() + "/elementIntent/requestedProperties", fact.sourceFactId(),
          "Required literals at /draft/facts/" + fact.sourceFactId() + "/elementIntent/exactValues lack an implementation binding: "
              + mapper.valueToTree(unbound) + ". Bind each runtime literal to a writable property or an owned executable artifact."
              + " A catalog system or operation name belongs on the interaction participant or operation."
              + " Do not copy it into an integration id property."
              + (elementSchemas == null ? "" : " Current writable property keys for " + fact.elementIntent().elementType()
                  + ": " + writablePropertyKeys(fact.elementIntent().elementType())
                  + ". Use describeElementProperty for the selected property's current value schema.")
              + " Use the saved author source; preserve the fact, exactValues, and existing values."
              + " Names, descriptions, unrelated nodes, and comments cannot implement runtime values."));
    }
    }
    return issues;
  }

  private java.util.SortedSet<String> writablePropertyKeys(String elementType) {
    var keys = new java.util.TreeSet<>(elementSchemas.allowedPatchPropertyKeys(elementType));
    keys.removeIf(key -> key.endsWith("Id") && key.startsWith("integration")
        || "chain-call-2".equals(elementType) && "elementId".equals(key));
    return keys;
  }

  private static boolean authorContainsLiteral(String authorText, String literal) {
    return authorText.contains(literal) || authorText.contains(jsonEscaped(literal));
  }

  private static String jsonEscaped(String literal) {
    return literal.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private RequirementCaptureResult view(
      RequirementDraft draft, boolean accepted, boolean changed, List<Issue> issues) {
    Reference reference = store.latestRevision(ToolSession.resolveConversationId())
        .map(revision -> revision.reference()).orElse(null);
    return RequirementCaptureResult.view(
        draft, reference, accepted, changed, issues, store.turnDirective(ToolSession.resolveConversationId()),
        uploadedSpecInteractions(draft));
  }

  private java.util.Set<String> uploadedSpecInteractions(RequirementDraft draft) {
    return uploadedSpecs == null ? java.util.Set.of()
        : uploadedSpecs.pendingInteractionIds(ToolSession.resolveConversationId(), draft);
  }

  private List<Issue> withPreservedFacts(List<Issue> issues, RequirementDraft accepted, List<FactInput> facts) {
    return issues.stream().map(issue -> new Issue(
        issue.code(), issue.path(), issue.entityId(), issue.message()
            + " Accepted fact records: "
            + mapper.valueToTree(accepted == null || accepted.authoredDraft() == null ? List.of()
                : accepted.authoredDraft().facts().stream()
                    .filter(fact -> Objects.equals(fact.sourceFactId(), issue.entityId())).toList())
            + ". Pending preservation constraints copied from rejected inputs: "
            + mapper.valueToTree(facts.stream()
                .filter(fact -> Objects.equals(fact.sourceFactId(), issue.entityId())).toList())
            + ". These partial constraints do not prove that every native consumer check has run or passed;"
            + " some checks may be deferred. They are not replacement records."
            + " Null does not instruct you to clear an accepted value. Retain accepted values and pending"
            + " constrained fields; correct only reported invalid fields or explicitly authorized amendments."
            + " During automatic repair, keep accepted elementIntent unchanged. Add extra source-backed settings through addFacts"
            + " on the same elementRef, rather than updating its accepted fact."))
        .toList();
  }

  private java.util.Map<String, java.util.Set<String>> mappingRepairPaths(String conversationId) {
    return ProductCapabilityCaptureContext.binding(conversationId)
        .filter(binding -> binding.mode() == ProductCapabilityCaptureContext.Mode.DISCOVERY)
        .map(ProductCapabilityCaptureContext.Binding::mappingRepairPaths).orElse(java.util.Map.of());
  }

  private RequirementCaptureResult recordOutcome(String toolName, Object arguments, RequirementCaptureResult result,
      DraftInput resolvedCandidate, org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference selectedAttempt) {
    String conversationId = ToolSession.resolveConversationId();
    store.recordCaptureOutcome(conversationId, toolName, arguments, result, resolvedCandidate, selectedAttempt);
    if ("finishRequirementDiscoveryTurn".equals(toolName) && result.issues().stream()
        .anyMatch(issue -> "REPEATED_REJECTION".equals(issue.code()))) {
      return result;
    }
    if (!result.accepted() && result.issues().stream()
        .noneMatch(issue -> "CONTINUATION_NOT_AUTHORIZED".equals(issue.code()))) {
      store.recordCaptureAttempt(conversationId, false);
      String scope = store.captureMutationScope(conversationId);
      outcomeGateway.repeatedRequirementIssue(
          conversationId, scope, result.issues().stream()
              .map(issue -> new CaptureToolOutcomeGateway.TypedIssue(
                  issue.code(), issue.path(), issue.message(), issue.entityId())).toList());
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
    return traced(toolName, arguments, pending -> action.get());
  }

  private String traced(String toolName, Object arguments,
      java.util.function.Function<EditResult, RequirementCaptureResult> action) {
    String conversationId = ToolSession.resolveConversationId();
    long started = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, toolName, conversationId, "structured arguments");
    try {
      RequirementCaptureResult result;
      result = store.withWriteAuthority(conversationId, () -> {
        store.captureMutationScope(conversationId);
        var initial = arguments instanceof DraftUpdate update && update.pendingProposalToken() != null
            ? store.pendingInitialUpdate(conversationId, update) : null;
        EditResult pending = initial == null ? null : initial.edit();
        var selectedAttempt = initial == null ? null : initial.attempt();
        DraftInput candidate = pending != null ? pending.candidate()
            : arguments instanceof DraftInput draft ? draft : null;
        DraftInput resolvedCandidate = pending == null ? null : pending.candidate();
        var captureTool = List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName);
        var identical = captureTool
            && (feedbackStore.lastRequirementRejection(conversationId).isPresent()
                || feedbackStore.fingerprintStore().softCreditsUsed(conversationId) > 0)
            ? store.identicalRejections(conversationId, toolName, arguments, candidate) : List.<RequirementDraftStore.RequirementCaptureAttempt>of();
        if (identical.size() >= 2) {
          store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
          var issues = new ArrayList<>(identical.getFirst().issues());
          issues.add(new RequirementCaptureResult.CaptureIssue("REPAIR_NO_PROGRESS", "/", null, null,
              "The same proposal was rejected under the current source, base, and repair authority. Submit a different correction."));
          var rejected = view(store.get(conversationId).orElse(null), false, false,
              issues.stream().map(issue -> new Issue(issue.code(), issue.path(), issue.entityId(), issue.message())).toList());
          store.recordCaptureAttempt(conversationId, false);
          feedbackStore.recordClassifiedRequirementFailure(conversationId,
              org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind.VALIDATION,
              CaptureFailureClass.IDENTICAL_SPAM, false, "REPAIR_NO_PROGRESS",
              issues.stream().map(issue -> new CaptureToolOutcomeGateway.TypedIssue(
                  issue.code(), issue.path(), issue.message(), issue.entityId())).toList(), true);
          var stopped = new RequirementCaptureResult(false, false, rejected.revision(), rejected.readiness(),
              RequirementCaptureResult.NextAction.STOP, rejected.issues(), rejected.pendingWork(), rejected.draft(),
              rejected.resolutions(), rejected.defaults());
          store.recordCaptureOutcome(conversationId, toolName, arguments, stopped, resolvedCandidate, selectedAttempt);
          return stopped;
        }
        var outcome = recordOutcome(toolName, arguments, action.apply(pending), resolvedCandidate, selectedAttempt);
        if (identical.size() == 1 && !outcome.accepted()) {
          var noticed = new ArrayList<>(outcome.issues());
          String open = outcome.issues().stream().map(RequirementCaptureResult.CaptureIssue::message)
              .filter(message -> message != null && !message.isBlank()).findFirst()
              .orElse("The previous rejection still applies.");
          noticed.add(new RequirementCaptureResult.CaptureIssue("UNCHANGED_PROPOSAL", "/", null, null,
              "This proposal is unchanged. " + open
                  + " Change the rejected field. Sending this same proposal again ends the correction."));
          outcome = new RequirementCaptureResult(outcome.accepted(), outcome.changed(), outcome.revision(),
              outcome.readiness(), RequirementCaptureResult.NextAction.REPAIR_CAPTURE, List.copyOf(noticed),
              outcome.pendingWork(), outcome.draft(), outcome.resolutions(), outcome.defaults());
        }
        return outcome;
      });
      result = result.withRepairConstraints(store.repairConstraints(conversationId));
      if (recoveryControl != null && "captureRequirementDraft".equals(toolName) && arguments instanceof DraftInput draft) {
        recoveryControl.requirementOutcome(conversationId, draft, result);
      }
      String encoded = encode(result);
      String summary = "accepted=" + result.accepted() + " changed=" + result.changed()
          + " readiness=" + result.readiness() + " issues="
          + result.issues().stream().map(RequirementCaptureResult.CaptureIssue::code).toList();
      ToolTraceLog.logToolComplete(
          LOG, toolName, conversationId, System.currentTimeMillis() - started, summary);
      // shortcut: only identical persisted proposals terminate; changed candidates need a semantic progress proof.
      if (List.of("captureRequirementDraft", "updateRequirementDraft").contains(toolName)
          && result.issues().stream().anyMatch(issue -> "REPAIR_NO_PROGRESS".equals(issue.code()))) {
        var rejection = feedbackStore.lastRequirementRejection(conversationId).orElseThrow();
        var feedback = rejection.feedback();
        var rejected = store.rejectedCaptureRevision(conversationId).orElseThrow();
        throw new CaptureNoProgressException("REPAIR_NO_PROGRESS: " + rejection.issues().stream()
            .map(issue -> issue.code() + " " + issue.path() + ": " + issue.message())
            .collect(Collectors.joining("\n")),
            new CaptureFailureEvidence(feedback, result.revision(), rejected.reference(),
                store.validateForContinuation(conversationId).stream().map(issue ->
                    new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
                        issue.code(), issue.message(), true, null, issue.entityId(),
                        CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()))).toList()));
      }
      return encoded;
    } catch (RuntimeException error) {
      ToolTraceLog.logToolFailed(
          LOG, toolName, conversationId, System.currentTimeMillis() - started, error);
      throw error;
    }
  }
}
