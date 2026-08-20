package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/**
 * Requirement-stage API resolver that makes a local catalog check a hard prerequisite for API Hub
 * discovery.
 */
@ApplicationScoped
public class CatalogFirstApiHubDiscoveryTool {

  private final CatalogBindingMatcher catalogBindingMatcher;
  private final CatalogSystemReadTool catalogReadTool;
  private final ConversationCatalogCache catalogCache;
  private final ApiHubMcpTools apiHubMcpTools;
  private final ObjectMapper objectMapper;

  @Inject
  public CatalogFirstApiHubDiscoveryTool(
      CatalogBindingMatcher catalogBindingMatcher,
      CatalogSystemReadTool catalogReadTool,
      ConversationCatalogCache catalogCache,
      ApiHubMcpTools apiHubMcpTools,
      ObjectMapper objectMapper) {
    this.catalogBindingMatcher = catalogBindingMatcher;
    this.catalogReadTool = catalogReadTool;
    this.catalogCache = catalogCache;
    this.apiHubMcpTools = apiHubMcpTools;
    this.objectMapper = objectMapper;
  }

  @Tool("""
      Resolve one required service operation. This tool always checks the local runtime catalog
      first. It calls API Hub only when the catalog has no matching service and operation.
      Give the best known service name (or an empty string when unknown) and the required operation
      name or HTTP method/path. If status is CATALOG_BOUND, use its IDs as catalogBinding in the
      next captureRequirementDraft call. If status is APIHUB_CANDIDATES, select one result with
      selectApiHubCandidate. If status is AMBIGUOUS, ask the reader to choose; never search API Hub
      for an ambiguous local catalog match. On a catalog miss, the result is the API Hub search
      response and may contain multiple candidates.
      """)
  public String resolveApiOperation(
      @P("Best known catalog service name, or empty when unknown") String serviceName,
      @P("Required operation name or HTTP method/path") String operationQuery,
      @P("Optional target release, for example 2024.4") String release) {
    String service = CatalogStrings.blankToNull(serviceName);
    String query = CatalogStrings.blankToNull(operationQuery);
    if (query == null) {
      return error("operationQuery is required");
    }
    NormalizedDesignFlow flow = probeFlow(service, query, release);
    NormalizedDesignFlow.Step step = flow.steps().getFirst();
    CatalogBindingMatcher.MatchResult result = catalogBindingMatcher.match(flow, step);
    if (result instanceof CatalogBindingMatcher.MatchResult.Exact exact) {
      rememberCatalogEvidence(ChainPlanTool.resolveConversationId(), service, exact.match());
      return catalogBound(exact.match());
    }
    if (result instanceof CatalogBindingMatcher.MatchResult.Ambiguous ambiguous) {
      return ambiguous(ambiguous.candidateIds());
    }
    return apiHubMcpTools.searchApiOperations(query, "rest", release, 0, 100, null);
  }

  private void rememberCatalogEvidence(
      String conversationId, String serviceName, CatalogBindingMatcher.CatalogMatch match) {
    if (conversationId == null || conversationId.isBlank()) {
      return;
    }
    String search = serviceName == null ? match.systemName() : serviceName;
    List<CatalogRestClient.SystemDto> systems = catalogReadTool.searchCatalogSystems(search);
    catalogCache.rememberSystems(conversationId, systems);
    catalogCache.rememberActiveSystemId(conversationId, match.systemId());
    List<CatalogRestClient.SpecificationDto> specifications =
        catalogReadTool.getApiSpecifications(match.systemId());
    catalogCache.rememberSpecificationsForSystem(conversationId, match.systemId(), specifications);
    List<CatalogRestClient.OperationDto> operations =
        catalogReadTool.listCatalogOperations(
            match.specificationId(), match.systemId(), null);
    for (CatalogRestClient.OperationDto operation : operations) {
      catalogCache.rememberOperation(conversationId, operation);
    }
  }

  private String catalogBound(CatalogBindingMatcher.CatalogMatch match) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("status", "CATALOG_BOUND");
      ObjectNode binding = root.putObject("catalogBinding");
      binding.put("systemId", match.systemId());
      binding.put("specificationId", match.specificationId());
      binding.put("specificationGroupId", match.specificationGroupId());
      binding.put("integrationOperationId", match.integrationOperationId());
      binding.put("systemName", match.systemName());
      binding.put("evidenceRef", match.evidenceRef());
      return objectMapper.writeValueAsString(root);
    } catch (Exception exception) {
      return error("could not encode catalog binding: " + exception.getMessage());
    }
  }

  private String ambiguous(List<String> candidateIds) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("status", "AMBIGUOUS");
      root.putPOJO("candidateOperationIds", candidateIds == null ? List.of() : candidateIds);
      return objectMapper.writeValueAsString(root);
    } catch (Exception exception) {
      return error("could not encode catalog candidates: " + exception.getMessage());
    }
  }

  private String error(String message) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("status", "ERROR");
      root.put("message", message);
      return objectMapper.writeValueAsString(root);
    } catch (Exception ignored) {
      return "{\"status\":\"ERROR\"}";
    }
  }

  private static NormalizedDesignFlow probeFlow(
      String serviceName, String operationQuery, String release) {
    String service = serviceName == null ? "service" : serviceName;
    List<String> constraints =
        CatalogStrings.blankToNull(release) == null ? List.of() : List.of("release: " + release);
    return new NormalizedDesignFlow(
        "1",
        "requirement-api-resolution",
        "requirement-api-resolution",
        "",
        new NormalizedDesignFlow.Trigger("http", "client", null, null, null, List.of()),
        List.of(
            new NormalizedDesignFlow.Participant("client", "Client", "EXTERNAL", List.of()),
            new NormalizedDesignFlow.Participant("service", service, "EXTERNAL", List.of())),
        List.of(
            new NormalizedDesignFlow.Step(
                "service-call", "service-call", "client", "service", operationQuery, "", List.of())),
        List.of(),
        List.of(),
        List.of(),
        constraints,
        List.of(),
        NormalizedDesignFlow.BindingResolutionPolicy.CATALOG_FIRST);
  }
}
