package org.qubership.integration.platform.ai.plan;

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
    List<RequirementCaptureProjection.AppliedDefault> defaults) {

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
            RequirementCaptureProjection.resolvedTargets(acceptedDraft));
    Readiness readiness = acceptedDraft.readyForPlan() ? Readiness.READY : Readiness.PARTIAL;
    NextAction action = issues.isEmpty()
        ? continuationAction(pending, readiness, draft, directive)
        : issueAction(issues);
    return new RequirementCaptureResult(
        accepted, changed, revision, readiness, action, issues, pending, draft,
        resolutions(draft, acceptedDraft.catalogBindings(),
            RequirementCaptureProjection.resolvedTargets(acceptedDraft)),
        RequirementCaptureProjection.defaults(draft));
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
      return NextAction.DISCUSS;
    }
    return NextAction.REPAIR_CAPTURE;
  }

  private static NextAction pendingAction(
      List<RequirementCaptureProjection.PendingWork> pending) {
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
