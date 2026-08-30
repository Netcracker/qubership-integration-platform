package org.qubership.integration.platform.ai.integration.catalog.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainLabel;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemFilter;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest;

import java.util.List;
import java.util.Map;

/**
 * MicroProfile REST Client for the QIP Catalog REST API (runtime-catalog).
 *
 * <p>Paths use the container-native prefix {@code /v1/...} (no {@code /api}) — this matches {@code
 * ChainController}, {@code SystemController}, etc. When {@code CATALOG_URL} points at the platform
 * gateway, use a URL whose routing strips {@code /api/...} to {@code /v1/...}; when it points at
 * runtime-catalog directly, {@code http://host:8080} + {@code /v1/chains} is correct.
 */
@RegisterRestClient(configKey = "catalog-api")
@RegisterProvider(CatalogOutboundLoggingFilter.class)
@RegisterProvider(CatalogResponseExceptionMapper.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timeout(value = 2, unit = ChronoUnit.SECONDS)
@Retry(
    maxRetries = 2,
    delay = 200,
    delayUnit = ChronoUnit.MILLIS,
    retryOn = {
      ProcessingException.class,
      ConnectException.class,
      SocketTimeoutException.class,
      HttpTimeoutException.class,
      WebApplicationException.class
    },
    abortOn = CatalogNonRetryableResponseException.class)
@CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 2, delayUnit = ChronoUnit.SECONDS)
public interface CatalogRestClient {

  // ── Chains ───────────────────────────────────────────────────────────────

  @POST
  @Path("/v1/chains")
  ChainDto createChain(CatalogCreateChainRequest body);

  @GET
  @Path("/v1/chains/{chainId}")
  ChainDto getChain(@PathParam("chainId") String chainId);

  @DELETE
  @Path("/v1/chains/{chainId}")
  void deleteChain(@PathParam("chainId") String chainId);

  @POST
  @Path("/v1/folders/search")
  List<FolderItemDto> searchFolderItems(CatalogChainSearchRequest request);

  // ── Snapshots ────────────────────────────────────────────────────────────

  /**
   * Builds a snapshot of the chain as it stands.
   *
   * <p>This is the only way back from a change this service cannot undo on its own: reverting a
   * snapshot restores elements with their original ids, which is what a deleted element otherwise
   * loses forever.
   *
   * <p>Fails with 400 when the chain does not pass the catalog's own property verification, so a
   * half-configured chain cannot be snapshotted at all. Building one also moves the chain's
   * {@code currentSnapshot} pointer to the new snapshot.
   */
  @POST
  @Path("/v1/catalog/chains/{chainId}/snapshots")
  SnapshotDto createSnapshot(@PathParam("chainId") String chainId);

  @GET
  @Path("/v1/catalog/chains/{chainId}/snapshots")
  List<SnapshotDto> listSnapshots(@PathParam("chainId") String chainId);

  // ── Deployments (classic catalog paths) ─────────────────────────────────

  @POST
  @Path("/v1/catalog/chains/{chainId}/deployments")
  DeploymentDto createDeployment(
      @PathParam("chainId") String chainId, CreateDeploymentRequest body);

  @GET
  @Path("/v1/catalog/chains/{chainId}/deployments")
  List<DeploymentDto> listDeployments(@PathParam("chainId") String chainId);

  @DELETE
  @Path("/v1/catalog/chains/{chainId}/deployments/{deploymentId}")
  void deleteDeployment(
      @PathParam("chainId") String chainId, @PathParam("deploymentId") String deploymentId);

  // ── Domains ──────────────────────────────────────────────────────────────

  @GET
  @Path("/v1/catalog/domains")
  List<DomainDto> listDomains();

  // ── Element library ──────────────────────────────────────────────────────

  @GET
  @Path("/v1/library/{name}")
  CatalogElementDescriptorDto getLibraryElement(@PathParam("name") String name);

  // ── Elements ─────────────────────────────────────────────────────────────

  @POST
  @Path("/v1/chains/{chainId}/elements")
  ChainDiffDto createElement(
      @PathParam("chainId") String chainId, CatalogCreateElementRequest body);

  @PATCH
  @Path("/v1/chains/{chainId}/elements/{elementId}")
  @Timeout(value = 10, unit = ChronoUnit.SECONDS)
  @Retry(maxRetries = 0)
  ChainDiffDto updateElement(
      @PathParam("chainId") String chainId,
      @PathParam("elementId") String elementId,
      Map<String, Object> body);

  /**
   * Deletes elements in one catalog transaction, cascading to each one's descendants and to every
   * dependency attached to them.
   *
   * <p>The bulk form, not the per-id one: the catalog applies it atomically, which is what keeps a
   * multi-element removal from half-landing. Pass only the roots of what is being removed -- the
   * cascade takes the rest.
   */
  @DELETE
  @Path("/v1/chains/{chainId}/elements")
  ChainDiffDto deleteElements(
      @PathParam("chainId") String chainId, @QueryParam("elementsIds") List<String> elementsIds);

  @GET
  @Path("/v1/chains/{chainId}/elements")
  List<CatalogElementResponseDto> listElements(@PathParam("chainId") String chainId);

  @GET
  @Path("/v1/chains/{chainId}/elements/{elementId}")
  CatalogElementResponseDto getElement(
      @PathParam("chainId") String chainId, @PathParam("elementId") String elementId);

