package org.qubership.integration.platform.ai.integration.catalog.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogElementPlacementRules;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogChainDiffs;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** LangChain4j catalog tools for connections and plan comparison stub. */
@ApplicationScoped
public class CatalogConnectionTools {

  private static final String TOOL_CREATE_CONNECTION = "createConnection";
  private static final String TOOL_GET_DEPENDENCIES = "getDependencies";
  private static final String TOOL_COMPARE_STUB = "comparePlanToCatalog";

  private static final Logger LOG = Logger.getLogger(CatalogConnectionTools.class);

  @Inject @RestClient CatalogRestClient catalogRestClient;

  @Inject CatalogElementPlacementRules catalogElementPlacementRules;

  @Inject CatalogToolSupport support;

  @Tool(
      "Create a connection between two elements in a QIP chain. Use this for real runtime"
          + " dependency edges between runtime catalog element UUIDs. Resolve plan clientId values"
          + " through the createElementsByJson map or getElements before this call. Parent/child"
          + " placement belongs to createElementsByJson, createElement, or transferElements. For"
          + " condition flows, target the root condition runtime element. The tool rejects"
          + " endpoints whose descriptors have outputEnabled=false (source) or inputEnabled=false"
          + " (target). Returns JSON: { ok, tool, message, data: { dependencyId } }.")
  public String createConnection(
      @P("Catalog chainId returned by createChain") String chainId,
      @P("Source runtime catalog element UUID (from)") String fromElementId,
      @P("Target runtime catalog element UUID (to)") String toElementId) {
    String blocked = CatalogMutationGuard.rejectOrNull(TOOL_CREATE_CONNECTION);
    if (blocked != null) {
      return blocked;
    }
    LOG.infof(
        "Catalog tool createConnection: chainId=%s, from=%s, to=%s",
        chainId, fromElementId, toElementId);
    try {
      CatalogElementResponseDto fromEl = catalogRestClient.getElement(chainId, fromElementId);
      CatalogElementResponseDto toEl = catalogRestClient.getElement(chainId, toElementId);
      Optional<String> placementErr =
          catalogElementPlacementRules.validateConnection(fromEl.type, toEl.type);
      if (placementErr.isPresent()) {
        return support.catalogToolError(
            TOOL_CREATE_CONNECTION,
            CatalogToolResult.CODE_INVALID_ARGUMENT,
            placementErr.get());
      }
      CatalogRestClient.ChainDiffDto result =
          catalogRestClient.createConnection(
              chainId, new CatalogCreateDependencyRequest(fromElementId, toElementId));
      String depId = CatalogChainDiffs.firstCreatedDependencyIdOrUnknown(result);
      String out =
          support.catalogToolSuccess(
              TOOL_CREATE_CONNECTION,
              "Connection created.",
              Map.of("dependencyId", depId));
      support.logCatalogToolDone(TOOL_CREATE_CONNECTION, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_CREATE_CONNECTION, e);
    }
  }

  @Tool(
      "List dependencies (directed connections) for a chain. Returns JSON: { ok, tool, data:"
          + " DependencyDto[] }.")
  public String getDependencies(@P("Chain ID") String chainId) {
    LOG.infof("Catalog tool getDependencies: chainId=%s", chainId);
    try {
      List<CatalogDependencyDto> deps = catalogRestClient.listDependencies(chainId);
      String out = support.catalogToolSuccess(TOOL_GET_DEPENDENCIES, deps);
      support.logCatalogToolDone(TOOL_GET_DEPENDENCIES, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_GET_DEPENDENCIES, e);
    }
  }

  /**
   * Stub for contract tests only — not registered as an LLM {@code @Tool}. Use getElements and
   * getDependencies.
   */
  public String comparePlanToCatalogStub(
      String chainId, String expectedPlanJson, String clientIdToElementIdJson) {
    LOG.infof(
        "Catalog tool comparePlanToCatalog (stub): chainId=%s, planLen=%d, mapLen=%d",
        chainId,
        expectedPlanJson != null ? expectedPlanJson.length() : 0,
        clientIdToElementIdJson != null ? clientIdToElementIdJson.length() : 0);
    try {
      Map<String, Object> body =
          Map.of(
              "ok",
              false,
              "errors",
              List.of(
                  "comparePlanToCatalog is disabled (stub); use getElements and getDependencies."),
              "warnings",
              List.of());
      String out = support.catalogToolSuccess(TOOL_COMPARE_STUB, body);
      support.logCatalogToolDone(TOOL_COMPARE_STUB, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_COMPARE_STUB, e);
    }
  }
}
