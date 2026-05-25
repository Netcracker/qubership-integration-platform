package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.integration.catalog.validation.CatalogImportApiHubArgsValidator;
import org.qubership.integration.platform.ai.integration.catalog.validation.CatalogSystemToolNames;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LangChain4j catalog tools for systems, API specifications, and operations
 * (read + mutate).
 */
@ApplicationScoped
public class CatalogSystemTools {

  private static final Logger LOG = Logger.getLogger(CatalogSystemTools.class);

  private final CatalogRestClient catalogRestClient;

  private final CatalogToolSupport support;

  private final CatalogSystemReadTool readSupport;

  private final ConversationPlanningDiaryService planningDiaryService;

  private final ObjectMapper objectMapper;

  @Inject
  public CatalogSystemTools(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogToolSupport support,
      CatalogSystemReadTool readSupport,
      ConversationPlanningDiaryService planningDiaryService,
      ObjectMapper objectMapper) {
    this.catalogRestClient = catalogRestClient;
    this.support = support;
    this.readSupport = readSupport;
    this.planningDiaryService = planningDiaryService;
    this.objectMapper = objectMapper;
  }

  @Tool("Search QIP catalog services (API Repository systems) by name substring. Call FIRST when"
      + " binding service-call from design: if a match exists, use systemId with"
      + " getApiSpecifications then listCatalogOperations. Only use APIHub"
      + " (searchRestApiOperations) when no suitable catalog service is found and APIHub is"
      + " available. Returns JSON: { ok, tool, data: SystemDto[] }.")
  public String searchCatalogSystems(
      @P("Substring to match service name (catalog searchCondition)") String searchCondition) {
    LOG.infof("Catalog tool %s: searchCondition=%s", CatalogSystemToolNames.SEARCH, searchCondition);
    try {
      String out = readSupport.searchCatalogSystemsJson(searchCondition);
      logDone(CatalogSystemToolNames.SEARCH, out);
      recordCatalogOutcomeIfApplicable(
          CatalogSystemToolNames.SEARCH, "searchCondition=" + searchCondition, out);
      return out;
    } catch (Exception e) {
      String err = support.catalogToolError(CatalogSystemToolNames.SEARCH, e);
      recordCatalogOutcomeIfApplicable(
          CatalogSystemToolNames.SEARCH, "searchCondition=" + searchCondition, err);
      return err;
    }
  }

  @Tool("List API specifications for a QIP system. Returns SpecificationDto[]: use each data[].id"
      + " for listCatalogOperations(specificationId), not systemId. Returns JSON: { ok, tool,"
      + " data: SpecificationDto[], message? }.")
  public String getApiSpecifications(
      @P("System ID from searchCatalogSystems or plan") String systemId) {
    LOG.infof("Catalog tool %s: systemId=%s", CatalogSystemToolNames.SPECS, systemId);
    try {
      String out = readSupport.getApiSpecificationsJson(systemId);
      logDone(CatalogSystemToolNames.SPECS, out);
      recordCatalogOutcomeIfApplicable(CatalogSystemToolNames.SPECS, "systemId=" + systemId, out);
      return out;
    } catch (Exception e) {
      String err = support.catalogToolError(CatalogSystemToolNames.SPECS, e);
      recordCatalogOutcomeIfApplicable(CatalogSystemToolNames.SPECS, "systemId=" + systemId, err);
      return err;
    }
  }

  @Tool("List operations for one API specification. Call once per specificationId; optional"
      + " searchFilter narrows by id, name, path, or method (in-memory). Optional systemId"
      + " improves session cache. specificationId must be an id from getApiSpecifications"
      + " data[], not systemId from searchCatalogSystems. Returns JSON: { ok, tool, data:"
      + " OperationDto[], message? }.")
  public String listCatalogOperations(
      @P("Specification id from getApiSpecifications data[].id") String specificationId,
      @P("Optional system id from searchCatalogSystems; improves cache context") String systemId,
      @P("Optional filter substring; omit for full list") String searchFilter) {
    LOG.infof(
        "Catalog tool %s: specificationId=%s, systemId=%s, searchFilter=%s",
        CatalogSystemToolNames.OPS,
        specificationId,
        systemId,
        CatalogStrings.blankToNull(searchFilter));
    try {
      String out = readSupport.listCatalogOperationsJson(specificationId, systemId, searchFilter);
      logDone(CatalogSystemToolNames.OPS, out);
      String argSummary = "specificationId="
          + specificationId
          + (CatalogStrings.blankToNull(searchFilter) != null
              ? ",searchFilter=" + searchFilter
              : ",searchFilter=(none)");
      recordCatalogOutcomeIfApplicable(CatalogSystemToolNames.OPS, argSummary, out);
      return out;
    } catch (Exception e) {
      String err = support.catalogToolError(CatalogSystemToolNames.OPS, e);
      recordCatalogOutcomeIfApplicable(
          CatalogSystemToolNames.OPS, "specificationId=" + specificationId, err);
      return err;
    }
  }

