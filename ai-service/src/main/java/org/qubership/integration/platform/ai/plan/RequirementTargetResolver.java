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
        if (matches.size() == 1 && (owner.participant() == null
            || same(owner.participant(), matches.getFirst().chainName))) {
          resolved.put(capability.interactionId(), matches.getFirst().id);
        } else if (capability.targetReference() != null) {
          issues.add(new Issue("INVALID_TARGET_SELECTION", path, capability.interactionId(),
              "Select a chain trigger verified for this target chain."));
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

  private static boolean selected(String requested, String actual) {
    return requested != null && requested.equals(actual);
  }

  private static boolean same(String requested, String actual) {
    return requested != null && actual != null
        && !requested.isBlank() && requested.trim().equalsIgnoreCase(actual.trim());
  }
}
