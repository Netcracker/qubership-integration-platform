package org.qubership.integration.platform.ai.catalog.binding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Writes catalog identity properties onto bound plan nodes without cross-protocol residue. */
public final class ServiceCallCatalogIdentity {

  private static final Set<String> STRIPPED_KEYS =
      Set.of(
          "serviceCallId",
          "systemType",
          "integrationSystemId",
          "integrationSpecificationGroupId",
          "integrationSpecificationId",
          "integrationOperationProtocolType",
          "integrationOperationId",
          "integrationOperationMethod",
          "integrationOperationPath",
          "synchronousGrpcCall",
          "integrationGqlQuery",
          "integrationGqlOperationName",
          "integrationGqlQueryHeader",
          "integrationGqlVariablesHeader",
          "integrationGqlVariablesJSON",
          "groupId",
          "integrationOperationAsyncProperties");

  private ServiceCallCatalogIdentity() {}

  public static String occurrenceId(ChainPlanNode node) {
    return node.serviceCallId().orElse(node.nodeId());
  }

  public static ChainPlanGraph upsert(ChainPlanGraph graph, ResolvedServiceCallBinding binding) {
    ChainPlanNode target = findNode(graph, binding.targetNodeId());
    if ("service-call".equals(target.type())) {
      String existingServiceCallId = propertyValue(target, "serviceCallId");
      if (existingServiceCallId != null && !existingServiceCallId.equals(binding.serviceCallId())) {
        throw new IllegalArgumentException(
            "serviceCallId "
                + existingServiceCallId
                + " on node "
                + binding.targetNodeId()
                + " does not match binding "
                + binding.serviceCallId());
      }
    }

    ChainPlanNode updated = withIdentity(target, binding);
    List<ChainPlanNode> nodes =
        graph.nodes().stream()
            .map(node -> node.nodeId().equals(binding.targetNodeId()) ? updated : node)
            .toList();
    ChainPlanGraph result = new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
    assertUniqueServiceCallIds(result);
    return result;
  }

  private static ChainPlanNode findNode(ChainPlanGraph graph, String nodeId) {
    for (ChainPlanNode node : graph.nodes()) {
      if (nodeId.equals(node.nodeId())) {
        return node;
      }
    }
    throw new IllegalArgumentException("node not found: " + nodeId);
  }

  private static ChainPlanNode withIdentity(ChainPlanNode node, ResolvedServiceCallBinding binding) {
    List<PlanProperty> properties = new ArrayList<>();
    if (node.properties() != null) {
      for (PlanProperty property : node.properties()) {
        if (property == null || STRIPPED_KEYS.contains(property.key())) {
          continue;
        }
        properties.add(property);
      }
    }
    properties.addAll(identityProperties(node, binding));
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }

  private static List<PlanProperty> identityProperties(
      ChainPlanNode node, ResolvedServiceCallBinding binding) {
    List<PlanProperty> properties = new ArrayList<>();
    if ("service-call".equals(node.type())) {
      properties.add(new PlanProperty("serviceCallId", binding.serviceCallId()));
    }
    properties.add(new PlanProperty("systemType", binding.systemType()));
    properties.add(new PlanProperty("integrationSystemId", binding.systemId()));
    properties.add(new PlanProperty("integrationSpecificationGroupId", binding.specificationGroupId()));
    properties.add(new PlanProperty("integrationSpecificationId", binding.specificationId()));
    properties.add(new PlanProperty("integrationOperationProtocolType", binding.protocolType()));
    properties.add(new PlanProperty("integrationOperationId", binding.operationId()));
    appendProtocolProperties(properties, node, binding);
    return properties;
  }

  private static void appendProtocolProperties(
      List<PlanProperty> properties, ChainPlanNode node, ResolvedServiceCallBinding binding) {
    String protocol = binding.protocolType().toLowerCase(Locale.ROOT);
    properties.add(new PlanProperty("integrationOperationMethod", binding.method()));
    boolean optionalAsyncPath =
        ("kafka".equals(protocol) || "amqp".equals(protocol))
            && binding.path() != null
            && !binding.path().isBlank();
    if ("http".equals(protocol) || optionalAsyncPath) {
      properties.add(new PlanProperty("integrationOperationPath", binding.path()));
    }
    if ("async-api-trigger".equals(node.type()) && "kafka".equals(protocol)) {
      // ponytail: sample default until catalog specs carry groupId
      String groupId = binding.groupId().isEmpty() ? "qip" : binding.groupId();
      properties.add(
          new PlanProperty(
              "integrationOperationAsyncProperties",
              kafkaAsyncPropertiesJson(binding.maasClassifierName(), groupId)));
      return;
    }
    if (!binding.groupId().isEmpty()) {
      properties.add(new PlanProperty("groupId", binding.groupId()));
    }
    if (!binding.maasClassifierName().isEmpty()) {
      properties.add(
          new PlanProperty(
              "integrationOperationAsyncProperties",
              "{\"maas.classifier.name\":\"" + binding.maasClassifierName() + "\"}"));
    }
  }

  private static String kafkaAsyncPropertiesJson(String classifier, String groupId) {
    if (classifier.isEmpty()) {
      return "{\"groupId\":\"" + groupId + "\"}";
    }
    return "{\"maas.classifier.name\":\"" + classifier + "\",\"groupId\":\"" + groupId + "\"}";
  }

  private static void assertUniqueServiceCallIds(ChainPlanGraph graph) {
    Map<String, String> owners = new HashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (!"service-call".equals(node.type())) {
        continue;
      }
      String serviceCallId = propertyValue(node, "serviceCallId");
      if (serviceCallId == null) {
        continue;
      }
      String priorOwner = owners.putIfAbsent(serviceCallId, node.nodeId());
      if (priorOwner != null) {
        throw new IllegalArgumentException("duplicate serviceCallId: " + serviceCallId);
      }
    }
  }

  private static String propertyValue(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (property != null && key.equals(property.key())) {
        String value = property.value();
        return value == null || value.isBlank() ? null : value;
      }
    }
    return null;
  }
}
