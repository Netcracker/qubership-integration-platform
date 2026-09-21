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
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogMcpSystemDto;
import org.qubership.integration.platform.ai.plan.RequirementFact;
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
