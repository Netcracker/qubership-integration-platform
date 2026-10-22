package org.qubership.integration.platform.ai.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

/** Checks HTTP response continuation without changing authored call properties. */
public final class RequirementFailureConsistency {
  private RequirementFailureConsistency() {}

  static List<Issue> issues(DraftInput input, List<CatalogBindingHint> bindings) {
    if (input == null || !RequirementCaptureEditor.validate(input).isEmpty()) return List.of();
    return issues(RequirementCaptureProjection.flow(input), RequirementCaptureProjection.facts(input), bindings);
  }

  static List<Issue> issues(RequirementDraft draft) {
    if (draft == null) return List.of();
    return draft.authoredDraft() == null ? issues(draft.flow(), draft.facts(), draft.catalogBindings())
        : issues(draft.authoredDraft(), draft.catalogBindings());
  }

  static boolean correctsOnlyModes(DraftInput base, DraftInput candidate, List<Issue> findings) {
    var ids = findings.stream().map(Issue::entityId).toList();
    var interactions = base.flow().interactions().stream().map(original -> {
      if (!ids.contains(original.interactionId())) return original;
      boolean corrected = candidate.flow().interactions().stream().anyMatch(value ->
          original.interactionId().equals(value.interactionId())
              && value.failureMode() == ServiceCallFailureMode.INLINE_RESPONSE);
      return corrected ? new RequirementCaptureInput.InteractionInput(original.interactionId(), original.direction(),
          original.participant(), original.operation(), original.description(), ServiceCallFailureMode.INLINE_RESPONSE,
          original.retryPolicy()) : original;
    }).toList();
    return candidate.equals(new DraftInput(new RequirementCaptureInput.FlowInput(interactions, base.flow().transitions()),
        base.facts(), base.capabilities(), base.openQuestions(), base.settings()));
  }

  static List<Issue> unauthorizedModeChanges(DraftInput base, DraftInput candidate,
      List<CatalogBindingHint> bindings, java.util.Set<String> authorPaths, java.util.Set<String> repairPaths) {
    if (base == null) return List.of();
    return issues(base, bindings).stream().filter(finding -> {
      String id = finding.entityId();
      var before = base.flow().interactions().stream().filter(value -> id.equals(value.interactionId())).findFirst().orElseThrow();
      var after = candidate.flow().interactions().stream().filter(value -> id.equals(value.interactionId())).findFirst();
      String path = "/interactions/" + id + "/failureMode";
      return after.isPresent() && before.failureMode() != after.orElseThrow().failureMode()
          && !repairPaths.contains(path)
          && authorPaths.stream().noneMatch(allowed -> path.equals(allowed) || path.startsWith(allowed + "/"));
    }).map(finding -> new Issue("AUTHOR_AMENDMENT_REQUIRED", finding.path(), finding.entityId(),
        "The current HTTP-response finding permits only an exact mode-only correction to INLINE_RESPONSE"
            + " under the same source, base, and catalog binding. Other changes require a validated author amendment."))
        .toList();
  }

  private static List<Issue> issues(
      RequirementFlow flow, List<RequirementFact> facts, List<CatalogBindingHint> bindings) {
    if (flow == null || facts == null || bindings == null) return List.of();
    var findings = new ArrayList<Issue>();
    for (var call : flow.interactions()) {
      String id = call.interactionId();
      if (call.direction() != Direction.OUTBOUND || call.failureMode() != ServiceCallFailureMode.PROPAGATE
          || flow.interactions().stream().filter(value -> id.equals(value.interactionId())).count() != 1) continue;
      var resolved = bindings.stream().filter(Objects::nonNull)
          .filter(value -> id.equals(value.interactionId())).toList();
      if (resolved.size() != 1 || !"http".equalsIgnoreCase(resolved.getFirst().protocol())) continue;
      var anchors = facts.stream().filter(Objects::nonNull)
          .filter(fact -> fact.elementIntent() != null && id.equals(fact.elementIntent().elementRef())
              && fact.elementIntent().placement() == RequirementElementIntent.Placement.ANCHOR).toList();
      if (anchors.size() != 1) continue;
      var fact = anchors.getFirst();
      var anchor = fact.elementIntent();
      if (fact.polarity() != RequirementFactPolarity.POSITIVE || !fact.interactionIds().equals(List.of(id))
          || !id.equals(anchor.ownerInteractionId()) || !"service-call".equals(anchor.elementType())
          || anchor.requestedProperties() == null) continue;
      var flags = anchor.requestedProperties().stream().filter(Objects::nonNull)
          .filter(property -> "errorThrowing".equals(property.key())).toList();
      if (flags.size() != 1 || flags.getFirst().value() == null
          || !"false".equalsIgnoreCase(flags.getFirst().value().trim())) continue;
      if (facts.stream().anyMatch(response -> normalResponse(flow, facts, id, anchor, response))) {
        findings.add(new Issue("FAILURE_MODE_CONFLICT", "/draft/flow/interactions/" + id + "/failureMode", id,
            "HTTP error responses continue from '" + id + "' to its requested normal response step because"
                + " errorThrowing=false. PROPAGATE says those responses stop before successors. Correct only"
                + " failureMode to INLINE_RESPONSE; preserve errorThrowing=false, all facts, names, values,"
                + " and order. Transport exceptions still propagate; this finding concerns HTTP responses."));
      }
    }
    return List.copyOf(findings);
  }

  private static boolean normalResponse(RequirementFlow flow, List<RequirementFact> facts, String call,
      RequirementElementIntent anchor, RequirementFact fact) {
    if (fact == null || fact.polarity() != RequirementFactPolarity.POSITIVE
        || fact.kind() != RequirementFactKind.BEHAVIOR && fact.kind() != RequirementFactKind.ROUTING
        || fact.elementIntent() == null || !fact.interactionIds().contains(call)) return false;
    var response = fact.elementIntent();
    String ref = response.elementRef();
    if (response.placement() != RequirementElementIntent.Placement.LOCAL_NODE || ref == null || ref.isBlank()
        || call.equals(ref) || facts.stream().filter(Objects::nonNull)
            .filter(value -> value.elementIntent() != null && ref.equals(value.elementIntent().elementRef())).count() != 1
        || response.predecessorElementRef() != null && !call.equals(response.predecessorElementRef())
        || anchor.successorElementRef() != null && !ref.equals(anchor.successorElementRef())
        || !call.equals(response.predecessorElementRef()) && !ref.equals(anchor.successorElementRef())) return false;
    String owner = response.ownerInteractionId();
    if (call.equals(owner)) return true;
    return owner != null && fact.interactionIds().contains(owner)
        && flow.interactions().stream().filter(value -> owner.equals(value.interactionId())
            && value.direction() == Direction.OUTBOUND).count() == 1
        && flow.transitions().stream().filter(edge -> call.equals(edge.sourceInteractionId())).count() == 1
        && flow.transitions().stream().anyMatch(edge -> call.equals(edge.sourceInteractionId())
            && owner.equals(edge.targetInteractionId()));
  }
}
