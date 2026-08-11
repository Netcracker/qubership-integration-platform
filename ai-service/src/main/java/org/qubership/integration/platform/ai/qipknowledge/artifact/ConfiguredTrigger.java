package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** One configured trigger role ready for structure assembly. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfiguredTrigger(
    String roleId,
    String semanticNodeId,
    String elementType,
    String label,
    List<PlanProperty> properties) {

  public ConfiguredTrigger {
    properties = properties == null ? List.of() : List.copyOf(properties);
  }
}
