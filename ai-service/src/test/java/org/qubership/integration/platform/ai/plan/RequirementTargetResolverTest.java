package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.CompositionCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.McpSystemCatalogBinder;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementTargetResolverTest {

  @Test
  void chainTargetMustMatchAnExistingTriggerForTheNamedChain() {
    CompositionCatalogBinder chains = mock(CompositionCatalogBinder.class);
    CatalogElementResponseDto trigger = new CatalogElementResponseDto();
    trigger.id = "verified-trigger";
    trigger.chainName = "Target Chain";
    when(chains.listChainTriggers()).thenReturn(List.of(trigger));
    RequirementTargetResolver resolver = new RequirementTargetResolver(
        chains, mock(McpSystemCatalogBinder.class));

    assertEquals("verified-trigger", resolver.resolve(draft(null)).targetIds().get("call"));
    assertEquals("verified-trigger", resolver.resolve(draft("verified-trigger"))
        .targetIds().get("call"));
    assertTrue(resolver.resolve(draft("invented-trigger")).issues().stream()
        .anyMatch(issue -> "INVALID_TARGET_SELECTION".equals(issue.code())));
  }

  private static DraftInput draft(String reference) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("call", Direction.OUTBOUND, "Target Chain", "Invoke",
            null, null, null)), List.of()), List.of(), List.of(
        new CapabilityInput("call", "chain-call-2", null, null, null,
            null, null, reference)), List.of(), new DraftSettings(null, null));
  }
}
