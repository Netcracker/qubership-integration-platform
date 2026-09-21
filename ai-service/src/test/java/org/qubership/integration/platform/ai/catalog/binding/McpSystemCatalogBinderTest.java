package org.qubership.integration.platform.ai.catalog.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogMcpSystemDto;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
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
      String interactionId, String participant, String path, String identifier) {
    return new RequirementFact(
        interactionId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.CAPABILITY,
        "mcp-trigger",
        "Expose the chain as an MCP tool",
        participant,
        identifier,
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
}
