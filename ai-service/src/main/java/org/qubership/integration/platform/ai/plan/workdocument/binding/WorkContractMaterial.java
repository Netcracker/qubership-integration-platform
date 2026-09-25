package org.qubership.integration.platform.ai.plan.workdocument.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.OperationDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.OperationSchemaMapsDto;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.SpecificationDto;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding;

/**
 * Loads request, success, and failure schemas for one selected operation and pinned version.
 * Schema bodies come from {@link CatalogRestClient} reads. This type does not invent a body.
 */
public final class WorkContractMaterial {

  private static final String JSON_TYPE = "application/json";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final CatalogRestClient catalog;

  public WorkContractMaterial(CatalogRestClient catalog) {
    this.catalog = catalog;
  }

  /** Catalog path for one schema read. Query values use the same encoding as the host adapter. */
  public static String catalogPath(String method, String... args) {
    return switch (method) {
      case "getOperation" -> "/v1/operations/" + encode(args[0]);
      case "getModel" -> "/v1/models/" + encode(args[0]);
      case "getOperationSchemas" ->
          "/v1/operations/" + encode(args[0]) + "/schemas?mode=" + encode(args[1]);
      case "getOperationRequestSchema" ->
          "/v1/operations/" + encode(args[0]) + "/schemas/request?contentType=" + encode(args[1]);
      case "getOperationResponseSchema" ->
          "/v1/operations/"
              + encode(args[0])
              + "/schemas/response?contentType="
              + encode(args[1])
              + "&responseCode="
              + encode(args[2]);
      default -> throw new UnsupportedOperationException(method);
    };
  }

  public ContractMaterial load(ResolvedWorkBinding binding) {
    String reference = reference(binding);
    String operationId = binding.operationId() == null ? "" : binding.operationId();
    String version = binding.version() == null ? "" : binding.version();
    if (reference.startsWith("apihub:")) {
      return new ContractMaterial.Unavailable(
          reference,
          operationId,
          version,
          "Contract "
              + reference
              + " has no pinned catalog schema. Import that version or select a catalog operation before loading schemas.");
    }
    try {
      OperationDto operation = catalog.getOperation(operationId);
      if (operation == null || operation.id() == null || !operationId.equals(operation.id())) {
        String returned = operation == null || operation.id() == null ? "" : operation.id();
        return new ContractMaterial.Incompatible(
            reference,
            operationId,
            version,
            "Catalog returned operation "
                + returned
                + " for requested operation "
                + operationId
                + ". Keep the selected operation.");
      }
      SpecificationDto model = catalog.getModel(operation.modelId());
      if (!version.equals(model.version() == null ? "" : model.version())) {
        return new ContractMaterial.Incompatible(
            reference,
            operationId,
            version,
            "Operation "
                + operationId
                + " is pinned to version "
                + version
                + ", but the catalog specification is "
                + model.version()
                + ". Keep the pinned operation and version.");
      }
      OperationSchemaMapsDto maps = catalog.getOperationSchemas(operationId, "full");
      List<PortSchemaMaterial> ports = new ArrayList<>();
      for (String port : binding.exposedPorts()) {
        JsonNode schema = readPort(operationId, port, maps);
        if (!usable(schema)) {
          return new ContractMaterial.MissingSchema(
              reference,
              operationId,
              version,
              port,
              "Operation "
                  + operationId
                  + " version "
                  + version
                  + " has no "
                  + port
                  + " schema. Load the pinned contract before using that port.");
        }
        ports.add(new PortSchemaMaterial(reference, operationId, version, port, sha256(schema), schema));
      }
      return new ContractMaterial.Ready(reference, operationId, version, ports);
    } catch (RuntimeException failure) {
      return new ContractMaterial.ReadFailed(
          reference,
          operationId,
          version,
          "Schema read failed for operation "
              + operationId
              + " version "
              + version
              + ": "
              + failure.getMessage()
              + ". Retry the catalog read; do not substitute a schema.");
    }
  }

  private JsonNode readPort(String operationId, String port, OperationSchemaMapsDto maps) {
    if ("request".equals(port) || "payload".equals(port)) {
      JsonNode read = catalog.getOperationRequestSchema(operationId, JSON_TYPE);
      JsonNode listed = maps.requestSchema() == null ? null : maps.requestSchema().get(JSON_TYPE);
      return confirmed(read, listed);
    }
    if ("success".equals(port)) {
      return response(operationId, maps, "200", "201", "2XX");
    }
    if ("failure".equals(port)) {
      return response(operationId, maps, "400", "4XX", "500", "5XX", "default");
    }
    return null;
  }

  private JsonNode response(String operationId, OperationSchemaMapsDto maps, String... codes) {
    Map<String, JsonNode> responses = maps.responseSchemas();
    if (responses == null) {
      return null;
    }
    for (String code : codes) {
      if (!responses.containsKey(code)) {
        continue;
      }
      JsonNode read = catalog.getOperationResponseSchema(operationId, JSON_TYPE, code);
      return confirmed(read, unwrap(responses.get(code)));
    }
    return null;
  }

  private static JsonNode confirmed(JsonNode read, JsonNode listed) {
    if (!usable(read) || !usable(listed) || !read.equals(listed)) {
      return null;
    }
    return read;
  }

  private static JsonNode unwrap(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode typed = node.get(JSON_TYPE);
    if (typed != null && !typed.isNull()) {
      return typed;
    }
    return node;
  }

  private static boolean usable(JsonNode schema) {
    return schema != null
        && !schema.isNull()
        && !schema.isMissingNode()
        && (schema.has("type") || schema.has("$ref") || schema.has("properties") || schema.has("items"));
  }

  private static String reference(ResolvedWorkBinding binding) {
    if (binding.contractReferences().isEmpty()) {
      return "";
    }
    return binding.contractReferences().get(0);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String sha256(JsonNode schema) {
    try {
      byte[] bytes = JSON.writeValueAsBytes(schema);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }
}
