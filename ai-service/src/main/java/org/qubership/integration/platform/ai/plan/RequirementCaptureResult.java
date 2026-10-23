package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

/** The accepted requirement view returned by each discovery tool. */
public record RequirementCaptureResult(
    boolean accepted,
    boolean changed,
    Reference revision,
    Readiness readiness,
    NextAction nextAction,
    List<CaptureIssue> issues,
    List<RequirementCaptureProjection.PendingWork> pendingWork,
    DraftInput draft,
    List<ResolutionSummary> resolutions,
    List<RequirementCaptureProjection.AppliedDefault> defaults,
    @JsonInclude(JsonInclude.Include.NON_NULL) RepairConstraints repairConstraints,
    @JsonInclude(JsonInclude.Include.NON_NULL) PendingProposal pendingProposal) {

  public RequirementCaptureResult(boolean accepted, boolean changed, Reference revision, Readiness readiness,
      NextAction nextAction, List<CaptureIssue> issues, List<RequirementCaptureProjection.PendingWork> pendingWork,
      DraftInput draft, List<ResolutionSummary> resolutions, List<RequirementCaptureProjection.AppliedDefault> defaults,
      RepairConstraints repairConstraints) {
    this(accepted, changed, revision, readiness, nextAction, issues, pendingWork, draft, resolutions, defaults,
        repairConstraints, null);
  }

  public RequirementCaptureResult(boolean accepted, boolean changed, Reference revision, Readiness readiness,
      NextAction nextAction, List<CaptureIssue> issues, List<RequirementCaptureProjection.PendingWork> pendingWork,
      DraftInput draft, List<ResolutionSummary> resolutions, List<RequirementCaptureProjection.AppliedDefault> defaults) {
    this(accepted, changed, revision, readiness, nextAction, issues, pendingWork, draft, resolutions, defaults, null);
  }

  public record RepairConstraints(List<RequirementCaptureInput.FactInput> facts, JsonNode values) {
    public RepairConstraints {
      facts = List.copyOf(facts);
      values = values == null ? null : values.deepCopy();
    }
  }

  /** A rejected candidate is a repair base, never an admitted draft or amendment authority. */
  public record PendingProposal(Reference attempt, String sourceGeneration, String sourceTurnId, Reference base,
      String toolName, DraftInput draft, List<CaptureIssue> issues,
      @JsonInclude(JsonInclude.Include.NON_NULL) String token) {
    public PendingProposal(Reference attempt, String sourceGeneration, String sourceTurnId, Reference base,
        String toolName, DraftInput draft, List<CaptureIssue> issues) {
      this(attempt, sourceGeneration, sourceTurnId, base, toolName, draft, issues, null);
    }
    public PendingProposal {
      issues = List.copyOf(issues);
    }
  }

  public RequirementCaptureResult withPendingProposal(PendingProposal proposal) {
    return new RequirementCaptureResult(accepted, changed, revision, readiness, nextAction, issues, pendingWork,
        draft, resolutions, defaults, repairConstraints, proposal);
  }

  public RequirementCaptureResult withRepairConstraints(RepairConstraints constraints) {
    if (constraints == null) return this;
    NextAction action = readiness == Readiness.EMPTY && nextAction == NextAction.DISCUSS
        ? NextAction.REPAIR_CAPTURE : nextAction;
    return new RequirementCaptureResult(accepted, changed, revision, readiness, action, issues, pendingWork,
        draft, resolutions, defaults, constraints, pendingProposal);
  }

  public enum Readiness {
    EMPTY,
    PARTIAL,
    READY
  }

  public enum NextAction {
    DISCUSS,
    COMPLETE_DRAFT,
    LOOKUP,
    ASK_USER,
    REQUEST_IMPORT_APPROVAL,
    RUN_IMPORT,
    REQUEST_IDS_CHOICE,
    AWAIT_APPROVAL,
    HANDOFF,
    REFRESH_DRAFT,
    REPAIR_CAPTURE,
    STOP
  }

  public record CaptureIssue(
      String code, String path, String entityId, IssueDetails details, String message) {}

  public record IssueDetails(
      String expectedType, List<String> allowedValues, List<String> relatedEntityIds) {}

  public record ResolutionSummary(
      String interactionId, String kind, String status, String displayName) {}

  public static RequirementCaptureResult view(
      RequirementDraft acceptedDraft,
      Reference revision,
      boolean accepted,
      boolean changed,
      List<RequirementCaptureEditor.Issue> editorIssues,
      RequirementDiscoveryDirective directive) {
    return view(acceptedDraft, revision, accepted, changed, editorIssues, directive, Set.of());
  }

  public static RequirementCaptureResult view(
      RequirementDraft acceptedDraft, Reference revision, boolean accepted, boolean changed,
      List<RequirementCaptureEditor.Issue> editorIssues, RequirementDiscoveryDirective directive,
      Set<String> uploadedSpecInteractions) {
    DraftInput draft = acceptedDraft == null ? null : acceptedDraft.authoredDraft();
    List<CaptureIssue> issues = editorIssues.stream()
        .map(issue -> new CaptureIssue(
            issue.code(), issue.path(), issue.entityId(),
            new IssueDetails(null, List.of(), List.of()), issue.message()))
        .toList();
    if (draft == null) {
      return new RequirementCaptureResult(
          accepted, changed, revision, Readiness.EMPTY,
          issues.isEmpty() ? NextAction.DISCUSS : issueAction(issues), issues,
          List.of(new RequirementCaptureProjection.PendingWork(
              "DRAFT_CONTENT_REQUIRED", null, null, "No requirement draft has been saved.")),
          null, List.of(), List.of());
    }
    List<RequirementCaptureProjection.PendingWork> pending =
        RequirementCaptureProjection.pending(draft, acceptedDraft.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(acceptedDraft), uploadedSpecInteractions);
    Readiness readiness = acceptedDraft.readyForPlan() ? Readiness.READY : Readiness.PARTIAL;
    NextAction action = nextAction(issues, pending, readiness, draft, directive);
    if (action == NextAction.COMPLETE_DRAFT && !acceptedDraft.openQuestions().isEmpty()) {
      action = NextAction.ASK_USER;
    }
    return new RequirementCaptureResult(
        accepted, changed, revision, readiness, action, issues, pending, draft,
        resolutions(draft, acceptedDraft.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(acceptedDraft)),
        RequirementCaptureProjection.defaults(draft));
  }

  private static NextAction nextAction(
      List<CaptureIssue> issues,
      List<RequirementCaptureProjection.PendingWork> pending,
      Readiness readiness,
      DraftInput draft,
      RequirementDiscoveryDirective directive) {
    if (issues.stream().anyMatch(issue -> "CONTINUATION_NOT_AUTHORIZED".equals(issue.code()))) {
      return pending.isEmpty() ? NextAction.COMPLETE_DRAFT : pendingAction(pending);
    }
    return issues.isEmpty()
        ? continuationAction(pending, readiness, draft, directive)
        : issueAction(issues);
  }

  private static NextAction issueAction(List<CaptureIssue> issues) {
    if (issues.stream().anyMatch(issue -> Set.of(
        "UNSUPPORTED_CAPABILITY", "UNSUPPORTED_RETRY_POLICY", "REPEATED_REJECTION",
        "REPAIR_BUDGET_EXHAUSTED", "TOOL_BUDGET_EXHAUSTED", "INTERNAL_CAPTURE_FAILURE",
        "OUTPUT_TRUNCATED", "STRICT_MODE_UNSUPPORTED", "INVALID_PROVIDER_CONFIGURATION")
        .contains(issue.code()))) {
      return NextAction.STOP;
    }
    if (issues.stream().anyMatch(issue ->
        "STALE_DRAFT".equals(issue.code()) || "STATE_WRITE_FAILED".equals(issue.code()))) {
      return NextAction.REFRESH_DRAFT;
    }
    if (issues.stream().anyMatch(issue ->
        "CONTINUATION_NOT_AUTHORIZED".equals(issue.code()))) {
      return NextAction.COMPLETE_DRAFT;
    }
    return NextAction.REPAIR_CAPTURE;
  }

  private static NextAction pendingAction(
      List<RequirementCaptureProjection.PendingWork> pending) {
    if (RequirementCaptureProjection.onlyUploadedSpecImportsRemain(pending)) {
      return NextAction.RUN_IMPORT;
    }
    if (pending.stream().anyMatch(work -> work.code().endsWith("LOOKUP_REQUIRED"))) {
      return NextAction.LOOKUP;
    }
    if (pending.stream().anyMatch(work -> "BUSINESS_CHOICE_REQUIRED".equals(work.code()))) {
      return NextAction.ASK_USER;
    }
    return pending.isEmpty() ? NextAction.DISCUSS : NextAction.COMPLETE_DRAFT;
  }

  private static NextAction continuationAction(
      List<RequirementCaptureProjection.PendingWork> pending,
      Readiness readiness,
      DraftInput draft,
      RequirementDiscoveryDirective directive) {
    if (directive == RequirementDiscoveryDirective.CONTINUE && readiness == Readiness.READY) {
      return draft.settings().idsRequested() == null
          ? NextAction.REQUEST_IDS_CHOICE : NextAction.HANDOFF;
    }
    if (directive == RequirementDiscoveryDirective.STAY && pending.isEmpty()) {
      return NextAction.DISCUSS;
    }
    return pendingAction(pending);
  }

  private static List<ResolutionSummary> resolutions(
      DraftInput draft, List<CatalogBindingHint> bindings, Map<String, String> targets) {
    Map<String, CatalogBindingHint> byInteraction = bindings.stream()
        .collect(Collectors.toMap(CatalogBindingHint::interactionId, hint -> hint, (a, b) -> a));
    Map<String, CapabilityInput> capabilities = draft.capabilities().stream()
        .collect(Collectors.toMap(CapabilityInput::interactionId, capability -> capability));
    return draft.flow().interactions().stream()
        .map(interaction -> resolution(interaction, capabilities.get(interaction.interactionId()),
            byInteraction.containsKey(interaction.interactionId()),
            targets.containsKey(interaction.interactionId())))
        .filter(summary -> summary != null)
        .toList();
  }

  private static ResolutionSummary resolution(
      InteractionInput interaction, CapabilityInput capability, boolean bound,
      boolean targetResolved) {
    String key = capability == null ? null : capability.capabilityKey();
    String kind;
    boolean resolved;
    if ("chain-call-2".equals(key)) {
      kind = "CHAIN";
      resolved = targetResolved;
    } else if ("mcp-trigger".equals(key)) {
      kind = "MCP";
      resolved = targetResolved;
    } else if (interaction.direction() == Direction.OUTBOUND && capability == null
        && !RequirementFlowValidator.isDirectHttpTarget(interaction.participant())
        || "async-api-trigger".equals(key)
        || "http-trigger".equals(key)
            && capability.httpMode() == RequirementCaptureInput.HttpMode.CATALOG) {
      kind = "OPERATION";
      resolved = bound;
    } else {
      return null;
    }
    return new ResolutionSummary(interaction.interactionId(), kind,
        resolved ? "RESOLVED" : "UNRESOLVED", interaction.participant());
  }
}
