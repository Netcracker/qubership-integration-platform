package org.qubership.integration.platform.ai.catalog.binding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateMcpSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogMcpSystemDto;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;

/**
 * Resolves mcp-trigger catalog identity before generators run.
 *
 * <p>Gather stores the catalog MCP system UUID on the mcp-trigger fact {@code path}. Create at bind
 * when {@code path} is blank.
 */
@ApplicationScoped
public class McpSystemCatalogBinder {

  static final String PICKER_PROMPT =
      "Choose the MCP service for this trigger, or say you want a new MCP service.";

  static final String ASK_NAME_PROMPT =
      "MCP trigger needs an MCP service name. Name the existing service, or say you want a new MCP"
          + " service.";

  private final CatalogRestClient catalogRestClient;

  @Inject
  public McpSystemCatalogBinder(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
  }

  public record McpSystemGatherResult(
      List<RequirementFact> facts, Optional<String> openQuestion, String catalogListing) {

    public McpSystemGatherResult(List<RequirementFact> facts, Optional<String> openQuestion) {
      this(facts, openQuestion, "");
    }

    public McpSystemGatherResult {
      facts = facts == null ? List.of() : List.copyOf(facts);
      catalogListing = catalogListing == null ? "" : catalogListing;
    }
  }

  public List<CatalogMcpSystemDto> listMcpSystems() {
    List<CatalogMcpSystemDto> systems = catalogRestClient.listMcpSystems();
    return systems == null ? List.of() : List.copyOf(systems);
  }

  public ChainPlanGraph bind(ChainPlanGraph graph, RequirementBrief brief) {
    Objects.requireNonNull(graph, "graph");
    List<ChainPlanNode> triggers = nodesOfType(graph, "mcp-trigger");
    if (triggers.isEmpty()) {
      return graph;
    }
    List<CatalogMcpSystemDto> systems = new ArrayList<>(listMcpSystems());
    ChainPlanGraph result = graph;
    for (ChainPlanNode trigger : triggers) {
      String systemId = resolveSystemId(trigger, brief, triggers.size(), systems);
      result = CompositionCatalogIdentity.upsertMcpServiceIds(result, trigger.nodeId(), systemId);
    }
    return result;
  }

  public static String identifierFromName(String name) {
    if (name == null || name.isBlank()) {
      return "mcp-service";
    }
    String slug =
        name.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    if (slug.isBlank()) {
      return "mcp-service";
    }
    return slug.length() <= 128 ? slug : slug.substring(0, 128);
  }

  public static McpSystemGatherResult gather(
      RequirementFlow flow,
      List<RequirementFact> facts,
      String assembledText,
      List<CatalogMcpSystemDto> systems) {
    List<RequirementFact> input = facts == null ? List.of() : facts;
    List<CatalogMcpSystemDto> catalog = usableSystems(systems);
    if (mcpTriggerFacts(input).isEmpty()) {
      return new McpSystemGatherResult(input, Optional.empty());
    }
    String text = assembledText == null ? "" : assembledText;
    List<RequirementFact> rewritten = new ArrayList<>(input.size());
    for (RequirementFact fact : input) {
      if (!isMcpTriggerFact(fact)) {
        rewritten.add(fact);
        continue;
      }
      if (systemIdExists(fact.path(), catalog)) {
        rewritten.add(fact);
        continue;
      }
      List<CatalogMcpSystemDto> matches =
          matchesSystem(fact.participant(), fact.operation(), catalog);
      boolean skipAutoBind = wantsNewMcpService(text) && !fact.participant().isBlank();
      if (!skipAutoBind) {
        if (matches.size() == 1) {
          rewritten.add(withPath(fact, matches.getFirst().id));
          continue;
        }
        if (matches.size() > 1) {
          return new McpSystemGatherResult(
              input, Optional.of(pickerQuestion(matches)), systemList(catalog));
        }
      }
      if (fact.participant().isBlank()) {
        return new McpSystemGatherResult(input, Optional.of(ASK_NAME_PROMPT));
      }
      rewritten.add(withPath(fact, ""));
    }
    return new McpSystemGatherResult(List.copyOf(rewritten), Optional.empty());
  }

