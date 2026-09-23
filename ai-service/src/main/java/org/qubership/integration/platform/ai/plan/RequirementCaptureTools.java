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
import java.util.function.Supplier;
import org.jboss.logging.Logger;
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

  @Inject
  public RequirementCaptureTools(
      RequirementDraftStore store, RequirementDraftTool legacyBindingAdapter, ObjectMapper mapper,
      CaptureToolOutcomeGateway outcomeGateway, CaptureAttemptFeedbackStore feedbackStore,
      RequirementTargetResolver targetResolver) {
    this.store = store;
    this.legacyBindingAdapter = legacyBindingAdapter;
    this.mapper = mapper;
    this.outcomeGateway = outcomeGateway;
    this.feedbackStore = feedbackStore;
    this.targetResolver = targetResolver;
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
      Never send complete, decision, assembledText, catalog bindings, or revision references.
      """)
  public String captureRequirementDraft(@P("Authored requirement snapshot") DraftInput draft) {
    return traced("captureRequirementDraft", () -> {
      String conversationId = ToolSession.resolveConversationId();
      synchronized (store) {
        RequirementDraft previous = store.get(conversationId).orElse(null);
        if (previous != null && previous.authoredDraft() != null) {
          if (Objects.equals(previous.authoredDraft(), draft)) {
            store.recordCaptureAttempt(conversationId, true);
            feedbackStore.clearRequirement(conversationId);
            return view(previous, true, false, List.of());
          }
          return view(previous, false, false, List.of(new Issue(
              "DRAFT_ALREADY_EXISTS", "/draft", null,
              "Read the accepted draft and use updateRequirementDraft for changes.")));
        }
        EditResult edit = RequirementCaptureEditor.initialize(draft);
        if (!edit.accepted()) {
          return view(previous, false, false, edit.issues());
        }
        return publish(conversationId, draft, previous);
      }
    });
  }

  @Tool("""
      Apply focused requirement changes atomically. Each updated entity is a complete small record.
      Empty lists leave their entity family unchanged. Remove references explicitly when deleting an
      interaction. A rejected edit retains the accepted draft and bindings.
      """)
  public String updateRequirementDraft(@P("Focused edits to the accepted draft") DraftUpdate changes) {
    return traced("updateRequirementDraft", () -> {
      String conversationId = ToolSession.resolveConversationId();
      synchronized (store) {
        RequirementDraft previous = store.get(conversationId).orElse(null);
        EditResult edit = RequirementCaptureEditor.apply(
            previous == null ? null : previous.authoredDraft(), changes);
        if (!edit.accepted()) {
          return view(previous, false, false, edit.issues());
        }
        if (!edit.changed()) {
          store.recordCaptureAttempt(conversationId, true);
          feedbackStore.clearRequirement(conversationId);
          return view(previous, true, false, List.of());
        }
        return publish(conversationId, edit.draft(), previous);
      }
    });
  }

  @Tool("Read the complete accepted requirements, resolution state, defaults, and remaining work.")
  public String readRequirementDraft() {
    return traced("readRequirementDraft", () -> {
      String conversationId = ToolSession.resolveConversationId();
      synchronized (store) {
        RequirementDraft current = store.get(conversationId).orElse(null);
        if (current == null || current.authoredDraft() == null) {
          return view(current, true, false, List.of());
        }
        List<CatalogBindingHint> bindings = legacyBindingAdapter.canonicalBindings(
            current.authoredDraft(), current, conversationId);
        if (!bindings.equals(current.catalogBindings())) {
          RequirementDraft refreshed = RequirementCaptureProjection.toDraft(
              current.authoredDraft(), current, bindings,
              current.sourceSkillVersion(), current.sourceSkillHash());
          store.put(conversationId, refreshed);
          current = refreshed;
        }
        return view(current, true, false, List.of());
      }
    });
  }

  @Tool("""
      Finish the requirement-discovery turn once, after captures and lookups. Use STAY for discussion
      and CONTINUE only when the author requested design or chain creation. This does not mutate or
      approve the accepted draft.
      """)
  public String finishRequirementDiscoveryTurn(
      @P("STAY for discussion; CONTINUE for an authorized next step")
          RequirementDiscoveryDirective directive) {
    return traced("finishRequirementDiscoveryTurn", () -> {
      String conversationId = ToolSession.resolveConversationId();
      if (feedbackStore.lastRequirementFailure(conversationId)
          .filter(failure -> failure.failureClass() == CaptureFailureClass.IDENTICAL_SPAM)
          .isPresent()) {
        store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
        return view(store.get(conversationId).orElse(null), false, false,
            List.of(new Issue("REPEATED_REJECTION", "/", null,
                "Requirement capture stopped after a repeated rejection.")));
      }
      if (directive == null || directive == RequirementDiscoveryDirective.NONE) {
        return view(store.get(conversationId).orElse(null), false, false,
            List.of(new Issue("INVALID_ENUM", "/directive", null,
                "Use STAY or CONTINUE.")));
      }
      store.finishTurn(conversationId, directive);
      return view(store.get(conversationId).orElse(null), true, false, List.of());
    });
  }

  private RequirementCaptureResult publish(
      String conversationId, DraftInput input, RequirementDraft previous) {
    List<Issue> preferenceIssues = RequirementPreferenceEvidence.validate(
        input, previous, store.currentAuthorText(conversationId).orElse(null),
        previous == null || previous.authoredDraft() == null ? "/draft" : "/changes");
    if (!preferenceIssues.isEmpty()) {
      return view(previous, false, false, preferenceIssues);
    }
    RequirementTargetResolver.Resolution targetResolution = targetResolver == null
        ? new RequirementTargetResolver.Resolution(Map.of(), List.of())
        : targetResolver.resolve(input);
    if (!targetResolution.issues().isEmpty()) {
      return view(previous, false, false, targetResolution.issues());
    }
    List<CatalogBindingHint> bindings =
        legacyBindingAdapter.canonicalBindings(input, previous, conversationId);
    RequirementDraft draft = RequirementCaptureProjection.toDraft(
        input, previous, bindings,
        legacyBindingAdapter.sourceSkillVersion(conversationId),
        legacyBindingAdapter.sourceSkillHash(conversationId), targetResolution.targetIds());
    store.put(conversationId, draft);
    store.recordCaptureAttempt(conversationId, true);
    feedbackStore.clearRequirement(conversationId);
    store.markCaptured(conversationId);
    ProductCapabilityCaptureContext.offerDraft(draft);
    return view(draft, true, true, List.of());
  }

  private RequirementCaptureResult view(
      RequirementDraft draft, boolean accepted, boolean changed, List<Issue> issues) {
    String conversationId = ToolSession.resolveConversationId();
    if (!accepted) {
      store.recordCaptureAttempt(conversationId, false);
      String scope = store.captureMutationScope(conversationId);
      boolean repeated = outcomeGateway.repeatedRequirementIssue(
          conversationId, scope, issues.stream()
              .map(issue -> new CaptureToolOutcomeGateway.TypedIssue(
                  issue.code(), issue.path())).toList());
      if (repeated) {
        store.finishTurn(conversationId, RequirementDiscoveryDirective.STAY);
        List<Issue> terminal = new ArrayList<>(issues);
        terminal.add(new Issue("REPEATED_REJECTION", "/", null,
            "The same requirement issue was rejected again. Stop automatic repair."));
        issues = List.copyOf(terminal);
      }
    }
    Reference reference = store.latestRevision(ToolSession.resolveConversationId())
        .map(revision -> revision.reference()).orElse(null);
    return RequirementCaptureResult.view(
        draft, reference, accepted, changed, issues, store.turnDirective(conversationId));
  }

  private String encode(RequirementCaptureResult result) {
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize requirement capture result", e);
    }
  }

  String rejectArguments(String toolName, Issue issue) {
    return traced(toolName, () -> view(
        store.get(ToolSession.resolveConversationId()).orElse(null),
        false, false, List.of(issue)));
  }

  private String traced(String toolName, Supplier<RequirementCaptureResult> action) {
    String conversationId = ToolSession.resolveConversationId();
    long started = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, toolName, conversationId, "structured arguments");
    try {
      RequirementCaptureResult result = action.get();
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
