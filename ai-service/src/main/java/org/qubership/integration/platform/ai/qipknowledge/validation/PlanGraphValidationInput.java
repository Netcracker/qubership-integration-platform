package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

/** Explicit input for compiler-backed plan graph validation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanGraphValidationInput(ChainPlanGraph graph, List<MappingIntent> mappingIntents) {

  public PlanGraphValidationInput {
    mappingIntents = mappingIntents == null ? List.of() : List.copyOf(mappingIntents);
  }

  public PlanGraphValidationInput(ChainPlanGraph graph) {
    this(graph, List.of());
  }
}
