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

  static final String LISTING_IS_NOT_A_BINDING =
      "This listing is not a SERVICE_CALL binding. Call resolveApiOperation with serviceCallId"
          + " to record the match.";

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
   * Typed catalog system search by name, id, or description substring. Blank queries yield an empty
   * list (the JSON tool path still returns INVALID_ARGUMENT).
   *
   * <p>Matches only what the reader typed. Binding resolution does not come through here — it
   * narrows through {@code CatalogSystemFinder}, which filters on operation paths, specification
   * groups, and protocol, and so finds services whose imported name reads nothing like the one the
   * reader would write.
   */
  public List<CatalogRestClient.SystemDto> searchCatalogSystems(String searchCondition) {
    String q = CatalogStrings.blankToNull(searchCondition);
    if (q == null) {
      return List.of();
    }
    List<CatalogRestClient.SystemDto> systems =
        catalogRestClient.searchSystems(new CatalogSystemSearchRequest(q));
    operationsLookup.rememberSystems(systems);
    return systems == null ? List.of() : List.copyOf(systems);
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
        CatalogSystemToolNames.SEARCH,
        LISTING_IS_NOT_A_BINDING,
        searchCatalogSystems(searchCondition));
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
    return support.catalogToolSuccess(
        CatalogSystemToolNames.SPECS, LISTING_IS_NOT_A_BINDING, specs);
  }

  /** Typed operations lookup for catalog-first binding resolution. Tools use this MDC form. */
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

  /**
   * Same listing keyed by {@code conversationId} so execution can revalidate after a worker hop
   * without reading MDC.
   */
  public List<CatalogRestClient.OperationDto> listCatalogOperations(
      String conversationId, String specificationId, String systemId, String searchFilter) {
    String mid = CatalogStrings.blankToNull(specificationId);
    if (mid == null) {
      return List.of();
    }
    String filter = CatalogStrings.blankToNull(searchFilter);
    List<CatalogRestClient.OperationDto> ops =
        filter != null
            ? operationsLookup.findOperations(conversationId, mid, systemId, filter)
            : operationsLookup.listOperations(conversationId, mid, systemId);
    return ops == null ? List.of() : List.copyOf(ops);
  }

  /**
   * The operation an element is already bound to, and the specification it belongs to.
   *
   * <p>The other lookups start from a name, which is what building a chain from a description has to
   * offer. Changing an element that already exists starts from the other end: the element carries an
   * operation id and nothing else -- no service name to search for, no specification id to list from
   * -- so without this the walk has no first step, and a model asked to change an operation can only
   * guess at what the id belongs to.
   */
  public String describeBoundOperationJson(String operationId) {
    String oid = CatalogStrings.percentDecode(operationId);
    if (oid == null) {
      return support.catalogToolError(
          CatalogSystemToolNames.BOUND,
          "operationId-required",
          "operationId is required",
          "Take it from the element's integrationOperationId property.");
    }
    CatalogRestClient.OperationDto operation =
        operationsLookup.findRememberedOperation(oid).orElse(null);
    if (operation == null) {
      try {
        operation = catalogRestClient.getOperation(oid);
      } catch (RuntimeException e) {
        return support.catalogToolError(
            CatalogSystemToolNames.BOUND,
            "operation-not-found",
            "No operation '" + oid + "' in the catalog",
            "The element points at an operation that no longer exists."
                + " Use searchCatalogSystems to find the service, then pick an operation.");
      }
    }
    if (operation == null || operation.modelId() == null) {
      return support.catalogToolError(
          CatalogSystemToolNames.BOUND,
          "operation-without-specification",
          "Operation '" + oid + "' names no specification",
          "Use searchCatalogSystems to find the service, then pick an operation.");
    }
    // The specification id is the handle every other operation of the same service hangs off, so it
    // is the one thing worth saying loudly: listCatalogOperations on it answers "what else is here".
    return support.catalogToolSuccess(
        CatalogSystemToolNames.BOUND,
        "Call listCatalogOperations with specificationId="
            + operation.modelId()
            + " to see the other operations of the same specification.",
        operation);
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
    return support.catalogToolSuccess(
        CatalogSystemToolNames.OPS, LISTING_IS_NOT_A_BINDING, ops);
  }
}
