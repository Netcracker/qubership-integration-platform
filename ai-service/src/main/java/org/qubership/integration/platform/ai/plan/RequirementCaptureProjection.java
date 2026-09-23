package org.qubership.integration.platform.ai.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;

/** Derives the existing requirement artifact from the accepted authored content. */
public final class RequirementCaptureProjection {

  public record PendingWork(
      String code, String interactionId, String questionId, String message) {}

  public record AppliedDefault(String code, String interactionId, String message) {}

  private RequirementCaptureProjection() {}

  public static RequirementFlow flow(DraftInput input) {
    return new RequirementFlow(
        input.flow().interactions().stream()
            .map(interaction -> new RequirementFlow.Interaction(
                interaction.interactionId(), interaction.direction(), interaction.participant(),
                interaction.operation(), interaction.description(), interaction.failureMode()))
            .toList(),
        input.flow().transitions().stream()
            .map(edge -> new RequirementFlow.Transition(
                edge.sourceInteractionId(), edge.targetInteractionId()))
            .toList());
  }

  public static List<RequirementFact> facts(DraftInput input) {
    return facts(input, Map.of());
  }

  public static List<RequirementFact> facts(DraftInput input, Map<String, String> targets) {
    List<RequirementFact> projected = new ArrayList<>();
    for (FactInput fact : input.facts()) {
      projected.add(new RequirementFact(
          fact.sourceFactId(), RequirementFactPolarity.valueOf(fact.polarity().name()),
          RequirementFactKind.valueOf(fact.kind().name()), "", fact.text(), "", "", "", "", "",
          "", fact.interactionIds()));
    }
    Map<String, InteractionInput> interactions = new HashMap<>();
    for (InteractionInput interaction : input.flow().interactions()) {
      interactions.put(interaction.interactionId(), interaction);
    }
    for (CapabilityInput capability : input.capabilities()) {
      InteractionInput owner = interactions.get(capability.interactionId());
      String path = "chain-call-2".equals(capability.capabilityKey())
              || "mcp-trigger".equals(capability.capabilityKey())
          ? targets.get(capability.interactionId()) : capability.path();
      String operation = capability.mcpServerId() != null
          ? capability.mcpServerId() : owner.operation();
      projected.add(new RequirementFact(
          capability.interactionId(), RequirementFactPolarity.POSITIVE,
          RequirementFactKind.CAPABILITY, capability.capabilityKey(),
          "Use " + capability.capabilityKey() + " for " + owner.participant() + ".",
          owner.participant(), operation,
          capability.topic(), capability.httpMethod() == null ? null : capability.httpMethod().name(),
          path, "", List.of(capability.interactionId())));
    }
    for (InteractionInput interaction : input.flow().interactions()) {
      if (interaction.retryPolicy() == null || interaction.retryPolicy().retryCount() == null) {
        continue;
      }
      int count = interaction.retryPolicy().retryCount();
      int delay = interaction.retryPolicy().retryDelayMs() == null
          ? 5000 : interaction.retryPolicy().retryDelayMs();
      projected.add(new RequirementFact(
          interaction.interactionId() + "-retry", count == 0
              ? RequirementFactPolarity.NEGATIVE : RequirementFactPolarity.POSITIVE,
          RequirementFactKind.BEHAVIOR, "",
          count == 0 ? "Do not retry this call."
              : "Retry this call " + count + " times with a delay of " + delay + " ms.",
          "", "", "", "", "", "", List.of(interaction.interactionId())));
    }
    return List.copyOf(projected);
  }

  public static List<PendingWork> pending(
      DraftInput input, List<CatalogBindingHint> bindings) {
    return pending(input, bindings, Map.of());
  }