  private String resolveSystemId(
      ChainPlanNode trigger,
      RequirementBrief brief,
      int triggerCount,
      List<CatalogMcpSystemDto> systems) {
    RequirementFact matched = matchingMcpFact(trigger, brief, triggerCount);
    if (matched == null) {
      throw new IllegalArgumentException(
          "MCP trigger " + trigger.nodeId() + " has no matching mcp-trigger capability fact.");
    }
    String path = matched.path();
    if (path != null && !path.isBlank()) {
      String systemId = path.trim();
      if (!systemIdExists(systemId, systems)) {
        throw new IllegalArgumentException(
            "MCP trigger "
                + trigger.nodeId()
                + " path '"
                + systemId
                + "' is not a catalog MCP system. Recapture with a system id from the catalog.");
      }
      return systemId;
    }
    String participant = matched.participant();
    if (participant == null || participant.isBlank()) {
      throw new IllegalArgumentException(
          "MCP trigger " + trigger.nodeId() + " has no MCP service name for catalog create.");
    }
    boolean userSupplied = matched.operation() != null && !matched.operation().isBlank();
    String identifier =
        userSupplied ? matched.operation().trim() : identifierFromName(participant);
    if (userSupplied) {
      List<CatalogMcpSystemDto> matches = systemsWithIdentifier(identifier, systems);
      if (matches.size() == 1) {
        return matches.getFirst().id;
      }
      if (matches.size() > 1) {
        throw new IllegalArgumentException(
            "MCP trigger "
                + trigger.nodeId()
                + " identifier '"
                + identifier
                + "' matches several catalog MCP systems.");
      }
      CatalogMcpSystemDto created =
          catalogRestClient.createMcpSystem(
              new CatalogCreateMcpSystemRequest(participant.trim(), identifier, null));
      return trackCreatedSystem(created, systems);
    }
    String unusedIdentifier = uniqueIdentifier(identifier, systems);
    CatalogMcpSystemDto created =
        catalogRestClient.createMcpSystem(
            new CatalogCreateMcpSystemRequest(participant.trim(), unusedIdentifier, null));
    return trackCreatedSystem(created, systems);
  }

  private static RequirementFact matchingMcpFact(
      ChainPlanNode trigger, RequirementBrief brief, int triggerCount) {
    List<RequirementFact> facts =
        brief == null || brief.facts() == null ? List.of() : brief.facts();
    List<RequirementFact> mcpFacts = mcpTriggerFacts(facts);
    for (RequirementFact fact : mcpFacts) {
      if (trigger.nodeId().equals(fact.sourceFactId())) {
        return fact;
      }
    }
    if (triggerCount == 1 && mcpFacts.size() == 1) {
      return mcpFacts.getFirst();
    }
    return null;
  }

  private static String trackCreatedSystem(
      CatalogMcpSystemDto created, List<CatalogMcpSystemDto> systems) {
    String id = requireCreatedId(created);
    systems.add(created);
    return id;
  }

  private static String requireCreatedId(CatalogMcpSystemDto created) {
    if (created == null || created.id == null || created.id.isBlank()) {
      throw new IllegalStateException("catalog createMcpSystem returned no system id");
    }
    return created.id.trim();
  }

  private static String uniqueIdentifier(String base, List<CatalogMcpSystemDto> systems) {
    if (!identifierTaken(base, systems)) {
      return base;
    }
    for (int suffix = 2; ; suffix++) {
      String candidate = base + "-" + suffix;
      if (!identifierTaken(candidate, systems)) {
        return candidate;
      }
    }
  }

  private static boolean identifierTaken(String identifier, List<CatalogMcpSystemDto> systems) {
    for (CatalogMcpSystemDto system : systems) {
      if (system.identifier != null && identifier.equals(system.identifier.trim())) {
        return true;
      }
    }
    return false;
  }

  private static List<CatalogMcpSystemDto> systemsWithIdentifier(
      String identifier, List<CatalogMcpSystemDto> systems) {
    List<CatalogMcpSystemDto> matches = new ArrayList<>();
    for (CatalogMcpSystemDto system : systems) {
      if (system.identifier != null && identifier.equals(system.identifier.trim())) {
        matches.add(system);
      }
    }
    return matches;
  }

