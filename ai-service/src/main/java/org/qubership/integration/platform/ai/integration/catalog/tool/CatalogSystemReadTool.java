package org.qubership.integration.platform.ai.integration.catalog.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.qubership.integration.platform.ai.integration.catalog.validation.CatalogLookupArgsValidator;
import org.qubership.integration.platform.ai.integration.catalog.validation.CatalogSystemToolNames;

import java.util.List;
import java.util.Optional;

/**
 * Shared catalog read logic for {@link CatalogSystemTools} (search,
 * specifications, operations).
 */
@ApplicationScoped
public class CatalogSystemReadTool {

  private final CatalogRestClient catalogRestClient;

  private final CatalogOperationsLookupService operationsLookup;

  private final CatalogToolSupport support;

  @Inject
  public CatalogSystemReadTool(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogOperationsLookupService operationsLookup,
      CatalogToolSupport support) {
    this.catalogRestClient = catalogRestClient;
    this.operationsLookup = operationsLookup;
    this.support = support;
  }

  public String searchCatalogSystemsJson(String searchCondition) {
    String q = CatalogStrings.blankToNull(searchCondition);
    if (q == null) {
      return support.catalogToolError(
          CatalogSystemToolNames.SEARCH,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "searchCondition is required",
          "Provide a non-empty substring to match service names.");
    }
    List<CatalogRestClient.SystemDto> systems = catalogRestClient.searchSystems(new CatalogSystemSearchRequest(q));
    operationsLookup.rememberSystems(systems);
      return support.catalogToolSuccess(CatalogSystemToolNames.SEARCH, systems);
  }

  public String getApiSpecificationsJson(String systemId) {
    Optional<CatalogToolResult.ErrorSpec> validationError =
        CatalogLookupArgsValidator.validateSystemIdForSpecifications(systemId);
    if (validationError.isPresent()) {
      return support.catalogToolError(CatalogSystemToolNames.SPECS, validationError.get());
    }
    String sid = CatalogStrings.blankToNull(systemId);
    operationsLookup.rememberActiveSystemId(sid);
    List<CatalogRestClient.SpecificationDto> specs = catalogRestClient.getApiSpecifications(sid);
    operationsLookup.rememberSpecificationsForSystem(sid, specs);
    if (specs == null || specs.isEmpty()) {
      Optional<String> hint = CatalogLookupArgsValidator.emptySpecificationsHint(sid);
      return hint.isPresent()
          ? support.catalogToolSuccess(CatalogSystemToolNames.SPECS, hint.get(), List.of())
          : support.catalogToolSuccess(CatalogSystemToolNames.SPECS, List.of());
    }
    return support.catalogToolSuccess(CatalogSystemToolNames.SPECS, specs);
  }

  public String listCatalogOperationsJson(
      String specificationId, String systemId, String searchFilter) {
    Optional<CatalogToolResult.ErrorSpec> validationError = operationsLookup
        .validateSpecificationIdForOperations(specificationId);
    if (validationError.isPresent()) {
      return support.catalogToolError(CatalogSystemToolNames.OPS, validationError.get());
    }
    String mid = CatalogStrings.blankToNull(specificationId);
    String filter = CatalogStrings.blankToNull(searchFilter);
    List<CatalogRestClient.OperationDto> ops = filter != null
        ? operationsLookup.findOperations(mid, systemId, filter)
        : operationsLookup.listOperations(mid, systemId);
    if (ops == null || ops.isEmpty()) {
      Optional<String> hint = CatalogLookupArgsValidator.emptyOperationsHint(mid);
      return hint.isPresent()
          ? support.catalogToolSuccess(CatalogSystemToolNames.OPS, hint.get(), List.of())
          : support.catalogToolSuccess(CatalogSystemToolNames.OPS, List.of());
    }
    return support.catalogToolSuccess(CatalogSystemToolNames.OPS, ops);
  }
}
