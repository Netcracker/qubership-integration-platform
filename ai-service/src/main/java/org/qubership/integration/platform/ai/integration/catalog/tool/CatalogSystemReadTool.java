package org.qubership.integration.platform.ai.integration.catalog.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.integration.platform.ai.chat.ToolSession;
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

  static final int MAX_OPERATION_LIST_CHARS = 96_000;

  static final String LISTING_IS_NOT_A_BINDING =
      "This listing does not bind an interaction. Call resolveApiOperation with the interactionId"
          + " from the stored RequirementFlow to record the match.";

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
    return searchCatalogSystems(ToolSession.resolveConversationId(), searchCondition);
  }

  public List<CatalogRestClient.SystemDto> searchCatalogSystems(String conversationId, String searchCondition) {
    String q = CatalogStrings.blankToNull(searchCondition);
    if (q == null) {
      return List.of();
    }
    List<CatalogRestClient.SystemDto> systems =
        catalogRestClient.searchSystems(new CatalogSystemSearchRequest(q));
    operationsLookup.rememberSystems(conversationId, systems);
    return systems == null ? List.of() : List.copyOf(systems);
  }

  public String searchCatalogSystemsJson(String searchCondition) {
    return searchCatalogSystemsJson(ToolSession.resolveConversationId(), searchCondition);
  }

  public String searchCatalogSystemsJson(String conversationId, String searchCondition) {
    if (conversationId == null || conversationId.isBlank()) {
      return missingContext(CatalogSystemToolNames.SEARCH);
    }
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
        searchCatalogSystems(conversationId, searchCondition));
  }

  /**
   * Loads one catalog system by id. Blank ids and a missing system yield empty; this does not
   * search by name.
   */
  public Optional<CatalogRestClient.SystemDto> getCatalogSystem(String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid == null) {
      return Optional.empty();
    }
    try {
      CatalogRestClient.SystemDto system = catalogRestClient.getSystem(sid);
      if (system != null) {
        operationsLookup.rememberSystems(List.of(system));
      }
      return Optional.ofNullable(system);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Typed specifications lookup for catalog-first binding resolution. */
  public List<CatalogRestClient.SpecificationDto> getApiSpecifications(String systemId) {
    return getApiSpecifications(ToolSession.resolveConversationId(), systemId);
  }

  public List<CatalogRestClient.SpecificationDto> getApiSpecifications(String conversationId, String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid == null) {
      return List.of();
    }
    operationsLookup.rememberActiveSystemId(conversationId, sid);
    List<CatalogRestClient.SpecificationDto> specs = catalogRestClient.getApiSpecifications(sid);
    operationsLookup.rememberSpecificationsForSystem(conversationId, sid, specs);
    return specs == null ? List.of() : List.copyOf(specs);
  }

  public String getApiSpecificationsJson(String systemId) {
    return getApiSpecificationsJson(ToolSession.resolveConversationId(), systemId);
  }

  public String getApiSpecificationsJson(String conversationId, String systemId) {
    if (conversationId == null || conversationId.isBlank()) {
      return missingContext(CatalogSystemToolNames.SPECS);
    }
    Optional<CatalogToolResult.ErrorSpec> validationError =
        CatalogLookupArgsValidator.validateSystemIdForSpecifications(systemId);
    if (validationError.isPresent()) {
      return support.catalogToolError(CatalogSystemToolNames.SPECS, validationError.get());
    }
    String sid = CatalogStrings.blankToNull(systemId);
    List<CatalogRestClient.SpecificationDto> specs = getApiSpecifications(conversationId, sid);
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
    return describeBoundOperationJson(ToolSession.resolveConversationId(), operationId);
  }

  public String describeBoundOperationJson(String conversationId, String operationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return missingContext(CatalogSystemToolNames.BOUND);
    }
    String oid = CatalogStrings.percentDecode(operationId);
    if (oid == null) {
      return support.catalogToolError(
          CatalogSystemToolNames.BOUND,
          "operationId-required",
          "operationId is required",
          "Take it from the element's integrationOperationId property.");
    }
    CatalogRestClient.OperationDto operation =
        operationsLookup.findRememberedOperation(conversationId, oid).orElse(null);
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
        operationSummary(operation));
  }

  public String listCatalogOperationsJson(
      String specificationId, String systemId, String searchFilter) {
    return listCatalogOperationsJson(ToolSession.resolveConversationId(), specificationId, systemId, searchFilter);
  }

  public String listCatalogOperationsJson(
      String conversationId, String specificationId, String systemId, String searchFilter) {
    return listCatalogOperationsJson(conversationId, specificationId, systemId, searchFilter, 0);
  }

  public String listCatalogOperationsJson(
      String conversationId, String specificationId, String systemId, String searchFilter,
      Integer offset) {
    if (conversationId == null || conversationId.isBlank()) {
      return missingContext(CatalogSystemToolNames.OPS);
    }
    Optional<CatalogToolResult.ErrorSpec> validationError =
        operationsLookup.validateSpecificationIdForOperations(conversationId, specificationId);
    if (validationError.isPresent()) {
      return support.catalogToolError(CatalogSystemToolNames.OPS, validationError.get());
    }
    List<CatalogRestClient.OperationDto> ops =
        listCatalogOperations(conversationId, specificationId, systemId, searchFilter);
    int start = offset == null ? 0 : offset;
    if (start < 0 || start > ops.size()) {
      return support.catalogToolError(CatalogSystemToolNames.OPS,
          CatalogToolResult.CODE_INVALID_ARGUMENT, "offset is outside the operation list.",
          "Use nextOffset from the previous response, or 0 for a new listing.");
    }
    ObjectNode result = support.createObjectNode();
    result.put("ok", true);
    result.put("tool", CatalogSystemToolNames.OPS);
    result.put("message", LISTING_IS_NOT_A_BINDING);
    result.put("total", ops.size());
    var data = result.putArray("data");
    int size = result.toString().length() + 512;
    int next = start;
    for (; next < ops.size(); next++) {
      ObjectNode summary = operationSummary(ops.get(next));
      int rowSize = summary.toString().length() + 1;
      if (size + rowSize > MAX_OPERATION_LIST_CHARS) {
        if (data.isEmpty()) {
          return support.catalogToolError(CatalogSystemToolNames.OPS, "CATALOG_METADATA_TOO_LARGE",
              "Operation selection metadata exceeds the listing limit.",
              "Call resolveApiOperation with the stored interactionId; it searches the complete catalog.");
        }
        break;
      }
      data.add(summary);
      size += rowSize;
    }
    result.put("complete", next == ops.size());
    if (next < ops.size()) {
      result.put("nextOffset", next);
      result.put("continuation", "Repeat this listing with the same filters and nextOffset as offset."
          + " resolveApiOperation searches all candidates without paging.");
    }
    return result.toString();
  }

  private ObjectNode operationSummary(CatalogRestClient.OperationDto operation) {
    ObjectNode summary = support.createObjectNode();
    summary.put("id", operation.id());
    summary.put("name", operation.name());
    summary.put("method", operation.method());
    summary.put("path", operation.path());
    summary.put("modelId", operation.modelId());
    return summary;
  }

  private String missingContext(String tool) {
    return support.catalogToolError(tool, "CONVERSATION_CONTEXT_REQUIRED",
        "Catalog lookup has no conversation identity.",
        "Restore the active conversation before calling this tool again.");
  }
}
