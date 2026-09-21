package org.qubership.integration.platform.ai.catalog.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateMcpSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogMcpSystemDto;
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

class McpSystemCatalogBinderTest {

  @Test
  void identifierFromNameSlugsDisplayName() {
    assertEquals("orders-mcp", McpSystemCatalogBinder.identifierFromName("Orders MCP"));
    assertEquals("mcp-service", McpSystemCatalogBinder.identifierFromName("   "));
  }

  @Test
  void gatherWritesPathWhenNameMatchesOneSystem() {
    CatalogMcpSystemDto system = system("sys-1", "Orders MCP", "orders-mcp");
    RequirementFact fact = mcpFact("in-1", "Orders MCP", "");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(
            flow("in-1"), List.of(fact), "expose as MCP tool on Orders MCP", List.of(system));
    assertTrue(result.openQuestion().isEmpty());
    assertEquals("sys-1", mcpFactIn(result.facts()).path());
  }

  @Test
  void gatherAsksPickerWhenSeveralMatchAndAllowsCreateNew() {
    List<CatalogMcpSystemDto> catalog =
        List.of(system("a", "Orders", "orders"), system("b", "Orders", "orders-2"));
    RequirementFact fact = mcpFact("in-1", "Orders", "");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(flow("in-1"), List.of(fact), "Orders MCP", catalog);
    assertTrue(result.openQuestion().orElseThrow().contains("Choose the MCP service"));
    assertTrue(result.openQuestion().orElseThrow().contains("new MCP service"));
    assertEquals("", mcpFactIn(result.facts()).path());
  }

  @Test
  void gatherLeavesPathBlankWhenNameIsUnknownSoBindCanCreate() {
    RequirementFact fact = mcpFact("in-1", "Brand New", "");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(
            flow("in-1"), List.of(fact), "Brand New", List.of());
    assertTrue(result.openQuestion().isEmpty());
    assertEquals("", mcpFactIn(result.facts()).path());
    assertEquals("Brand New", mcpFactIn(result.facts()).participant());
  }

  @Test
  void gatherAsksForNameWhenMcpTriggerHasNoParticipant() {
    RequirementFact fact = mcpFact("in-1", "", "");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(flow("in-1"), List.of(fact), "expose as MCP", List.of());
    assertTrue(result.openQuestion().orElseThrow().contains("MCP service name"));
  }

  @Test
  void gatherWritesPathWhenOperationMatchesOneSystem() {
    CatalogMcpSystemDto system = system("sys-id", "Orders MCP", "orders-mcp");
    RequirementFact fact = mcpFact("in-1", "", "", "orders-mcp");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(
            flow("in-1"), List.of(fact), "expose as MCP tool", List.of(system));
    assertTrue(result.openQuestion().isEmpty());
    assertEquals("sys-id", mcpFactIn(result.facts()).path());
  }

  @Test
  void gatherAsksPickerWhenOperationMatchesSeveralSystems() {
    List<CatalogMcpSystemDto> catalog =
        List.of(system("a", "Orders", "orders"), system("b", "Orders Alt", "orders"));
    RequirementFact fact = mcpFact("in-1", "", "", "orders");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(flow("in-1"), List.of(fact), "Orders MCP", catalog);
    assertTrue(result.openQuestion().orElseThrow().contains("Choose the MCP service"));
    assertTrue(result.openQuestion().orElseThrow().contains("new MCP service"));
    assertEquals("", mcpFactIn(result.facts()).path());
  }

  @Test
  void gatherAsksForNameWhenCreateNewPhraseButParticipantBlank() {
    RequirementFact fact = mcpFact("in-1", "", "");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(
            flow("in-1"), List.of(fact), "create a new MCP service", List.of());
    assertTrue(result.openQuestion().orElseThrow().contains("MCP service name"));
  }

