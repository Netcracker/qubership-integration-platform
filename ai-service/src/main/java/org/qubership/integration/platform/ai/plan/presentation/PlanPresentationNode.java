package org.qubership.integration.platform.ai.plan.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Compact node view for plan presentation facts. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanPresentationNode(
    String nodeId,
    String type,
    String label,
    String parentNodeId,
    List<String> propertyFacts) {

  public PlanPresentationNode {
    propertyFacts = propertyFacts == null ? List.of() : List.copyOf(propertyFacts);
  }

  public PlanPresentationNode(String nodeId, String type, String label, String parentNodeId) {
    this(nodeId, type, label, parentNodeId, List.of());
  }
}
