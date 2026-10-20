package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

@ApplicationScoped
class CatalogOperationSchemaLoader implements OperationSchemaLoader {

  static final String SCHEMA_VERSION = "1";
  static final String PRODUCER_ID = "catalog-operation-schema-loader";
  static final String PRODUCER_VERSION = "1";
  private static final String JSON_CONTENT_TYPE = "application/json";

  private final CatalogRestClient catalogRestClient;
  private final CompilationArtifacts artifacts;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<String, OperationSchemaMaps> cache = new ConcurrentHashMap<>();

  @Inject
  CatalogOperationSchemaLoader(
      @RestClient CatalogRestClient catalogRestClient,
      CompilationArtifacts artifacts,
      ObjectMapper objectMapper) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.objectMapper = canonicalMapper(Objects.requireNonNull(objectMapper, "objectMapper"));
  }

  @Override
  public OperationSchemaMaps load(String operationId) {
    String opId = requireText(operationId, "operationId");
    return cache.computeIfAbsent(opId, this::fetchSchemas);
  }

  @Override
  public MappingSchemaSide persistRequest(
      String compilationId, String serviceCallId, String operationId, String contentType) {
    return persist(
        compilationId,
        serviceCallId,
        operationId,
        MappingPort.REQUEST,
        contentType,
        null,
        load(operationId).requestByContentType().get(contentType));
  }

  @Override
  public MappingSchemaSide persistResponse(
      String compilationId,
      String serviceCallId,
      String operationId,
      String contentType,
      String responseCode) {
    String status = requireText(responseCode, "responseCode");
    Map<String, JsonNode> byContentType =
        load(operationId).responseByStatusThenContentType().get(status);
    JsonNode schema = byContentType == null ? null : byContentType.get(contentType);
    return persist(
        compilationId,
        serviceCallId,
        operationId,
        MappingPort.RESPONSE,
        contentType,
        status,
        schema);
  }

  private MappingSchemaSide persist(
      String compilationId,
      String serviceCallId,
      String operationId,
      MappingPort direction,
      String contentType,
      String responseCode,
      JsonNode schema) {
    requireText(compilationId, "compilationId");
    requireText(serviceCallId, "serviceCallId");
    requireText(operationId, "operationId");
    requireText(contentType, "contentType");
    if (schema == null || schema.isNull()) {
      StringBuilder message =
          new StringBuilder("No ")
              .append(direction.name())
              .append(" schema for operation ")
              .append(operationId)
              .append(" contentType=")
              .append(contentType);
      if (direction == MappingPort.RESPONSE) {
        message.append(" responseCode=").append(responseCode);
      }
      throw new IllegalStateException(message.toString());
    }
    String sha256 = sha256(write(schema));
    String provenance = provenance(operationId, direction, contentType, responseCode);
    MappingSchemaSide side =
        new MappingSchemaSide(
            SCHEMA_VERSION,
            serviceCallId,
            operationId,
            direction,
            contentType,
            responseCode,
            sha256,
            provenance,
            schema);
    artifacts.append(
        new AppendCommand(
            compilationId,
            Kind.MAPPING_SCHEMA_SIDE,
            SCHEMA_VERSION,
            PRODUCER_ID,
            PRODUCER_VERSION,
            side,
            List.of(),
            null));
    return side;
  }

  private OperationSchemaMaps fetchSchemas(String operationId) {
    CatalogRestClient.OperationSchemaMapsDto dto =
        catalogRestClient.getOperationSchemas(operationId, "full");
    Map<String, JsonNode> requestByContentType = new LinkedHashMap<>();
    if (dto.requestSchema() != null) {
      for (Map.Entry<String, JsonNode> entry : dto.requestSchema().entrySet()) {
        if (!"parameters".equals(entry.getKey())) {
          requestByContentType.put(entry.getKey(), entry.getValue());
        }
      }
    }
    Map<String, Map<String, JsonNode>> responseByStatus = new LinkedHashMap<>();
    if (dto.responseSchemas() != null) {
      for (Map.Entry<String, JsonNode> entry : dto.responseSchemas().entrySet()) {
        responseByStatus.put(entry.getKey(), parseResponseContentTypes(entry.getValue()));
      }
    }
    if (requestByContentType.isEmpty()) {
      JsonNode asyncRequest = asyncPayloadRequest(responseByStatus);
      if (asyncRequest != null) {
        requestByContentType.put(JSON_CONTENT_TYPE, asyncRequest);
      }
    }
    return new OperationSchemaMaps(
        operationId, Map.copyOf(requestByContentType), Map.copyOf(responseByStatus));
  }

  /**
   * Catalog AsyncAPI stores channel messages as flat JSON Schema under {@code responseSchemas},
   * with an empty request map. Mapping treats that payload as the operation body (trigger output
   * or publish request). HTTP statuses are left alone so GET operations keep an empty request.
   */
  private JsonNode asyncPayloadRequest(Map<String, Map<String, JsonNode>> responseByStatus) {
    if (responseByStatus == null || responseByStatus.isEmpty()) {
      return null;
    }
    List<JsonNode> messages = new ArrayList<>();
    for (Map.Entry<String, Map<String, JsonNode>> entry : responseByStatus.entrySet()) {
      if (looksLikeHttpStatus(entry.getKey())) {
        return null;
      }
      JsonNode schema = entry.getValue() == null ? null : entry.getValue().get(JSON_CONTENT_TYPE);
      if (schema == null || schema.isNull()) {
        return null;
      }
      messages.add(schema);
    }
    if (messages.size() == 1) {
      return messages.getFirst();
    }
    var root = objectMapper.createObjectNode();
    var oneOf = objectMapper.createArrayNode();
    for (JsonNode message : messages) {
      oneOf.add(message);
    }
    root.set("oneOf", oneOf);
    return root;
  }

  private static Map<String, JsonNode> parseResponseContentTypes(JsonNode node) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (looksLikeJsonSchema(node)) {
      return Map.of(JSON_CONTENT_TYPE, node);
    }
    if (!node.isObject()) {
      return Map.of();
    }
    Map<String, JsonNode> byContentType = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (!"parameters".equals(field.getKey())) {
        byContentType.put(field.getKey(), field.getValue());
      }
    }
    return Map.copyOf(byContentType);
  }

  private static boolean looksLikeHttpStatus(String key) {
    if (key == null) {
      return false;
    }
    if ("default".equals(key)) {
      return true;
    }
    if (key.length() == 3
        && Character.isDigit(key.charAt(0))
        && Character.isDigit(key.charAt(1))
        && Character.isDigit(key.charAt(2))) {
      return true;
    }
    return key.matches("^[1-5]XX$");
  }

  private static boolean looksLikeJsonSchema(JsonNode node) {
    return node.has("type") || node.has("$ref") || node.has("properties") || node.has("items");
  }

  private static String provenance(
      String operationId, MappingPort direction, String contentType, String responseCode) {
  return direction == MappingPort.REQUEST
        ? "GET /v1/operations/"
            + operationId
            + "/schemas/request?contentType="
            + contentType
        : "GET /v1/operations/"
            + operationId
            + "/schemas/response?contentType="
            + contentType
            + "&responseCode="
            + responseCode;
  }

  private byte[] write(JsonNode schema) {
    try {
      return objectMapper.writeValueAsBytes(schema);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize operation schema", e);
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static ObjectMapper canonicalMapper(ObjectMapper source) {
    return source.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }
}
