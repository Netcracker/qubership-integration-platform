package org.qubership.integration.platform.ai.integration.catalog.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** LangChain4j catalog tools for chain-level operations. */
@ApplicationScoped
public class CatalogChainTools {

  private static final String TOOL_CREATE_CHAIN = "createChain";
  private static final String TOOL_GET_CHAIN = "getChain";

  private static final Logger LOG = Logger.getLogger(CatalogChainTools.class);

  @Inject @RestClient CatalogRestClient catalogRestClient;

  @Inject CatalogToolSupport support;

  @Tool(
      "Create a new QIP integration chain. Returns JSON: { ok, tool, message, data: { chainId, name"
          + " } } for every subsequent catalog call for this execution. planId is only the"
          + " approved-plan snapshot id.")
  public String createChain(
      @P("Chain name") String name, @P("Chain description, can be empty") String description) {
    String blocked = CatalogMutationGuard.rejectOrNull(TOOL_CREATE_CHAIN);
    if (blocked != null) {
      return blocked;
    }
    LOG.infof(
        "Catalog tool createChain: name=%s, descriptionPresent=%s",
        name, description != null && !description.isBlank());
    try {
      CatalogRestClient.ChainDto result =
          catalogRestClient.createChain(CatalogCreateChainRequest.of(name, description));
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("chainId", result.id());
      data.put("name", result.name());
      String out = support.catalogToolSuccess(TOOL_CREATE_CHAIN, "Chain created.", data);
      support.logCatalogToolDone(TOOL_CREATE_CHAIN, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_CREATE_CHAIN, e);
    }
  }

  @Tool(
      "Get a QIP chain by chainId. Returns JSON: { ok, tool, data: chain metadata }. For element"
          + " properties use getElements.")
  public String getChain(@P("Chain ID") String chainId) {
    LOG.infof("Catalog tool getChain: chainId=%s", chainId);
    try {
      CatalogRestClient.ChainDto chain = catalogRestClient.getChain(chainId);
      String out = support.catalogToolSuccess(TOOL_GET_CHAIN, chain);
      support.logCatalogToolDone(TOOL_GET_CHAIN, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(TOOL_GET_CHAIN, e);
    }
  }
}
