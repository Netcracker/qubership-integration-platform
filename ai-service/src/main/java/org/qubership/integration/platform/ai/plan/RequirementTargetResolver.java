package org.qubership.integration.platform.ai.plan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.catalog.binding.CompositionCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.McpSystemCatalogBinder;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogMcpSystemDto;
import org.qubership.integration.platform.ai.plan.RequirementCaptureEditor.Issue;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;

/** Verifies composition targets against the existing catalog binders. */
@ApplicationScoped
public class RequirementTargetResolver {

  public record Resolution(Map<String, String> targetIds, List<Issue> issues) {}

  private final CompositionCatalogBinder chains;
  private final McpSystemCatalogBinder mcpSystems;

  @Inject
  public RequirementTargetResolver(
      CompositionCatalogBinder chains, McpSystemCatalogBinder mcpSystems) {
    this.chains = chains;
    this.mcpSystems = mcpSystems;
  }

  public Resolution resolve(DraftInput draft) {
    Map<String, InteractionInput> interactions = new HashMap<>();
    for (InteractionInput interaction : draft.flow().interactions()) {
      interactions.put(interaction.interactionId(), interaction);
    }
    Map<String, String> resolved = new HashMap<>();
    List<Issue> issues = new ArrayList<>();
    List<CatalogElementResponseDto> triggers = null;
    List<CatalogMcpSystemDto> systems = null;
    for (int i = 0; i < draft.capabilities().size(); i++) {
      CapabilityInput capability = draft.capabilities().get(i);
      InteractionInput owner = interactions.get(capability.interactionId());
      if (owner == null) {
        continue;
      }
      String path = "/draft/capabilities/" + i + "/targetReference";
      if ("chain-call-2".equals(capability.capabilityKey())) {
        if (triggers == null) {
          triggers = chains.listChainTriggers();
        }
        List<CatalogElementResponseDto> matches = triggers.stream()
            .filter(trigger -> trigger != null && trigger.id != null)
            .filter(trigger -> selected(capability.targetReference(), trigger.id)
                || capability.targetReference() == null
                    && same(owner.participant(), trigger.chainName))
            .toList();
        List<String> verified = triggers.stream()
            .filter(trigger -> trigger != null && trigger.id != null
                && same(owner.participant(), trigger.chainName))
            .map(trigger -> trigger.id).toList();
        boolean named = owner.participant() != null && !owner.participant().isBlank();
        if (matches.size() == 1 && (owner.participant() == null
            || same(owner.participant(), matches.getFirst().chainName))) {
          resolved.put(capability.interactionId(), matches.getFirst().id);
        } else if (verified.isEmpty() && (named || capability.targetReference() != null)) {
          issues.add(new Issue("INVALID_TARGET_SELECTION", path, capability.interactionId(),
              named
                  ? "The catalog has no chain trigger for \"" + owner.participant()
                      + "\". Ask which catalog chain to call. Do not invent a trigger id."
                  : "The catalog chain is not named. Ask which catalog chain to call. Do not invent a trigger id."));
        } else if (capability.targetReference() != null) {
          issues.add(new Issue("INVALID_TARGET_SELECTION", path, capability.interactionId(),
              "Set targetReference to the chain-trigger element ID only, without a chain ID or separator."
                  + " Verified trigger IDs for this target chain: " + verified + "."));
        }
      } else if ("mcp-trigger".equals(capability.capabilityKey())) {
        if (systems == null) {
          systems = mcpSystems.listMcpSystems();
        }
        List<CatalogMcpSystemDto> matches = systems.stream()
            .filter(system -> system != null && system.id != null)
            .filter(system -> selected(capability.targetReference(), system.id)
                || capability.targetReference() == null
                    && (same(owner.participant(), system.name)
                        || same(capability.mcpServerId(), system.identifier)))
            .toList();
        if (matches.size() == 1 && (owner.participant() == null
            || same(owner.participant(), matches.getFirst().name))) {
          resolved.put(capability.interactionId(), matches.getFirst().id);
        } else if (capability.targetReference() != null) {
          issues.add(new Issue("INVALID_TARGET_SELECTION", path, capability.interactionId(),
              "Select an MCP system verified for this service."));
        }
      }
    }
    return new Resolution(Map.copyOf(resolved), List.copyOf(issues));
  }

  /**
   * Adapts one chain-call answer into the verified chain-trigger element id.
   *
   * <p>Candidates come only from {@link CompositionCatalogBinder#listChainTriggers()}. The answer
   * must be that element id, or the single chain or trigger name. A chain id, an invented id, a
   * service-call operation id, and the question text itself stay unresolved.
   */
  public Resolution resolveChainCallAnswer(DraftInput draft, QuestionInput activeQuestion, String answerText) {
    if (draft == null || activeQuestion == null || answerText == null || answerText.isBlank()
        || draft.capabilities() == null) {
      return new Resolution(Map.of(), List.of());
    }
    String interactionId = activeQuestion.evidence() != null
        && activeQuestion.evidence().interactionId() != null
        && !activeQuestion.evidence().interactionId().isBlank()
        ? activeQuestion.evidence().interactionId()
        : activeQuestion.interactionIds() == null ? null : activeQuestion.interactionIds().stream()
            .filter(id -> id != null && !id.isBlank()).findFirst().orElse(null);
    if (interactionId == null || draft.capabilities().stream().noneMatch(capability -> capability != null
        && interactionId.equals(capability.interactionId()) && "chain-call-2".equals(capability.capabilityKey()))) {
      return new Resolution(Map.of(), List.of());
    }
    String answer = answerText.trim();
    List<CatalogElementResponseDto> listed = chains.listChainTriggers();
    List<CatalogElementResponseDto> candidates = (listed == null ? List.<CatalogElementResponseDto>of() : listed)
        .stream().filter(RequirementTargetResolver::chainTrigger).toList();
    if (candidates.stream().anyMatch(trigger -> answer.equals(trigger.chainId))) {
      return new Resolution(Map.of(), List.of());
    }
    List<CatalogElementResponseDto> byId = candidates.stream()
        .filter(trigger -> answer.equals(trigger.id)).toList();
    if (byId.size() == 1) {
      return new Resolution(Map.of(interactionId, byId.getFirst().id), List.of());
    }
    List<CatalogElementResponseDto> byName = candidates.stream()
        .filter(trigger -> same(answer, trigger.chainName) || same(answer, trigger.name)).toList();
    if (byName.size() == 1) {
      return new Resolution(Map.of(interactionId, byName.getFirst().id), List.of());
    }
    return new Resolution(Map.of(), List.of());
  }

  private static boolean chainTrigger(CatalogElementResponseDto trigger) {
    return trigger != null && trigger.id != null && !trigger.id.isBlank()
        && (trigger.type == null || trigger.type.isBlank() || "chain-trigger-2".equals(trigger.type));
  }

  private static boolean selected(String requested, String actual) {
    return requested != null && requested.equals(actual);
  }

  private static boolean same(String requested, String actual) {
    return requested != null && actual != null
        && !requested.isBlank() && requested.trim().equalsIgnoreCase(actual.trim());
  }
}
