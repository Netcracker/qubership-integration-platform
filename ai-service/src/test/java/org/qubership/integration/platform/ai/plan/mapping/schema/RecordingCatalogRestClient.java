package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;

/** Test fake that records catalog REST calls and returns one JSON schema map per operation. */
public final class RecordingCatalogRestClient implements CatalogRestClient {

  private final Map<String, CatalogRestClient.OperationSchemaMapsDto> schemasByOperation;
  private final List<String> calls = new ArrayList<>();

  private RecordingCatalogRestClient(
      Map<String, CatalogRestClient.OperationSchemaMapsDto> schemasByOperation) {
    this.schemasByOperation = schemasByOperation;
  }

  public static RecordingCatalogRestClient withJsonMaps() {
    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode requestSchema =
          mapper.readTree(
              """
              {
                "type": "object",
                "properties": { "orderId": { "type": "string" } },
                "required": ["orderId"]
              }
              """);
      JsonNode responseSchema =
          mapper.readTree(
              """
              {
                "type": "object",
                "properties": { "status": { "type": "string" } }
              }
              """);
      Map<String, JsonNode> request = new LinkedHashMap<>();
      request.put("application/json", requestSchema);
      Map<String, JsonNode> responseByStatus = new LinkedHashMap<>();
      responseByStatus.put("application/json", responseSchema);
      Map<String, JsonNode> responseSchemas = new LinkedHashMap<>();
      responseSchemas.put("201", mapper.valueToTree(responseByStatus));
      CatalogRestClient.OperationSchemaMapsDto dto =
          new CatalogRestClient.OperationSchemaMapsDto("op-1", request, responseSchemas);
      return new RecordingCatalogRestClient(Map.of("op-1", dto));
    } catch (Exception e) {
      throw new IllegalStateException("cannot build schema fixture", e);
    }
  }

  /** Async-style response: schema node directly under status key, not under a media type. */
  public static RecordingCatalogRestClient withFlatAsyncResponseSchema() {
    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode requestSchema =
          mapper.readTree(
              """
              {
                "type": "object",
                "properties": { "orderId": { "type": "string" } },
                "required": ["orderId"]
              }
              """);
      JsonNode flatResponseSchema =
          mapper.readTree(
              """
              {
                "type": "object",
                "properties": { "payload": { "type": "string" } }
              }
              """);
      Map<String, JsonNode> request = new LinkedHashMap<>();
      request.put("application/json", requestSchema);
      Map<String, JsonNode> responseSchemas = new LinkedHashMap<>();
      responseSchemas.put("message", flatResponseSchema);
      CatalogRestClient.OperationSchemaMapsDto dto =
          new CatalogRestClient.OperationSchemaMapsDto("op-1", request, responseSchemas);
      return new RecordingCatalogRestClient(Map.of("op-1", dto));
    } catch (Exception e) {
      throw new IllegalStateException("cannot build flat async schema fixture", e);
    }
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  private void record(String call) {
    calls.add(call);
  }

  @Override
  public CatalogRestClient.OperationSchemaMapsDto getOperationSchemas(
      String operationId, String mode) {
    record("getOperationSchemas:" + operationId + ":" + mode);
    CatalogRestClient.OperationSchemaMapsDto dto = schemasByOperation.get(operationId);
    if (dto == null) {
      throw new IllegalArgumentException("unknown operation " + operationId);
    }
    return dto;
  }

  @Override
  public JsonNode getOperationRequestSchema(String operationId, String contentType) {
    record("getOperationRequestSchema:" + operationId + ":" + contentType);
    throw new UnsupportedOperationException("getOperationRequestSchema");
  }

  @Override
  public JsonNode getOperationResponseSchema(
      String operationId, String contentType, String responseCode) {
    record(
        "getOperationResponseSchema:" + operationId + ":" + contentType + ":" + responseCode);
    throw new UnsupportedOperationException("getOperationResponseSchema");
  }

  @Override
  public List<SystemDto> searchSystems(CatalogSystemSearchRequest body) {
    record("searchSystems:" + Objects.requireNonNull(body).searchCondition());
    return List.of(new SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http"));
  }

  @Override
  public List<SpecificationDto> getApiSpecifications(String systemId) {
    record("getApiSpecifications:" + systemId);
    return List.of(new SpecificationDto("spec-1", "2024.4", "sg-1", systemId));
  }

  @Override
  public List<OperationDto> getOperations(
      String modelId, int offset, int count, String searchFilter) {
    record("getOperations:" + modelId);
    return List.of(new OperationDto("op-1", "findPets", "GET", "/pets", modelId));
  }

  private static UnsupportedOperationException unsupported(String method) {
    return new UnsupportedOperationException(method);
  }

  @Override
  public ChainDto createChain(
      org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest body) {
    throw unsupported("createChain");
  }

  @Override
  public ChainDto getChain(String chainId) {
    throw unsupported("getChain");
  }

  @Override
  public void deleteChain(String chainId) {
    throw unsupported("deleteChain");
  }

  @Override
  public List<FolderItemDto> searchFolderItems(
      org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest request) {
    throw unsupported("searchFolderItems");
  }

  @Override
  public SnapshotDto createSnapshot(String chainId) {
    throw unsupported("createSnapshot");
  }

  @Override
  public List<SnapshotDto> listSnapshots(String chainId) {
    throw unsupported("listSnapshots");
  }

  @Override
  public DeploymentDto createDeployment(String chainId, CreateDeploymentRequest body) {
    throw unsupported("createDeployment");
  }

  @Override
  public List<DeploymentDto> listDeployments(String chainId) {
    throw unsupported("listDeployments");
  }

  @Override
  public void deleteDeployment(String chainId, String deploymentId) {
    throw unsupported("deleteDeployment");
  }

  @Override
  public List<DomainDto> listDomains() {
    throw unsupported("listDomains");
  }

  @Override
  public org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorDto
      getLibraryElement(String name) {
    throw unsupported("getLibraryElement");
  }

  @Override
  public ChainDiffDto createElement(
      String chainId,
      org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest body) {
    throw unsupported("createElement");
  }

  @Override
  public ChainDiffDto updateElement(String chainId, String elementId, Map<String, Object> body) {
    throw unsupported("updateElement");
  }

  @Override
  public ChainDiffDto deleteElements(String chainId, List<String> elementsIds) {
    throw unsupported("deleteElements");
  }

  @Override
  public List<org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto>
      listElements(String chainId) {
    throw unsupported("listElements");
  }

  @Override
  public org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto getElement(
      String chainId, String elementId) {
    throw unsupported("getElement");
  }

  @Override
  public ChainDiffDto transferElements(
      String chainId,
      org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest body) {
    throw unsupported("transferElements");
  }

  @Override
  public ChainDiffDto createConnection(
      String chainId,
      org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest body) {
    throw unsupported("createConnection");
  }

  @Override
  public List<org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto>
      listDependencies(String chainId) {
    throw unsupported("listDependencies");
  }

  @Override
  public ChainDiffDto deleteDependencies(String chainId, List<String> dependenciesIds) {
    throw unsupported("deleteDependencies");
  }

  @Override
  public SystemDto createSystem(
      org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest body) {
    throw unsupported("createSystem");
  }

  @Override
  public List<SystemDto> filterSystems(
      List<org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemFilter> body) {
    throw unsupported("filterSystems");
  }

  @Override
  public SystemDto getSystem(String systemId) {
    throw unsupported("getSystem");
  }

  @Override
  public List<EnvironmentDto> getEnvironments(String systemId) {
    throw unsupported("getEnvironments");
  }

  @Override
  public EnvironmentDto createEnvironment(
      String systemId,
      org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest body) {
    throw unsupported("createEnvironment");
  }

  @Override
  public SpecificationDto getModel(String modelId) {
    throw unsupported("getModel");
  }

  @Override
  public OperationDto getOperation(String operationId) {
    throw unsupported("getOperation");
  }
}
