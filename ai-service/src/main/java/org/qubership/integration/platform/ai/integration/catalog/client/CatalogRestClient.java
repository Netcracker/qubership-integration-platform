package org.qubership.integration.platform.ai.integration.catalog.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
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
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
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

  // ── Elements ─────────────────────────────────────────────────────────────

  @POST
  @Path("/v1/chains/{chainId}/elements")
  ChainDiffDto createElement(
      @PathParam("chainId") String chainId, CatalogCreateElementRequest body);

  @PATCH
  @Path("/v1/chains/{chainId}/elements/{elementId}")
  ChainDiffDto updateElement(
      @PathParam("chainId") String chainId,
      @PathParam("elementId") String elementId,
      Map<String, Object> body);

  @DELETE
  @Path("/v1/chains/{chainId}/elements/{elementId}")
  void deleteElement(
      @PathParam("chainId") String chainId, @PathParam("elementId") String elementId);

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

  // ── Systems / specifications / operations ────────────────────────────────

  @POST
  @Path("/v1/systems")
  SystemDto createSystem(CatalogCreateSystemRequest body);

  @POST
  @Path("/v1/systems/search")
  List<SystemDto> searchSystems(CatalogSystemSearchRequest body);

  @GET
  @Path("/v1/systems/{systemId}")
  SystemDto getSystem(@PathParam("systemId") String systemId);

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

  // ── APIHub import (legacy path — may not exist on all deployments) ─────────

  @POST
  @Path("/v1/systems/{systemId}/specifications/import")
  SpecificationDto importApiHubSpec(
      @PathParam("systemId") String systemId, Map<String, Object> body);

  // ── DTOs ─────────────────────────────────────────────────────────────────

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChainDto(String id, String name, String description) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ElementSummaryDto(String id, String type, Map<String, Object> properties) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record DependencySummaryDto(String id, String from, String to) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChainDiffDto(
      List<ElementSummaryDto> createdElements,
      List<ElementSummaryDto> updatedElements,
      List<DependencySummaryDto> createdDependencies) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SystemDto(String id, String name, String type, String protocol) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpecificationDto(
      String id, String name, String specificationGroupId, String systemId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OperationDto(String id, String name, String method, String path, String modelId) {}
}
