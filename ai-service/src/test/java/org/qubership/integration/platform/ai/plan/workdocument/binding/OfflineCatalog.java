package org.qubership.integration.platform.ai.plan.workdocument.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding;

/**
 * Catalog transport fake. It returns versioned contracts and does not call a live catalog.
 */
public final class OfflineCatalog implements CatalogResolution {

  private final ObjectMapper json = new ObjectMapper();
  private final Set<String> missing = new LinkedHashSet<>();
  private String version = "1";
  private boolean priorityEnum;

  public void version(String version) {
    this.version = version;
  }

  public String version() {
    return version;
  }

  public void miss(String operationHint) {
    missing.add(operationHint);
  }

  public void priorityEnum(boolean enabled) {
    this.priorityEnum = enabled;
  }

  @Override
  public CatalogLookup lookup(String operationHint, String pinnedVersion) {
    if (operationHint != null && missing.contains(operationHint)) {
      return new CatalogLookup.Miss();
    }
    if (pinnedVersion != null && !pinnedVersion.isBlank() && !pinnedVersion.equals(version)) {
      return new CatalogLookup.PinnedUnavailable(pinnedVersion);
    }
    String operation = operationHint == null || operationHint.isBlank() ? "operation" : operationHint;
    return new CatalogLookup.Hit(
        new CatalogHit(
            operation,
            "catalog-" + operation,
            "group-" + operation,
            "spec-" + operation,
            version,
            operation,
            "http",
            "POST",
            "/" + operation,
            ports(operation)));
  }

  @Override
  public ApiHubHit searchApiHub(String interactionId, String operationHint, String pinnedVersion) {
    return null;
  }

  @Override
  public void importContract(ApiHubHit hit) {}

  @Override
  public ContractMaterial loadContract(ResolvedWorkBinding binding) {
    String operation = binding == null || binding.operationId() == null ? "" : binding.operationId();
    String pinned = binding == null || binding.version() == null ? version : binding.version();
    String reference = "";
    if (binding != null && binding.contractReferences() != null && !binding.contractReferences().isEmpty()) {
      reference = binding.contractReferences().get(0);
    }
    List<PortSchemaMaterial> schemas = new ArrayList<>();
    for (String port : ports(operation)) {
      schemas.add(
          new PortSchemaMaterial(
              reference, operation, pinned, port, "hash-" + port + "-" + pinned, schema(port)));
    }
    return new ContractMaterial.Ready(reference, operation, pinned, schemas);
  }

  private List<String> ports(String operation) {
    if ("onTaskStart".equals(operation)) {
      return List.of("payload");
    }
    if ("onTaskResult".equals(operation)) {
      return List.of("request");
    }
    return List.of("request", "success", "failure");
  }

  private ObjectNode schema(String port) {
    ObjectNode root = json.createObjectNode();
    root.put("type", "object");
    ObjectNode properties = root.putObject("properties");
    switch (port) {
      case "payload" -> {
        properties.putObject("name").put("type", "string");
        properties.putObject("subRequestType").put("type", "string");
        properties.putObject("orderId").put("type", "string");
        properties.putObject("executionId").put("type", "string");
        properties.putObject("processInstanceId").put("type", "string");
        properties.putObject("executionNumber").put("type", "string");
        properties.putObject("taskId").put("type", "string");
        properties.putObject("priority").put("type", "string");
        properties.putObject("parameters").put("type", "object").putObject("properties").putObject("orderCreationDate").put("type", "string");
      }
      case "success", "failure" -> properties.putObject("status").put("type", "string");
      case "request" -> {
        properties.putObject("Subject").put("type", "string");
        ObjectNode priority = properties.putObject("Priority").put("type", "string");
        if (priorityEnum) {
          priority.putArray("enum").add("High").add("Normal").add("Low");
        }
        properties.putObject("Status").put("type", "string");
        properties.putObject("ActivityDate").put("type", "string");
        properties.putObject("Description").put("type", "string");
        properties.putObject("commandType").put("type", "string");
        properties.putObject("executionId").put("type", "string");
        properties.putObject("orderId").put("type", "string");
        properties.putObject("processId").put("type", "string");
        properties.putObject("executionNumber").put("type", "string");
        properties.putObject("taskId").put("type", "string");
        properties.putObject("sourceAppName").put("type", "string");
        properties.putObject("error").put("type", "object").putObject("properties").putObject("code").put("type", "string");
      }
      default -> properties.putObject("value").put("type", "string");
    }
    return root;
  }
}
