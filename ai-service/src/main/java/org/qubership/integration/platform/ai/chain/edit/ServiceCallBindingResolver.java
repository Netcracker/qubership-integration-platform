package org.qubership.integration.platform.ai.chain.edit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubSearchHitParser;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ApiHubSpecificationImportResult;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/**
 * Finds the catalog operation a reader means, starting from the one the element already has.
 *
 * <p>A reader who says "change the operation" cannot answer "which id?" — they do not know the ids.
 * The element carries its current operation, which names a specification, which names a service,
 * and that bounds the search to the operations the same service already offers. Only when nothing
 * there fits does the search widen to service names.
 *
 * <p>Every field of the result is read from the catalog. The catalog refuses a service-call whose
 * operation id, method and path disagree, so a binding assembled from a model's memory of any one
 * of them is worse than no binding at all.
 */
@ApplicationScoped
public class ServiceCallBindingResolver {

  /** Property the catalog stores an element's current operation under. */
  public static final String OPERATION_ID_PROPERTY = "integrationOperationId";

  private static final int MAX_CANDIDATES = 10;

  private final CatalogRestClient catalogRestClient;
  private final CatalogSystemReadTool readTool;
  private final ApiHubMcpTools apiHub;

  @Inject
  public ServiceCallBindingResolver(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogSystemReadTool readTool,
      ApiHubMcpTools apiHub) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
    this.readTool = Objects.requireNonNull(readTool, "readTool");
    this.apiHub = apiHub;
  }

  public ServiceCallBindingOutcome resolve(ChainPlanNode target, String query) {
    Objects.requireNonNull(target, "target");
    String filter = query == null ? "" : query.trim();
    List<Candidate> candidates = withinCurrentSpecification(target, filter);
    if (candidates.isEmpty()) {
      candidates = acrossServices(filter);
    }
    if (candidates.isEmpty()) {
      return outsideTheLocalCatalog(filter);
    }
    if (candidates.size() > 1) {
      return new ServiceCallBindingOutcome.Ambiguous(
          "Several operations match. Which one do you mean?",
          candidates.stream().limit(MAX_CANDIDATES).map(Candidate::describe).toList());
    }
    return new ServiceCallBindingOutcome.Resolved(candidates.get(0).toBinding(target.nodeId()));
  }

  /**
   * What to say when the local catalog has nothing.
   *
   * <p>APIHub is asked, but only to name what an import would bring in. Importing a specification
   * creates catalog artifacts the reader did not ask for, so it waits for them to say so.
   */
  private ServiceCallBindingOutcome outsideTheLocalCatalog(String filter) {
    String plainMiss =
        "No operation in the local catalog matches '"
            + filter
            + "'. Name the service and the operation, or import the specification first.";
    if (apiHub == null || filter.isBlank()) {
      return new ServiceCallBindingOutcome.NotFound(plainMiss);
    }
    ApiHubRequirementRefs refs;
    try {
      refs =
          ApiHubSearchHitParser.parseImportCandidate(
              apiHub.searchApiOperations(
                  filter, ApiHubRequirementRefs.DEFAULT_API_TYPE, null, 0, 100, null),
              ApiHubRequirementRefs.DEFAULT_API_TYPE,
              null);
    } catch (RuntimeException e) {
      return new ServiceCallBindingOutcome.NotFound(plainMiss);
    }
    if (refs == null || !refs.hasImportableRefs()) {
      return new ServiceCallBindingOutcome.NotFound(plainMiss);
    }
    return new ServiceCallBindingOutcome.EscalationRequired(
        "'"
            + filter
            + "' is not in the local catalog. APIHub has "
            + refs.specificationGroupName()
            + " in package "
            + refs.packageId()
            + " at "
            + refs.version()
            + ". Importing it adds a service and a specification to the catalog.",
        refs);
  }

  /**
   * The complete binding for an operation that has just been imported.
   *
   * <p>The import answers with catalog ids; method, path, type and protocol are read back from the
   * catalog rather than carried over from the APIHub hit, so the element describes what the catalog
   * actually stored. An import that produced no operation id is reported as incomplete instead of
   * being filled in.
   */
  public ServiceCallBindingOutcome fromImport(
      String targetNodeId, ApiHubSpecificationImportResult result, String release) {
    if (result == null || result.catalogOperationId().isEmpty()) {
      return new ServiceCallBindingOutcome.NotFound(
          "The import finished without naming an operation, so there is nothing to bind to.");
    }
    String operationId = result.catalogOperationId().orElseThrow();
    CatalogRestClient.OperationDto operation;
    try {
      operation = catalogRestClient.getOperation(operationId);
    } catch (RuntimeException e) {
      operation = null;
    }
    CatalogRestClient.SystemDto system = system(result.systemId());
    if (operation == null || system == null) {
      return new ServiceCallBindingOutcome.NotFound(
          "The imported specification does not describe operation '"
              + operationId
              + "' completely, so nothing was changed.");
    }
    return new ServiceCallBindingOutcome.Resolved(
        new ResolvedServiceCallBinding(
            targetNodeId,
            Candidate.blankToDash(system.type()),
            result.systemId(),
            result.specificationGroupId(),
            result.specificationId(),
            operationId,
            Candidate.blankToDash(system.protocol()),
            Candidate.blankToDash(operation.method()),
            Candidate.blankToDash(operation.path()),
            operation.name(),
            ResolvedServiceCallBinding.Source.APIHUB_IMPORT,
            release,
            "apihub-import:" + result.importId()));
  }

  /** The operation an element is bound to right now, if it has one. */
  public static String currentOperationId(ChainPlanNode node) {
    if (node == null || node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && OPERATION_ID_PROPERTY.equals(property.key())) {
        return property.value() == null || property.value().isBlank() ? null : property.value();
      }
    }
    return null;
  }

  private List<Candidate> withinCurrentSpecification(ChainPlanNode target, String filter) {
    String operationId = currentOperationId(target);
    if (operationId == null) {
      return List.of();
    }
    CatalogRestClient.OperationDto current;
    try {
      current = catalogRestClient.getOperation(operationId);
    } catch (RuntimeException e) {
      return List.of();
    }
    if (current == null || current.modelId() == null) {
      return List.of();
    }
    CatalogRestClient.SpecificationDto specification = specification(current.modelId());
    if (specification == null || specification.systemId() == null) {
      return List.of();
    }
    CatalogRestClient.SystemDto system = system(specification.systemId());
    if (system == null) {
      return List.of();
    }
    return match(specification, system, filter, operationId);
  }

  private List<Candidate> acrossServices(String filter) {
    if (filter.isBlank()) {
      return List.of();
    }
    List<Candidate> found = new ArrayList<>();
    LinkedHashSet<String> seenOperationIds = new LinkedHashSet<>();
    for (CatalogRestClient.SystemDto system : readTool.searchCatalogSystems(filter)) {
      if (system == null || system.id() == null) {
        continue;
      }
      for (CatalogRestClient.SpecificationDto specification :
          readTool.getApiSpecifications(system.id())) {
        if (specification == null || specification.id() == null) {
          continue;
        }
        for (Candidate candidate : match(specification, system, filter, null)) {
          if (seenOperationIds.add(candidate.operation().id())) {
            found.add(candidate);
          }
        }
      }
    }
    return found;
  }

  /**
   * Operations of one specification that match the reader's words. The operation the element
   * already points at is excluded: offering it back as a choice answers nothing.
   */
  private List<Candidate> match(
      CatalogRestClient.SpecificationDto specification,
      CatalogRestClient.SystemDto system,
      String filter,
      String excludedOperationId) {
    List<CatalogRestClient.OperationDto> operations =
        readTool.listCatalogOperations(
            specification.id(), system.id(), filter.isBlank() ? null : filter);
    List<Candidate> candidates = new ArrayList<>();
    for (CatalogRestClient.OperationDto operation : operations) {
      if (operation == null || operation.id() == null || operation.id().equals(excludedOperationId)) {
        continue;
      }
      candidates.add(new Candidate(system, specification, operation));
    }
    return candidates;
  }

  private CatalogRestClient.SpecificationDto specification(String modelId) {
    try {
      return catalogRestClient.getModel(modelId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private CatalogRestClient.SystemDto system(String systemId) {
    try {
      return catalogRestClient.getSystem(systemId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private record Candidate(
      CatalogRestClient.SystemDto system,
      CatalogRestClient.SpecificationDto specification,
      CatalogRestClient.OperationDto operation) {

    String describe() {
      return operation.name()
          + " ("
          + blankToDash(operation.method())
          + " "
          + blankToDash(operation.path())
          + ") in "
          + system.name();
    }

    ResolvedServiceCallBinding toBinding(String targetNodeId) {
      return new ResolvedServiceCallBinding(
          targetNodeId,
          blankToDash(system.type()),
          system.id(),
          specification.specificationGroupId(),
          specification.id(),
          operation.id(),
          blankToDash(system.protocol()),
          blankToDash(operation.method()),
          blankToDash(operation.path()),
          operation.name(),
          ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
          "",
          "catalog:/v1/operations/" + operation.id());
    }

    static String blankToDash(String value) {
      return value == null || value.isBlank() ? "-" : value.trim();
    }
  }
}