  public static List<PendingWork> pending(
      DraftInput input, List<CatalogBindingHint> bindings, Map<String, String> targets) {
    List<PendingWork> pending = new ArrayList<>();
    Map<String, CapabilityInput> capabilities = new HashMap<>();
    for (CapabilityInput capability : input.capabilities()) {
      capabilities.put(capability.interactionId(), capability);
    }
    Set<String> bound = new HashSet<>();
    for (CatalogBindingHint binding : bindings) {
      bound.add(binding.interactionId());
    }
    if (input.flow().interactions().isEmpty()) {
      pending.add(new PendingWork("ENTRY_POINT_REQUIRED", null, null,
          "Describe what starts the integration."));
    }
    boolean hasInbound = false;
    for (InteractionInput interaction : input.flow().interactions()) {
      String id = interaction.interactionId();
      if (interaction.direction() == Direction.INBOUND) {
        hasInbound = true;
      }
      if (interaction.direction() == null || blank(interaction.participant())
          || blank(interaction.operation())) {
        pending.add(new PendingWork("INTERACTION_FIELD_REQUIRED", id, null,
            "Describe the interaction's direction, participant, and operation."));
      }
      CapabilityInput capability = capabilities.get(id);
      if (interaction.direction() == Direction.OUTBOUND && capability == null
          && RequirementFlowValidator.isDirectHttpTarget(interaction.participant())) {
        pending.add(new PendingWork("CAPABILITY_CHOICE_REQUIRED", id, null,
            "The named HTTP URI needs an http-sender capability with its method and URI."));
      }
      if (interaction.direction() == Direction.INBOUND && capability == null) {
        pending.add(new PendingWork("CAPABILITY_CHOICE_REQUIRED", id, null,
            "Choose the entry point's native capability."));
      }
      if (capability != null) {
        if ("http-trigger".equals(capability.capabilityKey())
            && capability.httpMode() == null) {
          pending.add(new PendingWork("CAPABILITY_CHOICE_REQUIRED", id, null,
              "Choose custom or catalog HTTP mode."));
        }
        if (("http-sender".equals(capability.capabilityKey())
                || capability.httpMode() == RequirementCaptureInput.HttpMode.CUSTOM)
            && (capability.httpMethod() == null || blank(capability.path()))) {
          pending.add(new PendingWork("CAPABILITY_FIELD_REQUIRED", id, null,
              "Provide the HTTP method and path or URI."));
        }
        if ("chain-call-2".equals(capability.capabilityKey())
            && !targets.containsKey(id)) {
          pending.add(new PendingWork("TARGET_LOOKUP_REQUIRED", id, null,
              "Resolve the named target chain."));
        }
        if ("mcp-trigger".equals(capability.capabilityKey())
            && !targets.containsKey(id)) {
          pending.add(new PendingWork("TARGET_LOOKUP_REQUIRED", id, null,
              "Resolve the named MCP service."));
        }
      }
      if (interaction.retryPolicy() != null
          && interaction.retryPolicy().retryCount() == null) {
        pending.add(new PendingWork("RETRY_COUNT_REQUIRED", id, null,
            "Provide the requested retry count."));
      }
      if (interaction.failureMode() != null
          && interaction.failureMode() != ServiceCallFailureMode.PROPAGATE) {
        boolean hasSupportingFact = input.facts().stream().anyMatch(fact ->
            fact.interactionIds().contains(id)
                && (fact.kind() == RequirementCaptureInput.FactKind.BEHAVIOR
                    || fact.kind() == RequirementCaptureInput.FactKind.ROUTING));
        boolean hasSuccessor = input.flow().transitions().stream().anyMatch(edge ->
            id.equals(edge.sourceInteractionId()));
        if (!hasSupportingFact || (interaction.failureMode() == ServiceCallFailureMode.INLINE_RESPONSE
            && !hasSuccessor)) {
          pending.add(new PendingWork("FAILURE_RESPONSE_REQUIRED", id, null,
              "Describe the requested failure path and its destination."));
        }
      }
      boolean needsCatalog = interaction.direction() == Direction.OUTBOUND && capability == null
          && !RequirementFlowValidator.isDirectHttpTarget(interaction.participant())
          || capability != null && ("async-api-trigger".equals(capability.capabilityKey())
              || "http-trigger".equals(capability.capabilityKey())
                  && capability.httpMode() == RequirementCaptureInput.HttpMode.CATALOG);
      if (needsCatalog && !bound.contains(id)
          && !blank(interaction.participant()) && !blank(interaction.operation())) {
        pending.add(new PendingWork("OPERATION_LOOKUP_REQUIRED", id, null,
            "Resolve the catalog operation for " + interaction.participant() + "."));
      }
    }
    if (!hasInbound && !input.flow().interactions().isEmpty()) {
      pending.add(new PendingWork("ENTRY_POINT_REQUIRED", null, null,
          "Describe what starts the integration."));
    }
    if (hasInbound && !allOutboundReachable(input)) {
      pending.add(new PendingWork("FLOW_ORDER_REQUIRED", null, null,
          "Connect each outbound operation to an entry point."));
    }
    if (input.facts().stream().noneMatch(fact -> fact.polarity() == RequirementCaptureInput.Polarity.POSITIVE
        && (fact.kind() == RequirementCaptureInput.FactKind.GOAL
            || fact.kind() == RequirementCaptureInput.FactKind.BEHAVIOR))) {
      pending.add(new PendingWork("FACTS_REQUIRED", null, null,
          "Record the intended result or behavior."));
    }
    for (QuestionInput question : input.openQuestions()) {
      pending.add(new PendingWork("BUSINESS_CHOICE_REQUIRED", null, question.questionId(),
          question.text()));
    }
    return List.copyOf(pending);
  }

