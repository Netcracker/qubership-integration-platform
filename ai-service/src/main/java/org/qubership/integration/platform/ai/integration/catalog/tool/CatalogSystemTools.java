package org.qubership.integration.platform.ai.integration.catalog.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.validation.CatalogSystemToolNames;
import org.qubership.integration.platform.ai.logging.ToolTraceLog;

/**
 * Read-only catalog tools for GATHER_REQUIREMENTS and CREATE_CHAIN_PLAN: system search,
 * specifications, operations.
 * Mutating tools (createSystem, importApiHubSpec) are intentionally excluded — they belong
 * to IMPLEMENT_CHAIN and will be added in a separate step class.
 */
@ApplicationScoped
public class CatalogSystemTools {

  private static final Logger LOG = Logger.getLogger(CatalogSystemTools.class);

  private final CatalogSystemReadTool readSupport;
  private final CatalogToolSupport support;

  @Inject
  public CatalogSystemTools(CatalogSystemReadTool readSupport, CatalogToolSupport support) {
    this.readSupport = readSupport;
    this.support = support;
  }

  @Tool("Search QIP catalog services (API Repository systems) by name substring. Call FIRST when"
      + " binding service-call from design: if a match exists, use systemId with"
      + " getApiSpecifications then listCatalogOperations. Only use APIHub"
      + " (searchApiOperations) when no suitable catalog service is found and APIHub is"
      + " available. Returns JSON: { ok, tool, data: SystemDto[] }.")
  public String searchCatalogSystems(
      @P("Substring to match service name (catalog searchCondition)") String searchCondition) {
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG, CatalogSystemToolNames.SEARCH, null, "searchCondition=" + searchCondition);
    try {
      String out = readSupport.searchCatalogSystemsJson(searchCondition);
      support.logCatalogToolDone(CatalogSystemToolNames.SEARCH, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.SEARCH, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.SEARCH, null, System.currentTimeMillis() - startMs, e);
      throw e;
    }
  }

  @Tool("Resolve the operation an existing element is already bound to. Call FIRST when changing"
      + " the operation on an element that already has one: pass its integrationOperationId and"
      + " the answer names the operation and the specification it belongs to, so"
      + " listCatalogOperations on that specificationId lists what else the same service offers."
      + " Searching by the element's own name finds nothing -- that is a label, not a service."
      + " Returns JSON: { ok, tool, message, data: OperationDto }.")
  public String describeBoundOperation(
      @P("Operation UUID from the element's integrationOperationId property") String operationId) {
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG, CatalogSystemToolNames.BOUND, null, "operationId=" + operationId);
    try {
      String out = readSupport.describeBoundOperationJson(operationId);
      support.logCatalogToolDone(CatalogSystemToolNames.BOUND, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.BOUND, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.BOUND, null, System.currentTimeMillis() - startMs, e);
      throw e;
    }
  }

  @Tool("Get API specifications (models) for a catalog system. Use systemId returned by"
      + " searchCatalogSystems. Returns JSON: { ok, tool, data: SpecificationDto[] }.")
  public String getApiSpecifications(
      @P("Catalog system UUID from searchCatalogSystems result") String systemId) {
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, CatalogSystemToolNames.SPECS, null, "systemId=" + systemId);
    try {
      String out = readSupport.getApiSpecificationsJson(systemId);
      support.logCatalogToolDone(CatalogSystemToolNames.SPECS, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.SPECS, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.SPECS, null, System.currentTimeMillis() - startMs, e);
      throw e;
    }
  }

  @Tool("List operations for a catalog specification. Use specificationId (model id) returned by"
      + " getApiSpecifications. Optional searchFilter for name substring. Returns JSON: { ok,"
      + " tool, data: OperationDto[] }.")
  public String listCatalogOperations(
      @P("Specification UUID from getApiSpecifications result") String specificationId,
      @P("Optional system UUID for context") String systemId,
      @P("Optional name filter substring") String searchFilter) {
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG,
        CatalogSystemToolNames.OPS,
        null,
        "specificationId="
            + specificationId
            + " systemId="
            + systemId
            + " searchFilter="
            + searchFilter);
    try {
      String out = readSupport.listCatalogOperationsJson(specificationId, systemId, searchFilter);
      support.logCatalogToolDone(CatalogSystemToolNames.OPS, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.OPS, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.OPS, null, System.currentTimeMillis() - startMs, e);
      throw e;
    }
  }
}
