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
      if (model == null || !version.equals(model.version() == null ? "" : model.version())) {
        String catalogVersion = model == null || model.version() == null ? "" : model.version();
        return new ContractMaterial.Incompatible(
            reference,
            operationId,
            version,
            "Operation "
                + operationId
                + " is pinned to version "
                + version
                + ", but the catalog specification is "
                + catalogVersion
                + ". Keep the pinned operation and version.");
      }
      String modelId = model.id() == null ? "" : model.id();
      if (!reference.equals(modelId)) {
        return new ContractMaterial.Incompatible(
            reference,
            operationId,
            version,
            "Operation "
                + operationId
                + " is pinned to specification "
                + reference
                + ", but the catalog specification is "
                + modelId
                + ". Keep the pinned specification.");
      }
      OperationSchemaMapsDto maps = catalog.getOperationSchemas(operationId, "full");
      List<PortSchemaMaterial> ports = new ArrayList<>();
      for (String port : binding.exposedPorts()) {
        PortLoad loadedPort = readPort(operationId, port, maps);
        if (loadedPort instanceof PortLoad.Several several) {
          return new ContractMaterial.Gap(
              reference,
              operationId,
              version,
              port,
              several.codes(),
              "Operation "
                  + operationId
                  + " version "
                  + version
                  + " has several "
                  + port
                  + " schemas ("
                  + String.join(", ", several.codes())
                  + "). Select one response code for that port.");
        }
        JsonNode schema = loadedPort instanceof PortLoad.Body body ? body.schema() : null;
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

  private PortLoad readPort(String operationId, String port, OperationSchemaMapsDto maps) {
    if ("request".equals(port) || "payload".equals(port)) {
      return readBody(operationId, maps);
    }
    if ("success".equals(port)) {
      return readOutcome(operationId, maps, true);
    }
    if ("failure".equals(port)) {
      return readOutcome(operationId, maps, false);
    }
    return new PortLoad.Absent();
  }

  private PortLoad readBody(String operationId, OperationSchemaMapsDto maps) {
    JsonNode listed = maps.requestSchema() == null ? null : maps.requestSchema().get(JSON_TYPE);
    if (usable(listed)) {
      return confirmedBody(catalog.getOperationRequestSchema(operationId, JSON_TYPE), listed);
    }
    return asyncMessage(operationId, maps);
  }

  /**
   * Catalog AsyncAPI stores the channel message under response schemas and leaves the request map
   * empty. HTTP status keys stay response codes, so a GET does not gain a request from its reply.
   */
  private PortLoad asyncMessage(String operationId, OperationSchemaMapsDto maps) {
    Map<String, JsonNode> responses = maps.responseSchemas();
    if (responses == null || responses.isEmpty()) {
      return new PortLoad.Absent();
    }
    List<String> names = new ArrayList<>();
    for (String key : responses.keySet()) {
      if (httpStatus(key)) {
        return new PortLoad.Absent();
      }
      names.add(key);
    }
    if (names.size() != 1) {
      return new PortLoad.Several(names);
    }
    String name = names.get(0);
    return confirmedBody(
        catalog.getOperationResponseSchema(operationId, JSON_TYPE, name), unwrap(responses.get(name)));
  }

  private PortLoad readOutcome(String operationId, OperationSchemaMapsDto maps, boolean success) {
    Map<String, JsonNode> responses = maps.responseSchemas();
    if (responses == null) {
      return new PortLoad.Absent();
    }
    List<String> explicitSuccess = codes(responses, true);
    List<String> chosen;
    if (success) {
      chosen =
          explicitSuccess.isEmpty() && responses.containsKey("default")
              ? List.of("default")
              : explicitSuccess;
    } else {
      List<String> failures = codes(responses, false);
      if (!explicitSuccess.isEmpty() && responses.containsKey("default")) {
        failures.add("default");
      }
      chosen = failures;
    }
    if (chosen.isEmpty()) {
      return new PortLoad.Absent();
    }
    if (chosen.size() > 1) {
      return new PortLoad.Several(chosen);
    }
    String code = chosen.get(0);
    return confirmedBody(
        catalog.getOperationResponseSchema(operationId, JSON_TYPE, code), unwrap(responses.get(code)));
  }

  private static List<String> codes(Map<String, JsonNode> responses, boolean success) {
    List<String> codes = new ArrayList<>();
    for (String code : responses.keySet()) {
      if (success ? successCode(code) : failureCode(code)) {
        codes.add(code);
      }
    }
    return codes;
  }

  private static PortLoad confirmedBody(JsonNode read, JsonNode listed) {
    JsonNode schema = confirmed(read, listed);
    return schema == null ? new PortLoad.Absent() : new PortLoad.Body(schema);
  }

  private static boolean successCode(String code) {
    return "2XX".equals(code) || statusFamily(code, '2');
  }

  private static boolean failureCode(String code) {
    return "4XX".equals(code) || "5XX".equals(code) || statusFamily(code, '4') || statusFamily(code, '5');
  }

  private static boolean httpStatus(String code) {
    return "default".equals(code)
        || "1XX".equals(code)
        || "3XX".equals(code)
        || successCode(code)
        || failureCode(code)
        || statusFamily(code, '1')
        || statusFamily(code, '3');
  }

  private static boolean statusFamily(String code, char family) {
    return code != null
        && code.length() == 3
        && code.charAt(0) == family
        && digit(code.charAt(1))
        && digit(code.charAt(2));
  }

  private static boolean digit(char value) {
    return value >= '0' && value <= '9';
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
        && (schema.has("type")
            || schema.has("$ref")
            || schema.has("properties")
            || schema.has("items")
            || schema.has("allOf")
            || schema.has("oneOf")
            || schema.has("anyOf")
            || schema.has("enum"));
  }

  private sealed interface PortLoad {
    record Body(JsonNode schema) implements PortLoad {}

    record Absent() implements PortLoad {}

    record Several(List<String> codes) implements PortLoad {}
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