  public static List<AppliedDefault> defaults(DraftInput input) {
    List<AppliedDefault> defaults = new ArrayList<>();
    if (input.settings().preferredSystemType() == null) {
      defaults.add(new AppliedDefault("DEFAULT_INTERNAL_SYSTEM", null,
          "The system is internal by default."));
    }
    for (InteractionInput interaction : input.flow().interactions()) {
      if (interaction.direction() != Direction.OUTBOUND) {
        continue;
      }
      if (interaction.failureMode() == null) {
        defaults.add(new AppliedDefault("DEFAULT_FAILURE_PROPAGATION", interaction.interactionId(),
            "Invocation failures propagate by default."));
      }
      if (interaction.retryPolicy() == null) {
        defaults.add(new AppliedDefault("DEFAULT_NO_CALL_RETRY", interaction.interactionId(),
            "The call has no configured retry by default."));
      } else if (interaction.retryPolicy().retryCount() != null
          && interaction.retryPolicy().retryCount() > 0
          && interaction.retryPolicy().retryDelayMs() == null) {
        defaults.add(new AppliedDefault("DEFAULT_RETRY_DELAY", interaction.interactionId(),
            "Retry delay defaults to 5000 ms."));
      }
    }
    return List.copyOf(defaults);
  }

  public static String render(DraftInput input, List<CatalogBindingHint> bindings) {
    StringBuilder text = new StringBuilder("Integration requirements:\n");
    Map<String, String> labels = new HashMap<>();
    int entries = 0;
    int calls = 0;
    int unspecified = 0;
    for (InteractionInput interaction : input.flow().interactions()) {
      String label;
      if (interaction.direction() == Direction.INBOUND) {
        label = "Entry " + ++entries;
      } else if (interaction.direction() == Direction.OUTBOUND) {
        label = "Call " + ++calls;
      } else {
        label = "Interaction " + ++unspecified;
      }
      labels.put(interaction.interactionId(), label);
      text.append("- ").append(label).append(": ").append(display(interaction.participant()))
          .append(" ").append(display(interaction.operation())).append('\n');
      if (!blank(interaction.description())) {
        text.append("  ").append(interaction.description()).append('\n');
      }
    }
    for (TransitionInput transition : input.flow().transitions()) {
      text.append("- After ").append(labels.get(transition.sourceInteractionId()))
          .append(", run ").append(labels.get(transition.targetInteractionId())).append(".\n");
    }
    for (CapabilityInput capability : input.capabilities()) {
      text.append("- ").append(labels.get(capability.interactionId())).append(" transport: ")
          .append(readableCapability(capability.capabilityKey()));
      if (capability.httpMode() != null) {
        text.append(" (").append(capability.httpMode().name().toLowerCase(java.util.Locale.ROOT))
            .append(")");
      }
      if (capability.httpMethod() != null && !blank(capability.path())) {
        text.append(": ").append(capability.httpMethod()).append(" ").append(capability.path());
      }
      if (!blank(capability.topic())) {
        text.append(": topic ").append(capability.topic());
      }
      text.append(".\n");
    }
    for (FactInput fact : input.facts()) {
      text.append("- ").append(fact.text());
      if (!fact.interactionIds().isEmpty()) {
        text.append(" (for ").append(fact.interactionIds().stream()
            .map(labels::get).reduce((left, right) -> left + ", " + right).orElse("")).append(")");
      }
      text.append('\n');
    }
    for (CatalogBindingHint binding : bindings) {
      text.append("- Catalog operation for ").append(labels.get(binding.interactionId()))
          .append(": ").append(binding.operationQuery()).append(".\n");
    }
    for (QuestionInput question : input.openQuestions()) {
      text.append("- Open question: ").append(question.text()).append('\n');
    }
    for (AppliedDefault applied : defaults(input)) {
      text.append("- Platform default: ").append(applied.message()).append('\n');
    }
    return text.toString().trim();
  }

  private static String readableCapability(String key) {
    return switch (key) {
      case "http-trigger" -> "HTTP endpoint";
      case "http-sender" -> "HTTP request";
      case "chain-call-2" -> "chain call";
      case "mcp-trigger" -> "MCP endpoint";
      default -> key.replaceAll("-\\d+$", "").replace('-', ' ');
    };
  }

  public static RequirementDraft toDraft(
      DraftInput input,
      RequirementDraft previous,
      List<CatalogBindingHint> bindings,
      String sourceVersion,
      String sourceHash) {
    return toDraft(input, previous, bindings, sourceVersion, sourceHash, Map.of());
  }