  private static List<ChainPlanNode> nodesOfType(ChainPlanGraph graph, String type) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (type.equals(node.type())) {
        nodes.add(node);
      }
    }
    return nodes;
  }

  private static List<RequirementFact> mcpTriggerFacts(List<RequirementFact> facts) {
    List<RequirementFact> mcpFacts = new ArrayList<>();
    for (RequirementFact fact : facts) {
      if (isMcpTriggerFact(fact)) {
        mcpFacts.add(fact);
      }
    }
    return mcpFacts;
  }

  private static boolean isMcpTriggerFact(RequirementFact fact) {
    return fact != null
        && fact.polarity() == RequirementFactPolarity.POSITIVE
        && fact.kind() == RequirementFactKind.CAPABILITY
        && "mcp-trigger".equals(fact.capabilityKey());
  }

  private static List<CatalogMcpSystemDto> usableSystems(List<CatalogMcpSystemDto> systems) {
    if (systems == null || systems.isEmpty()) {
      return List.of();
    }
    List<CatalogMcpSystemDto> usable = new ArrayList<>();
    for (CatalogMcpSystemDto system : systems) {
      if (system == null || system.id == null || system.id.isBlank()) {
        continue;
      }
      usable.add(system);
    }
    return List.copyOf(usable);
  }

  private static boolean systemIdExists(String systemId, List<CatalogMcpSystemDto> systems) {
    if (systemId == null || systemId.isBlank()) {
      return false;
    }
    String expected = systemId.trim();
    for (CatalogMcpSystemDto system : systems) {
      if (expected.equals(system.id)) {
        return true;
      }
    }
    return false;
  }

  private static List<CatalogMcpSystemDto> matchesSystem(
      String participant, String operation, List<CatalogMcpSystemDto> systems) {
    List<CatalogMcpSystemDto> matches = new ArrayList<>();
    for (CatalogMcpSystemDto system : systems) {
      if (matchesQuery(system, participant) || matchesQuery(system, operation)) {
        matches.add(system);
      }
    }
    return matches;
  }

  private static boolean matchesQuery(CatalogMcpSystemDto system, String query) {
    if (query == null || query.isBlank()) {
      return false;
    }
    String expected = query.trim();
    return fieldEquals(system.name, expected) || fieldEquals(system.identifier, expected);
  }

  private static boolean fieldEquals(String actual, String expected) {
    if (actual == null || actual.isBlank()) {
      return false;
    }
    return actual.trim().equalsIgnoreCase(expected);
  }

  private static boolean wantsNewMcpService(String assembledText) {
    if (assembledText == null || assembledText.isBlank()) {
      return false;
    }
    String lowered = assembledText.toLowerCase(Locale.ROOT);
    return lowered.contains("new mcp service") || lowered.contains("create a new mcp service");
  }

  private static RequirementFact withPath(RequirementFact fact, String path) {
    return new RequirementFact(
        fact.sourceFactId(),
        fact.polarity(),
        fact.kind(),
        fact.capabilityKey(),
        fact.text(),
        fact.participant(),
        fact.operation(),
        fact.topic(),
        fact.httpMethod(),
        path,
        fact.serviceCallId());
  }

  private static String pickerQuestion(List<CatalogMcpSystemDto> matches) {
    List<String> names = new ArrayList<>();
    for (CatalogMcpSystemDto system : matches) {
      names.add(displayName(system));
    }
    return PICKER_PROMPT + " Existing: " + String.join(", ", names) + ".";
  }

  private static String systemList(List<CatalogMcpSystemDto> systems) {
    List<String> labels = new ArrayList<>();
    for (CatalogMcpSystemDto system : systems) {
      labels.add(displayName(system) + " (id=" + system.id + ")");
    }
    return String.join("; ", labels);
  }

  private static String displayName(CatalogMcpSystemDto system) {
    if (system.name != null && !system.name.isBlank()) {
      return system.name.trim();
    }
    if (system.identifier != null && !system.identifier.isBlank()) {
      return system.identifier.trim();
    }
    return "(unnamed MCP service)";
  }
}
