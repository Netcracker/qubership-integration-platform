package org.qubership.integration.platform.ai.integration.catalog.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMemoryIds;
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

  @Tool("Search QIP catalog services (API Repository systems) by name substring. Capture"
      + " RequirementFlow first; this browse does not bind an interaction. After the flow is"
      + " stored, use a matching systemId with getApiSpecifications then listCatalogOperations,"
      + " then call resolveApiOperation with that interactionId. Only use APIHub"
      + " (searchApiOperations) when no suitable catalog service is found and APIHub is"
      + " available. Returns JSON: { ok, tool, message, data: SystemDto[] }.")
  public String searchCatalogSystems(
      @P("Substring to match service name (catalog searchCondition)") String searchCondition,
      @ToolMemoryId String memoryId) {
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG, CatalogSystemToolNames.SEARCH, null, "searchCondition=" + searchCondition);
    try {
      String out = readSupport.searchCatalogSystemsJson(CompilerSkillMemoryIds.conversationId(memoryId), searchCondition);
      support.logCatalogToolDone(CatalogSystemToolNames.SEARCH, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.SEARCH, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.SEARCH, null, System.currentTimeMillis() - startMs, e);
      return support.catalogToolError(CatalogSystemToolNames.SEARCH, e);
    }
  }

  @Tool("Resolve the operation an existing element is already bound to. Call FIRST when changing"
      + " the operation on an element that already has one: pass its integrationOperationId and"
      + " the answer names the operation and the specification it belongs to, so"
      + " listCatalogOperations on that specificationId lists what else the same service offers."
      + " Searching by the element's own name finds nothing -- that is a label, not a service."
      + " Returns JSON: { ok, tool, message, data: OperationDto }.")
  public String describeBoundOperation(
      @P("Operation UUID from the element's integrationOperationId property") String operationId,
      @ToolMemoryId String memoryId) {
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(
        LOG, CatalogSystemToolNames.BOUND, null, "operationId=" + operationId);
    try {
      String out = readSupport.describeBoundOperationJson(CompilerSkillMemoryIds.conversationId(memoryId), operationId);
      support.logCatalogToolDone(CatalogSystemToolNames.BOUND, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.BOUND, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.BOUND, null, System.currentTimeMillis() - startMs, e);
      return support.catalogToolError(CatalogSystemToolNames.BOUND, e);
    }
  }

  @Tool("Get API specifications (models) for a catalog system. Use systemId returned by"
      + " searchCatalogSystems. This listing does not bind an interaction; call"
      + " resolveApiOperation with the interactionId from the stored RequirementFlow after you"
      + " pick an operation. Returns JSON:"
      + " { ok, tool, message, data: SpecificationDto[] }.")
  public String getApiSpecifications(
      @P("Catalog system UUID from searchCatalogSystems result") String systemId,
      @ToolMemoryId String memoryId) {
    long startMs = System.currentTimeMillis();
    ToolTraceLog.logToolInvoke(LOG, CatalogSystemToolNames.SPECS, null, "systemId=" + systemId);
    try {
      String out = readSupport.getApiSpecificationsJson(CompilerSkillMemoryIds.conversationId(memoryId), systemId);
      support.logCatalogToolDone(CatalogSystemToolNames.SPECS, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.SPECS, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.SPECS, null, System.currentTimeMillis() - startMs, e);
      return support.catalogToolError(CatalogSystemToolNames.SPECS, e);
    }
  }

  public String listCatalogOperations(
      String specificationId, String systemId, String searchFilter, String memoryId) {
    return listCatalogOperations(specificationId, systemId, searchFilter, null, memoryId);
  }

  @Tool("List operations for a catalog specification. Use specificationId (model id) returned by"
      + " getApiSpecifications. Optional searchFilter for name substring. This listing does not"
      + " bind an interaction; call resolveApiOperation with the interactionId from the stored"
      + " RequirementFlow to record the match. Returns JSON:"
      + " Summaries omit schemas. Returns JSON: { ok, tool, message, data, total, complete, nextOffset }."
      + " If complete is false, continue with nextOffset as offset or use resolveApiOperation,"
      + " which searches the complete catalog without paging.")
  public String listCatalogOperations(
      @P("Specification UUID from getApiSpecifications result") String specificationId,
      @P("Optional system UUID for context") String systemId,
      @P("Optional name filter substring") String searchFilter,
      @P("Listing offset; null or 0 starts a new listing, otherwise use nextOffset") Integer offset,
      @ToolMemoryId String memoryId) {
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
      String out = readSupport.listCatalogOperationsJson(
          CompilerSkillMemoryIds.conversationId(memoryId), specificationId, systemId, searchFilter, offset);
      support.logCatalogToolDone(CatalogSystemToolNames.OPS, out);
      ToolTraceLog.logToolComplete(
          LOG, CatalogSystemToolNames.OPS, null, System.currentTimeMillis() - startMs, out);
      return out;
    } catch (Exception e) {
      ToolTraceLog.logToolFailed(
          LOG, CatalogSystemToolNames.OPS, null, System.currentTimeMillis() - startMs, e);
      return support.catalogToolError(CatalogSystemToolNames.OPS, e);
    }
  }
}