  public static RequirementDraft toDraft(
      DraftInput input,
      RequirementDraft previous,
      List<CatalogBindingHint> bindings,
      String sourceVersion,
      String sourceHash,
      Map<String, String> verifiedTargets) {
    RequirementFlow flow = flow(input);
    Map<String, String> targets = new HashMap<>(retainedTargets(previous, input));
    targets.putAll(verifiedTargets);
    List<RequirementFact> facts = facts(input, targets);
    boolean ready = pending(input, bindings, targets).isEmpty()
        && RequirementFlowValidator.validateBindings(flow, facts, bindings).isEmpty();
    String candidateInteractionId = previous == null
        ? null : previous.apiHubCandidateInteractionId();
    boolean candidateBound = candidateInteractionId != null && bindings.stream()
        .anyMatch(binding -> candidateInteractionId.equals(binding.interactionId()));
    return new RequirementDraft(
        ready,
        render(input, bindings),
        ready ? DraftDecision.READY_FOR_PLAN : DraftDecision.NEEDS_INPUT,
        input.openQuestions().stream().map(QuestionInput::text).toList(),
        RequirementDraftTool.SOURCE_SKILL_ID, sourceVersion, sourceHash,
        previous == null || candidateBound ? null : previous.apiHubCandidate(),
        false,
        facts,
        previous != null && previous.importIntent() && !candidateBound,
        candidateBound ? null : candidateInteractionId,
        input.settings().idsRequested(),
        flow,
        bindings,
        input.settings().preferredSystemType() == null
            ? null : input.settings().preferredSystemType().name(),
        input);
  }

  public static Map<String, String> resolvedTargets(RequirementDraft draft) {
    if (draft == null) {
      return Map.of();
    }
    Map<String, String> targets = new HashMap<>();
    for (RequirementFact fact : draft.facts()) {
      if (fact.kind() == RequirementFactKind.CAPABILITY
          && ("chain-call-2".equals(fact.capabilityKey())
              || "mcp-trigger".equals(fact.capabilityKey()))
          && !blank(fact.path())) {
        targets.put(fact.sourceFactId(), fact.path());
      }
    }
    return Map.copyOf(targets);
  }

  private static Map<String, String> retainedTargets(
      RequirementDraft previous, DraftInput input) {
    if (previous == null || previous.authoredDraft() == null) {
      return Map.of();
    }
    Map<String, String> resolved = resolvedTargets(previous);
    Map<String, String> retained = new HashMap<>();
    for (CapabilityInput capability : input.capabilities()) {
      CapabilityInput old = previous.authoredDraft().capabilities().stream()
          .filter(entry -> entry.interactionId().equals(capability.interactionId()))
          .findFirst().orElse(null);
      InteractionInput currentOwner = input.flow().interactions().stream()
          .filter(entry -> entry.interactionId().equals(capability.interactionId()))
          .findFirst().orElse(null);
      InteractionInput oldOwner = previous.authoredDraft().flow().interactions().stream()
          .filter(entry -> entry.interactionId().equals(capability.interactionId()))
          .findFirst().orElse(null);
      if (capability.equals(old) && currentOwner != null && oldOwner != null
          && currentOwner.direction() == oldOwner.direction()
          && java.util.Objects.equals(currentOwner.participant(), oldOwner.participant())
          && java.util.Objects.equals(currentOwner.operation(), oldOwner.operation())
          && resolved.containsKey(capability.interactionId())) {
        retained.put(capability.interactionId(), resolved.get(capability.interactionId()));
      }
    }
    return retained;
  }

  private static boolean allOutboundReachable(DraftInput input) {
    Map<String, List<String>> successors = new HashMap<>();
    for (TransitionInput edge : input.flow().transitions()) {
      successors.computeIfAbsent(edge.sourceInteractionId(), ignored -> new ArrayList<>())
          .add(edge.targetInteractionId());
    }
    Set<String> visited = new HashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    for (InteractionInput interaction : input.flow().interactions()) {
      if (interaction.direction() == Direction.INBOUND && visited.add(interaction.interactionId())) {
        queue.add(interaction.interactionId());
      }
    }
    while (!queue.isEmpty()) {
      for (String next : successors.getOrDefault(queue.remove(), List.of())) {
        if (visited.add(next)) {
          queue.add(next);
        }
      }
    }
    return input.flow().interactions().stream()
        .noneMatch(interaction -> interaction.direction() == Direction.OUTBOUND
            && !visited.contains(interaction.interactionId()));
  }

  private static String display(String value) {
    return blank(value) ? "[to be defined]" : value;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