  @Tool("Create a QIP system (external or internal service). "
      + "serviceType: INTERNAL for Netcracker/TMF APIs, EXTERNAL for third-party APIs. "
      + "Returns JSON: { ok, tool, message, data: { systemId, name } }. Prefer"
      + " searchCatalogSystems first; skip createSystem when an existing catalog service already"
      + " fits the design.")
  public String createSystem(
      @P("System name") String name, @P("Service type: INTERNAL or EXTERNAL") String serviceType) {
    String blocked = CatalogMutationGuard.rejectOrNull(CatalogSystemToolNames.CREATE_SYSTEM);
    if (blocked != null) {
      return blocked;
    }
    LOG.infof("Catalog tool createSystem: name=%s, type=%s", name, serviceType);
    try {
      CatalogRestClient.SystemDto result = catalogRestClient
          .createSystem(new CatalogCreateSystemRequest(name, serviceType));
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("systemId", result.id());
      data.put("name", result.name());
      String out = support.catalogToolSuccess(CatalogSystemToolNames.CREATE_SYSTEM, "System created.", data);
      support.logCatalogToolDone(CatalogSystemToolNames.CREATE_SYSTEM, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(CatalogSystemToolNames.CREATE_SYSTEM, e);
    }
  }

  @Tool("Import an API specification from APIHUB into a QIP system. Call this after createSystem when"
      + " no matching service was found in the catalog (searchCatalogSystems). Use packageId,"
      + " version, operationId from searchRestApiOperations. Not for fixing placeholder"
      + " integrationOperationId on an existing IMPLEMENTED catalog service. Returns JSON:"
      + " { ok, tool, message, data: { specId } }.")
  public String importApiHubSpecToSystem(
      @P("System ID from createSystem") String systemId,
      @P("Package ID from APIHUB search") String packageId,
      @P("Version from APIHUB search") String version,
      @P("Operation ID from APIHUB search") String operationId,
      @P("Specification name") String name) {
    String blocked = CatalogMutationGuard.rejectOrNull(CatalogSystemToolNames.IMPORT_APIHUB);
    if (blocked != null) {
      return blocked;
    }
    LOG.infof(
        "Catalog tool importApiHubSpecToSystem: systemId=%s, packageId=%s, version=%s,"
            + " operationId=%s, name=%s",
        systemId, packageId, version, operationId, name);
    String validationError = validateImportApiHubArgs(packageId, version, operationId);
    if (validationError != null) {
      return validationError;
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("packageId", packageId.trim());
      body.put("version", version.trim());
      body.put("operationId", operationId.trim());
      if (name != null && !name.isBlank()) {
        body.put("name", name.trim());
      }
      CatalogRestClient.SpecificationDto result = catalogRestClient.importApiHubSpec(systemId, body);
      Map<String, Object> data = Map.of("specId", result.id());
      String out = support.catalogToolSuccess(CatalogSystemToolNames.IMPORT_APIHUB, "Spec imported.", data);
      support.logCatalogToolDone(CatalogSystemToolNames.IMPORT_APIHUB, out);
      return out;
    } catch (Exception e) {
      return support.catalogToolError(CatalogSystemToolNames.IMPORT_APIHUB, e);
    }
  }

  static Optional<CatalogToolResult.ErrorSpec> validateImportApiHubArgsSpec(
      String packageId, String version, String operationId) {
    return CatalogImportApiHubArgsValidator.validate(packageId, version, operationId);
  }

  private String validateImportApiHubArgs(String packageId, String version, String operationId) {
    return validateImportApiHubArgsSpec(packageId, version, operationId)
        .map(spec -> support.catalogToolError(CatalogSystemToolNames.IMPORT_APIHUB, spec))
        .orElse(null);
  }

  private void logDone(String toolName, String outcome) {
    support.logCatalogToolDone(toolName, outcome);
  }

  private void recordCatalogOutcomeIfApplicable(String toolName, String argsSummary, String out) {
    if (!ScenarioType.CREATE_CHAIN_PLAN.name().equals(MDC.get(ChatMdc.SCENARIO_TYPE))) {
      return;
    }
    String conversationId = resolveConversationIdFromMdc();
    if (conversationId.isBlank()) {
      return;
    }
    if (support.isToolError(out)) {
      planningDiaryService.recordCatalogLookupNote(
          conversationId, toolName, argsSummary, "error", out);
      return;
    }
    try {
      JsonNode data = CatalogToolResult.dataOrNull(objectMapper, out);
      if (data != null && data.isArray() && data.isEmpty()) {
        planningDiaryService.recordCatalogLookupNote(
            conversationId, toolName, argsSummary, "empty", "[]");
        return;
      }
      String payloadJson = CatalogToolResult.unwrapDataJson(objectMapper, out);
      planningDiaryService.recordCatalogToolSuccessFromJson(
          conversationId, toolName, argsSummary, payloadJson, objectMapper);
    } catch (Exception ignored) {
      // non-JSON tool output — skip
    }
  }

  private static String resolveConversationIdFromMdc() {
    String fromMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    return fromMdc != null ? fromMdc.trim() : "";
  }
}
