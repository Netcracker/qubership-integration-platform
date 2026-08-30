package org.qubership.integration.platform.ai.productpipeline.create;

import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/**
 * Deterministic fallback for security generator failures. External HTTP triggers configured with
 * RBAC must carry a non-empty roles list. When the security generator fails to provide one, this
 * fallback injects a safe default role so validation can pass instead of blocking chain creation.
 */
final class CompilerSecurityFallback {

  private static final Logger LOG = Logger.getLogger(CompilerSecurityFallback.class);
  private static final String DEFAULT_ROLE = "[\"qip-viewer\"]";

  private CompilerSecurityFallback() {
    // Utility class.
  }

  static ChainPlanGraph apply(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return graph;
    }
    ChainPlanGraph current = graph;
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || !"http-trigger".equalsIgnoreCase(trim(node.type()))) {
        continue;
      }
      if (!"true".equalsIgnoreCase(propertyValue(node, "externalRoute"))) {
        continue;
      }
      if (!"RBAC".equalsIgnoreCase(propertyValue(node, "accessControlType"))) {
        continue;
      }
      String roles = propertyValue(node, "roles");
      if (roles != null && !roles.isBlank() && !"[]".equals(roles.trim())) {
        continue;
      }
      LOG.warnf(
          "Applying default RBAC role to nodeId=%s; security generator did not provide roles",
          node.nodeId());
      current = current.withNodeProperty(node.nodeId(), "roles", DEFAULT_ROLE);
    }
    return current;
  }

  private static String propertyValue(ChainPlanNode node, String key) {
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

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
