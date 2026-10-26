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
  void compositeReferenceReturnsVerifiedTriggerIdsWithoutAcceptingIt() {
    var chains = mock(CompositionCatalogBinder.class);
    var first = new CatalogElementResponseDto();
    first.id = "old-trigger";
    first.chainName = "Target Chain";
    var second = new CatalogElementResponseDto();
    second.id = "new-trigger";
    second.chainName = "Target Chain";
    var unrelated = new CatalogElementResponseDto();
    unrelated.id = "unrelated-trigger";
    unrelated.chainName = "Another Chain";
    when(chains.listChainTriggers()).thenReturn(List.of(first, second, unrelated));
    var resolver = new RequirementTargetResolver(chains, mock(McpSystemCatalogBinder.class));

    var rejected = resolver.resolve(draft("chain-id/new-trigger"));
    assertTrue(rejected.targetIds().isEmpty());
    var issue = rejected.issues().getFirst();
    assertEquals("INVALID_TARGET_SELECTION", issue.code());
    assertTrue(issue.message().contains("targetReference"), issue.message());
    assertTrue(issue.message().contains("old-trigger") && issue.message().contains("new-trigger"), issue.message());
    assertTrue(!issue.message().contains("unrelated-trigger"), issue.message());
    assertEquals("new-trigger", resolver.resolve(draft("new-trigger")).targetIds().get("call"));
    assertTrue(resolver.resolve(draft("unrelated-trigger")).targetIds().isEmpty());
    assertTrue(resolver.resolve(draft(null)).targetIds().isEmpty());
  }

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

  @Test
  void aNamedChainWithNoTriggerAsksTheUserInsteadOfInventingAnId() {
    RequirementTargetResolver resolver = new RequirementTargetResolver(
        mock(CompositionCatalogBinder.class), mock(McpSystemCatalogBinder.class));

    var missing = resolver.resolve(draft(null));
    assertTrue(missing.targetIds().isEmpty());
    var issue = missing.issues().getFirst();
    assertEquals("INVALID_TARGET_SELECTION", issue.code());
    assertTrue(issue.message().contains("Target Chain"), issue.message());
    assertTrue(issue.message().contains("Ask which catalog chain to call"), issue.message());
    assertTrue(!issue.message().contains("Set targetReference"), issue.message());
    var invented = resolver.resolve(draft("invented-trigger")).issues().getFirst();
    assertEquals("INVALID_TARGET_SELECTION", invented.code());
    assertTrue(invented.message().contains("Do not invent a trigger id."), invented.message());
    assertTrue(!invented.message().contains("Set targetReference"), invented.message());
  }

  private static DraftInput draft(String reference) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("call", Direction.OUTBOUND, "Target Chain", "Invoke",
            null, null, null)), List.of()), List.of(), List.of(
        new CapabilityInput("call", "chain-call-2", null, null, null,
            null, null, reference)), List.of(), new DraftSettings(null, null));
  }
}
