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

import java.util.ArrayList;
import java.util.LinkedHashSet;
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

  /**
   * Typed catalog system search used by product-pipeline binding resolution. Blank queries yield an
   * empty list (JSON tool path still returns INVALID_ARGUMENT).
   *
   * <p>When the primary {@code searchCondition} returns no hits, retries with significant tokens
   * (length &ge; 4) so human names like {@code Party Management} still find import-derived catalog
   * names such as {@code S ProdCat PartyMgmt}.
   */
  public List<CatalogRestClient.SystemDto> searchCatalogSystems(String searchCondition) {
    String q = CatalogStrings.blankToNull(searchCondition);
    if (q == null) {
      return List.of();
    }
    List<CatalogRestClient.SystemDto> systems =
        catalogRestClient.searchSystems(new CatalogSystemSearchRequest(q));
    if (systems == null || systems.isEmpty()) {
      systems = searchWithTokenFallbacks(q);
    }
    operationsLookup.rememberSystems(systems);
    return systems == null ? List.of() : List.copyOf(systems);
  }

  private List<CatalogRestClient.SystemDto> searchWithTokenFallbacks(String query) {
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<CatalogRestClient.SystemDto> out = new ArrayList<>();
    for (String token : query.split("[\\s._-]+")) {
      String t = CatalogStrings.blankToNull(token);
      if (t == null || t.length() < 4) {
        continue;
      }
      List<CatalogRestClient.SystemDto> found =
          catalogRestClient.searchSystems(new CatalogSystemSearchRequest(t));
      if (found == null) {
        continue;
      }
      for (CatalogRestClient.SystemDto system : found) {
        if (system != null
            && CatalogStrings.blankToNull(system.id()) != null
            && seen.add(system.id())) {
          out.add(system);
        }
      }
    }
    return out;
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
    return support.catalogToolSuccess(
        CatalogSystemToolNames.SEARCH, searchCatalogSystems(searchCondition));
  }

  /** Typed specifications lookup for catalog-first binding resolution. */
  public List<CatalogRestClient.SpecificationDto> getApiSpecifications(String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid == null) {
      return List.of();
    }
    operationsLookup.rememberActiveSystemId(sid);
    List<CatalogRestClient.SpecificationDto> specs = catalogRestClient.getApiSpecifications(sid);
    operationsLookup.rememberSpecificationsForSystem(sid, specs);
    return specs == null ? List.of() : List.copyOf(specs);
  }

  public String getApiSpecificationsJson(String systemId) {
    Optional<CatalogToolResult.ErrorSpec> validationError =
        CatalogLookupArgsValidator.validateSystemIdForSpecifications(systemId);
    if (validationError.isPresent()) {
      return support.catalogToolError(CatalogSystemToolNames.SPECS, validationError.get());
    }
    String sid = CatalogStrings.blankToNull(systemId);
    List<CatalogRestClient.SpecificationDto> specs = getApiSpecifications(sid);
    if (specs.isEmpty()) {
      Optional<String> hint = CatalogLookupArgsValidator.emptySpecificationsHint(sid);
      return hint.isPresent()
          ? support.catalogToolSuccess(CatalogSystemToolNames.SPECS, hint.get(), List.of())
          : support.catalogToolSuccess(CatalogSystemToolNames.SPECS, List.of());
    }
    return support.catalogToolSuccess(CatalogSystemToolNames.SPECS, specs);
  }

  /** Typed operations lookup for catalog-first binding resolution. */
  public List<CatalogRestClient.OperationDto> listCatalogOperations(
      String specificationId, String systemId, String searchFilter) {
    String mid = CatalogStrings.blankToNull(specificationId);
    if (mid == null) {
      return List.of();
    }
    String filter = CatalogStrings.blankToNull(searchFilter);
    List<CatalogRestClient.OperationDto> ops =
        filter != null
            ? operationsLookup.findOperations(mid, systemId, filter)
            : operationsLookup.listOperations(mid, systemId);
    return ops == null ? List.of() : List.copyOf(ops);
  }

  public String listCatalogOperationsJson(
      String specificationId, String systemId, String searchFilter) {
    Optional<CatalogToolResult.ErrorSpec> validationError =
        operationsLookup.validateSpecificationIdForOperations(specificationId);
    if (validationError.isPresent()) {
      return support.catalogToolError(CatalogSystemToolNames.OPS, validationError.get());
    }
    String mid = CatalogStrings.blankToNull(specificationId);
    List<CatalogRestClient.OperationDto> ops =
        listCatalogOperations(specificationId, systemId, searchFilter);
    if (ops.isEmpty()) {
      Optional<String> hint = CatalogLookupArgsValidator.emptyOperationsHint(mid);
      return hint.isPresent()
          ? support.catalogToolSuccess(CatalogSystemToolNames.OPS, hint.get(), List.of())
          : support.catalogToolSuccess(CatalogSystemToolNames.OPS, List.of());
    }
    return support.catalogToolSuccess(CatalogSystemToolNames.OPS, ops);
  }
}
