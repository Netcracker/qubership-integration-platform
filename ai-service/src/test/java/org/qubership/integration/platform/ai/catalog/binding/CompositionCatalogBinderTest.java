package org.qubership.integration.platform.ai.catalog.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class CompositionCatalogBinderTest {

  @Test
  void bindIsIdentityWhenGraphHasNoCompositionNodes() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "trigger-http", "http-trigger", "HTTP", null, null, List.of())),
            List.of());

    ChainPlanGraph out = binder.bind(graph, brief());

    assertEquals(graph.nodes(), out.nodes());
    verify(catalog, never()).getElementsByType("any-chain", "chain-trigger-2");
  }

  @Test
  void bindWritesChainCallElementIdFromFactPath() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(
            List.of(
                trigger("trig-other", "Other chain"),
                trigger("trig-header", "Chain trigger + Header modification")));
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "trigger-http", "http-trigger", "HTTP", null, null, List.of()),
                new ChainPlanNode(
                    "call-other", "chain-call-2", "Call header modification", null, null, List.of())),
            List.of());
    RequirementBrief brief =
        brief()
            .withFacts(
                List.of(
                    chainCallFact(
                        "call-other",
                        "Chain trigger + Header modification",
                        "trig-header")));

    ChainPlanGraph out = binder.bind(graph, brief);

    assertEquals("trig-header", property(node(out, "call-other"), "elementId"));
  }

  @Test
  void bindDoesNotResolveChainCallByChainName() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(List.of(trigger("trig-header", "Chain trigger + Header modification")));
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "call-other", "chain-call-2", "Call", null, null, List.of())),
            List.of());
    RequirementBrief brief =
        brief()
            .withFacts(
                List.of(
                    chainCallFact(
                        "call-other", "Chain trigger + Header modification", "")));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> binder.bind(graph, brief));
    assertTrue(error.getMessage().contains("call-other"), error.getMessage());
    assertTrue(error.getMessage().contains("path"), error.getMessage());
  }

  @Test
  void bindFailsWhenChainCallPathIsNotACatalogTrigger() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(List.of(trigger("trig-other", "Other chain")));
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "call-other", "chain-call-2", "Call", null, null, List.of())),
            List.of());
    RequirementBrief brief =
        brief()
            .withFacts(
                List.of(chainCallFact("call-other", "Other chain", "missing-id")));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> binder.bind(graph, brief));
    assertTrue(error.getMessage().contains("missing-id"), error.getMessage());
    assertTrue(error.getMessage().contains("call-other"), error.getMessage());
  }

  @Test
  void bindWritesChainCallElementIdWhenSeveralTriggersShareTheChainName() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(
            List.of(
                trigger("trig-a", "Shared chain"),
                trigger("trig-b", "Shared chain")));
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "call-other", "chain-call-2", "Call", null, null, List.of())),
            List.of());
    RequirementBrief brief =
        brief()
            .withFacts(List.of(chainCallFact("call-other", "Shared chain", "trig-b")));

    ChainPlanGraph out = binder.bind(graph, brief);

    assertEquals("trig-b", property(node(out, "call-other"), "elementId"));
  }

  @Test
  void gatherWritesUniqueTriggerIdOntoExplicitChainCallPath() {
    CompositionCatalogBinder.ChainCallGatherResult result =
        CompositionCatalogBinder.gatherChainCalls(
            chainCallFlow(),
            List.of(
                httpTriggerFact(),
                chainCallFact("call-other", "Chain trigger + Header modification", "")),
            "Call Chain trigger + Header modification",
            List.of(
                trigger("trig-other", "Other chain"),
                trigger("trig-header", "Chain trigger + Header modification")));

    assertTrue(result.openQuestion().isEmpty(), result.openQuestion().toString());
    assertEquals("trig-header", chainCallPath(result.facts()));
  }

  @Test
  void gatherAsksPickerWhenExplicitChainCallNameIsMissing() {
    CompositionCatalogBinder.ChainCallGatherResult result =
        CompositionCatalogBinder.gatherChainCalls(
            chainCallFlow(),
            List.of(httpTriggerFact(), chainCallFact("call-other", "", "")),
            "Call another chain",
            List.of(
                trigger("trig-other", "Other chain"),
                trigger("trig-header", "Chain trigger + Header modification")));

    assertTrue(result.openQuestion().isPresent());
    String question = result.openQuestion().get();
    assertTrue(question.contains("call-other"), question);
    assertTrue(
        question.contains(CompositionCatalogBinder.CHAIN_CALL_PICKER_PROMPT), question);
    assertFalse(question.contains("trig-other"), question);
    assertFalse(question.contains("id="), question);
    assertTrue(result.catalogListing().contains("trig-other"), result.catalogListing());
    assertTrue(result.catalogListing().contains("trig-header"), result.catalogListing());
    assertEquals("", chainCallPath(result.facts()));
  }

  @Test
  void gatherAsksPickerWhenParticipantDisplayTitleDiffersFromCatalogChainName() {
    CompositionCatalogBinder.ChainCallGatherResult result =
        CompositionCatalogBinder.gatherChainCalls(
            chainCallFlow(),
            List.of(
                httpTriggerFact(),
                chainCallFact("call-other", "Chain trigger + Header modification", "")),
            "Call Chain trigger + Header modification",
            List.of(trigger("trig-header", "chain-trigger-header-modification")));

    assertTrue(result.openQuestion().isPresent(), result.openQuestion().toString());
    String question = result.openQuestion().get();
    assertTrue(
        question.contains(CompositionCatalogBinder.CHAIN_CALL_PICKER_PROMPT), question);
    assertFalse(question.contains("trig-header"), question);
    assertTrue(result.catalogListing().contains("trig-header"), result.catalogListing());
    assertTrue(
        result.catalogListing().contains("chain-trigger-header-modification"),
        result.catalogListing());
    assertEquals("", chainCallPath(result.facts()));
  }

  @Test
  void gatherAsksPickerWhenExplicitChainCallNameIsAmbiguous() {
    CompositionCatalogBinder.ChainCallGatherResult result =
        CompositionCatalogBinder.gatherChainCalls(
            chainCallFlow(),
            List.of(httpTriggerFact(), chainCallFact("call-other", "Shared chain", "")),
            "Call Shared chain",
            List.of(trigger("trig-a", "Shared chain"), trigger("trig-b", "Shared chain")));

    assertTrue(result.openQuestion().isPresent());
    String question = result.openQuestion().get();
    assertTrue(
        question.contains(CompositionCatalogBinder.CHAIN_CALL_PICKER_PROMPT), question);
    assertFalse(question.contains("trig-a"), question);
    assertTrue(result.catalogListing().contains("trig-a"), result.catalogListing());
    assertTrue(result.catalogListing().contains("trig-b"), result.catalogListing());
  }

  @Test
  void gatherAsksToCreateChainTriggerWhenCatalogIsEmpty() {
    CompositionCatalogBinder.ChainCallGatherResult result =
        CompositionCatalogBinder.gatherChainCalls(
            chainCallFlow(),
            List.of(
                httpTriggerFact(),
                chainCallFact("call-other", "Chain trigger + Header modification", "")),
            "Call Chain trigger + Header modification",
            List.of());

    assertTrue(result.openQuestion().isPresent());
    String question = result.openQuestion().get();
    assertTrue(question.toLowerCase().contains("chain-trigger"), question);
    assertTrue(question.toLowerCase().contains("catalog"), question);
  }

  @Test
  void gatherGuessFromCatalogNameAlwaysAsksPicker() {
    CompositionCatalogBinder.ChainCallGatherResult result =
        CompositionCatalogBinder.gatherChainCalls(
            chainCallFlow(),
            List.of(httpTriggerFact()),
            "Invoke Chain trigger + Header modification via chain call",
            List.of(trigger("trig-header", "Chain trigger + Header modification")));

    assertTrue(result.openQuestion().isPresent());
    String question = result.openQuestion().get();
    assertTrue(question.contains("Chain trigger + Header modification"), question);
    assertTrue(
        question.contains(CompositionCatalogBinder.CHAIN_CALL_PICKER_PROMPT), question);
    assertFalse(question.contains("trig-header"), question);
    assertTrue(result.catalogListing().contains("trig-header"), result.catalogListing());
    assertTrue(
        result.facts().stream().noneMatch(fact -> "chain-call-2".equals(fact.capabilityKey())),
        result.facts().toString());
  }

  @Test
  void gatherDoesNotGuessWhenCatalogChainNameIsAbsent() {
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction(
                    "http-entry", Direction.INBOUND, "Caller", "GET /auto-tests/chain-call", ""),
                new Interaction(
                    "create-order", Direction.OUTBOUND, "Salesforce", "createTask", "")),
            List.of(new Transition("http-entry", "create-order")));
    CompositionCatalogBinder.ChainCallGatherResult result =
        CompositionCatalogBinder.gatherChainCalls(
            flow,
            List.of(httpTriggerFact()),
            "Create an order in Salesforce",
            List.of(trigger("trig-header", "Chain trigger + Header modification")));

    assertTrue(result.openQuestion().isEmpty(), result.openQuestion().toString());
  }

  @Test
  void bindWritesReuseElementIdToTheSoleReuseNode() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "trigger-http", "http-trigger", "HTTP", null, null, List.of()),
                new ChainPlanNode(
                    "reuse-ref", "reuse-reference", "Reuse Reference", null, null, List.of()),
                new ChainPlanNode(
                    "reuse-container", "reuse", "Reuse", null, null, List.of()),
                new ChainPlanNode(
                    "reuse-body", "script", "Set test property", "reuse-container", null, List.of())),
            List.of());

    ChainPlanGraph out = binder.bind(graph, brief());

    assertEquals("reuse-container", property(node(out, "reuse-ref"), "reuseElementId"));
    verify(catalog, never()).getElementsByType("any-chain", "chain-trigger-2");
  }

  @Test
  void bindFailsWhenReuseReferenceHasNoReuseContainer() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "reuse-ref", "reuse-reference", "Reuse Reference", null, null, List.of())),
            List.of());

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> binder.bind(graph, brief()));
    assertTrue(error.getMessage().contains("reuse-ref"));
  }

  @Test
  void bindLeavesUnrelatedNodesUntouched() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    CompositionCatalogBinder binder = new CompositionCatalogBinder(catalog);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "trigger-http",
                    "http-trigger",
                    "HTTP",
                    null,
                    null,
                    List.of(new PlanProperty("contextPath", "/auto-tests/reuse"))),
                new ChainPlanNode(
                    "reuse-ref", "reuse-reference", "Reuse Reference", null, null, List.of()),
                new ChainPlanNode(
                    "reuse-container", "reuse", "Reuse", null, null, List.of())),
            List.of());

    ChainPlanGraph out = binder.bind(graph, brief());

    assertEquals(
        "/auto-tests/reuse", property(node(out, "trigger-http"), "contextPath"));
    assertNull(property(node(out, "reuse-container"), "reuseElementId"));
  }

  private static RequirementFact chainCallFact(String sourceFactId, String participant, String path) {
    return new RequirementFact(
        sourceFactId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY,
        "chain-call-2",
        "Call the header modification chain",
        participant,
        "",
        "",
        "",
        path);
  }

  private static RequirementFact httpTriggerFact() {
    return new RequirementFact(
        "http-entry",
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY,
        "http-trigger",
        "Expose GET /auto-tests/chain-call",
        "",
        "",
        "",
        "GET",
        "/auto-tests/chain-call");
  }

  private static RequirementFlow chainCallFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("http-entry", Direction.INBOUND, "Caller", "GET /auto-tests/chain-call", ""),
            new Interaction(
                "call-other",
                Direction.OUTBOUND,
                "Chain trigger + Header modification",
                "chain-trigger",
                "")),
        List.of(new Transition("http-entry", "call-other")));
  }

  private static String chainCallPath(List<RequirementFact> facts) {
    return facts.stream()
        .filter(fact -> "chain-call-2".equals(fact.capabilityKey()))
        .map(RequirementFact::path)
        .findFirst()
        .orElse("");
  }

  private static RequirementBrief brief() {
    return new RequirementBrief("Chain call", List.of(), List.of(), List.of(), List.of(), "summary");
  }

  private static CatalogElementResponseDto trigger(String id, String chainName) {
    CatalogElementResponseDto dto = new CatalogElementResponseDto();
    dto.id = id;
    dto.type = "chain-trigger-2";
    dto.chainName = chainName;
    dto.chainId = "chain-" + id;
    dto.name = "Trigger";
    return dto;
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(candidate -> candidate.nodeId().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }
}