  @Test
  void bindWritesMcpServiceIdsFromFactPath() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems()).thenReturn(List.of(system("sys-1", "Orders MCP", "orders-mcp")));
    McpSystemCatalogBinder binder = new McpSystemCatalogBinder(catalog);
    ChainPlanGraph graph = graphWithTrigger("in-1");
    ChainPlanGraph out = binder.bind(graph, briefWith(mcpFact("in-1", "Orders MCP", "sys-1")));
    assertEquals("[\"sys-1\"]", property(node(out, "in-1"), "mcpServiceIds"));
    verify(catalog, never()).createMcpSystem(any());
  }

  @Test
  void bindCreatesSystemWhenPathBlank() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems()).thenReturn(List.of());
    CatalogMcpSystemDto created = system("sys-new", "Brand New", "brand-new");
    when(catalog.createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "brand-new", null)))
        .thenReturn(created);
    McpSystemCatalogBinder binder = new McpSystemCatalogBinder(catalog);
    ChainPlanGraph out = binder.bind(graphWithTrigger("in-1"), briefWith(mcpFact("in-1", "Brand New", "")));
    assertEquals("[\"sys-new\"]", property(node(out, "in-1"), "mcpServiceIds"));
  }

  @Test
  void bindCreatesSystemWithUserIdentifierUnchanged() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems()).thenReturn(List.of());
    CatalogMcpSystemDto created = system("sys-new", "Brand New", "Orders_SVC");
    when(catalog.createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "Orders_SVC", null)))
        .thenReturn(created);
    McpSystemCatalogBinder binder = new McpSystemCatalogBinder(catalog);
    ChainPlanGraph out =
        binder.bind(
            graphWithTrigger("in-1"),
            briefWith(mcpFact("in-1", "Brand New", "", "Orders_SVC")));
    assertEquals("[\"sys-new\"]", property(node(out, "in-1"), "mcpServiceIds"));
    verify(catalog).createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "Orders_SVC", null));
  }

  @Test
  void bindDoesNotSuffixUserIdentifierWhenItAlreadyExists() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems()).thenReturn(List.of(system("sys-1", "Orders MCP", "Orders_SVC")));
    McpSystemCatalogBinder binder = new McpSystemCatalogBinder(catalog);
    ChainPlanGraph out =
        binder.bind(
            graphWithTrigger("in-1"),
            briefWith(mcpFact("in-1", "Brand New", "", "Orders_SVC")));
    assertEquals("[\"sys-1\"]", property(node(out, "in-1"), "mcpServiceIds"));
    verify(catalog, never()).createMcpSystem(any());
  }

  @Test
  void bindSuffixesIdentifierForSecondCreateInSameGraph() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems()).thenReturn(List.of());
    CatalogMcpSystemDto first = system("sys-1", "Brand New", "brand-new");
    CatalogMcpSystemDto second = system("sys-2", "Brand New", "brand-new-2");
    when(catalog.createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "brand-new", null)))
        .thenReturn(first);
    when(catalog.createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "brand-new-2", null)))
        .thenReturn(second);
    McpSystemCatalogBinder binder = new McpSystemCatalogBinder(catalog);
    ChainPlanGraph graph = graphWithTriggers("in-1", "in-2");
    RequirementBrief brief =
        briefWith(
            mcpFact("in-1", "Brand New", ""),
            mcpFact("in-2", "Brand New", ""));
    ChainPlanGraph out = binder.bind(graph, brief);
    assertEquals("[\"sys-1\"]", property(node(out, "in-1"), "mcpServiceIds"));
    assertEquals("[\"sys-2\"]", property(node(out, "in-2"), "mcpServiceIds"));
    verify(catalog).createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "brand-new", null));
    verify(catalog)
        .createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "brand-new-2", null));
  }

  @Test
  void bindSuffixesIdentifierWhenSlugAlreadyExists() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems()).thenReturn(List.of(system("other", "Other", "brand-new")));
    CatalogMcpSystemDto created = system("sys-new", "Brand New", "brand-new-2");
    when(catalog.createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "brand-new-2", null)))
        .thenReturn(created);
    McpSystemCatalogBinder binder = new McpSystemCatalogBinder(catalog);
    binder.bind(graphWithTrigger("in-1"), briefWith(mcpFact("in-1", "Brand New", "")));
    verify(catalog).createMcpSystem(new CatalogCreateMcpSystemRequest("Brand New", "brand-new-2", null));
  }

  @Test
  void gatherSkipsAutoBindWhenCreateNewWithParticipantName() {
    List<CatalogMcpSystemDto> catalog =
        List.of(system("a", "Orders", "orders"), system("b", "Orders", "orders-2"));
    RequirementFact fact = mcpFact("in-1", "Fresh MCP", "");
    McpSystemCatalogBinder.McpSystemGatherResult result =
        McpSystemCatalogBinder.gather(
            flow("in-1"), List.of(fact), "create a new MCP service named Fresh MCP", catalog);
    assertTrue(result.openQuestion().isEmpty());
    assertEquals("", mcpFactIn(result.facts()).path());
    assertEquals("Fresh MCP", mcpFactIn(result.facts()).participant());
  }

  private static CatalogMcpSystemDto system(String id, String name, String identifier) {
    CatalogMcpSystemDto dto = new CatalogMcpSystemDto();
    dto.id = id;
    dto.name = name;
    dto.identifier = identifier;
    return dto;
  }

  private static RequirementFact mcpFact(String interactionId, String participant, String path) {
    return mcpFact(interactionId, participant, path, "");
  }

  private static RequirementFact mcpFact(
      String interactionId, String participant, String path, String operation) {
    return new RequirementFact(
        interactionId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY,
        "mcp-trigger",
        "Expose the chain as an MCP tool",
        participant,
        operation,
        "",
        "",
        path,
        "");
  }

  private static RequirementFlow flow(String inboundId) {
    return new RequirementFlow(
        List.of(new Interaction(inboundId, Direction.INBOUND, "Agent", "tool", "")), List.of());
  }

  private static RequirementFact mcpFactIn(List<RequirementFact> facts) {
    return facts.stream().filter(f -> "mcp-trigger".equals(f.capabilityKey())).findFirst().orElseThrow();
  }

  private static ChainPlanGraph graphWithTrigger(String nodeId) {
    return graphWithTriggers(nodeId);
  }

  private static ChainPlanGraph graphWithTriggers(String... nodeIds) {
    List<ChainPlanNode> nodes =
        java.util.Arrays.stream(nodeIds)
            .map(
                nodeId ->
                    new ChainPlanNode(nodeId, "mcp-trigger", "MCP", null, null, List.of()))
            .toList();
    return new ChainPlanGraph("1.0", new ChainSection("n", "n"), nodes, List.of());
  }

  private static RequirementBrief briefWith(RequirementFact... facts) {
    return brief().withFacts(List.of(facts));
  }

  private static RequirementBrief brief() {
    return new RequirementBrief("MCP chain", List.of(), List.of(), List.of(), List.of(), "summary");
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
    for (PlanProperty planProperty : node.properties()) {
      if (key.equals(planProperty.key())) {
        return planProperty.value();
      }
    }
    return null;
  }
}