  @POST
  @Path("/v1/chains/{chainId}/elements/transfer")
  ChainDiffDto transferElements(
      @PathParam("chainId") String chainId, CatalogTransferElementsRequest body);

  // ── Dependencies (connections between elements) ─────────────────────────

  @POST
  @Path("/v1/chains/{chainId}/dependencies")
  ChainDiffDto createConnection(
      @PathParam("chainId") String chainId, CatalogCreateDependencyRequest body);

  @GET
  @Path("/v1/chains/{chainId}/dependencies")
  List<CatalogDependencyDto> listDependencies(@PathParam("chainId") String chainId);

  /** Deletes connections in one catalog transaction. Bulk form, for the same reason as elements. */
  @DELETE
  @Path("/v1/chains/{chainId}/dependencies")
  ChainDiffDto deleteDependencies(
      @PathParam("chainId") String chainId,
      @QueryParam("dependenciesIds") List<String> dependenciesIds);

  // ── Systems / specifications / operations ────────────────────────────────

  @POST
  @Path("/v1/systems")
  SystemDto createSystem(CatalogCreateSystemRequest body);

  @POST
  @Path("/v1/systems/search")
  List<SystemDto> searchSystems(CatalogSystemSearchRequest body);

  /**
   * Narrows services by an AND-set of predicates, resolved in SQL. Unlike {@code /v1/systems/search}
   * this can filter on values below the service — {@code URL} joins through to operation paths —
   * so a lookup that knows an operation need not read every service to find it.
   */
  @POST
  @Path("/v1/systems/filter")
  List<SystemDto> filterSystems(List<CatalogSystemFilter> body);

  @GET
  @Path("/v1/systems/{systemId}")
  SystemDto getSystem(@PathParam("systemId") String systemId);

  @GET
  @Path("/v1/systems/{systemId}/environments")
  List<EnvironmentDto> getEnvironments(@PathParam("systemId") String systemId);

  @POST
  @Path("/v1/systems/{systemId}/environments")
  EnvironmentDto createEnvironment(
      @PathParam("systemId") String systemId, CatalogCreateEnvironmentRequest body);

  @GET
  @Path("/v1/models")
  List<SpecificationDto> getApiSpecifications(@QueryParam("systemId") String systemId);

  @GET
  @Path("/v1/models/{modelId}")
  SpecificationDto getModel(@PathParam("modelId") String modelId);

  @GET
  @Path("/v1/operations/{operationId}")
  OperationDto getOperation(@PathParam("operationId") String operationId);

  @GET
  @Path("/v1/operations")
  List<OperationDto> getOperations(
      @QueryParam("modelId") String modelId,
      @QueryParam("offset") int offset,
      @QueryParam("count") int count,
      @QueryParam("searchFilter") String searchFilter);

  // ── DTOs ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CurrentSnapshotDto(String id, String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChainDto(
      String id,
      String name,
      String description,
      CurrentSnapshotDto currentSnapshot,
      boolean unsavedChanges) {

    /** Keeps existing test and call sites on the three-field constructor. */
    public ChainDto(String id, String name, String description) {
      this(id, name, description, null, false);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record FolderItemDto(
      String id,
      String name,
      String description,
      String itemType,
      List<CatalogChainLabel> labels) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ElementSummaryDto(
      String id,
      String type,
      Map<String, Object> properties,
      String parentElementId,
      List<ElementSummaryDto> children) {

    /** Keeps existing test and call sites on the three-field constructor. */
    public ElementSummaryDto(String id, String type, Map<String, Object> properties) {
      this(id, type, properties, null, null);
    }

    public ElementSummaryDto {
      children = children == null ? List.of() : List.copyOf(children);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DependencySummaryDto(String id, String from, String to) {}

  /**
   * What a catalog mutation actually changed.
   *
   * <p>The removed lists matter for a delete: the catalog cascades, so what went is routinely more
   * than what was asked for, and reporting the request rather than the response would understate
   * the damage.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChainDiffDto(
      List<ElementSummaryDto> createdElements,
      List<ElementSummaryDto> updatedElements,
      List<DependencySummaryDto> createdDependencies,
      List<ElementSummaryDto> removedElements,
      List<DependencySummaryDto> removedDependencies) {

    /** Additive-only result, for the create and update paths that can never remove anything. */
    public ChainDiffDto(
        List<ElementSummaryDto> createdElements,
        List<ElementSummaryDto> updatedElements,
        List<DependencySummaryDto> createdDependencies) {
      this(createdElements, updatedElements, createdDependencies, List.of(), List.of());
    }

    public ChainDiffDto {
      removedElements = removedElements == null ? List.of() : List.copyOf(removedElements);
      removedDependencies =
          removedDependencies == null ? List.of() : List.copyOf(removedDependencies);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SnapshotDto(String id, String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CreateDeploymentRequest(String domain, String snapshotId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RuntimeStateDto(String status, String error) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DeploymentRuntimeDto(Map<String, RuntimeStateDto> states) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DeploymentDto(
      String id,
      String chainId,
      String snapshotId,
      String name,
      String domain,
      DeploymentRuntimeDto runtime) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DomainDto(String name, String type) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SystemDto(String id, String name, String type, String protocol) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record EnvironmentDto(String id, String name, String address) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpecificationDto(
      String id, String name, String specificationGroupId, String systemId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OperationDto(
      String id, String name, String method, String path, String modelId, JsonNode specification) {

    public OperationDto(String id, String name, String method, String path, String modelId) {
      this(id, name, method, path, modelId, null);
    }
  }
}
