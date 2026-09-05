package org.qubership.integration.platform.ai.chain.edit.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/**
 * Builds the process-skill prompt for {@code cip-chain-edit-planner} from the imported graph and
 * catalog operation schemas.
 */
@ApplicationScoped
public class CipChainEditPlannerRequestBuilder {

  private static final String SERVICE_CALL = "service-call";
  private static final String OPERATION_ID = "integrationOperationId";

  private final ObjectMapper objectMapper;
  private final OperationSchemaLoader schemaLoader;

  @Inject
  public CipChainEditPlannerRequestBuilder(
      ObjectMapper objectMapper, OperationSchemaLoader schemaLoader) {
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    this.schemaLoader = schemaLoader;
  }

  public CipChainEditPlannerRequestBuilder(ObjectMapper objectMapper) {
    this(objectMapper, null);
  }

  public Map<String, OperationSchemaMaps> loadSchemas(
      ChainPlanGraph graph, List<ResolvedServiceCallBinding> bindings) {
    Map<String, OperationSchemaMaps> schemas = new LinkedHashMap<>();
    if (schemaLoader == null) {
      return schemas;
    }
    if (bindings != null) {
      for (ResolvedServiceCallBinding binding : bindings) {
        putSchema(schemas, binding.operationId());
      }
    }
    if (graph.nodes() != null) {
      for (ChainPlanNode node : graph.nodes()) {
        if (node == null || !SERVICE_CALL.equals(node.type())) {
          continue;
        }
        putSchema(schemas, property(node, OPERATION_ID));
      }
    }
    return Map.copyOf(schemas);
  }

  public String buildPrompt(ChainEditPlannerRequest request) {
    StringBuilder body = new StringBuilder();
    body.append("## User request\n\n");
    body.append(request.userRequest().isBlank() ? request.intent().requestedChange() : request.userRequest());
    body.append("\n\n## Chain edit intent\n\n");
    body.append("- action: ").append(request.intent().action()).append('\n');
    body.append("- disposition: ").append(request.intent().disposition()).append('\n');
    body.append("- requested element type: ")
        .append(request.intent().requestedElementType())
        .append('\n');
    body.append("- original target ids: ")
        .append(String.join(", ", request.intent().targetNodeIds()))
        .append('\n');
    body.append("- requested change: ").append(request.intent().requestedChange()).append('\n');
    body.append("\n## Imported graph\n\n```json\n");
    body.append(writeJson(request.graph()));
    body.append("\n```\n");
    if (!request.bindings().isEmpty()) {
      body.append("\n## Bindings\n\n```json\n");
      body.append(writeJson(request.bindings()));
      body.append("\n```\n");
    }
    if (!request.operationSchemas().isEmpty()) {
      body.append("\n## Reporter schema variants\n\n");
      body.append(
          "Use these catalog keys as reporterSchemaVariant. Do not ask when a failed payload"
              + " key is present.\n");
      for (Map.Entry<String, OperationSchemaMaps> entry : request.operationSchemas().entrySet()) {
        body.append("- ")
            .append(entry.getKey())
            .append(": ")
            .append(String.join(", ", entry.getValue().responseByStatusThenContentType().keySet()))
            .append('\n');
      }
      body.append("\n## Operation schemas\n\n```json\n");
      body.append(writeJson(request.operationSchemas()));
      body.append("\n```\n");
    }
    body.append("\nReturn only the JSON structural plan object.\n");
    return body.toString();
  }

  private void putSchema(Map<String, OperationSchemaMaps> schemas, String operationId) {
    if (operationId == null || operationId.isBlank() || schemas.containsKey(operationId)) {
      return;
    }
    try {
      schemas.put(operationId, schemaLoader.load(operationId));
    } catch (RuntimeException e) {
      // Leave the operation out. The planner must CLARIFY when a required schema is missing.
    }
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize planner input", e);
    }
  }
}
