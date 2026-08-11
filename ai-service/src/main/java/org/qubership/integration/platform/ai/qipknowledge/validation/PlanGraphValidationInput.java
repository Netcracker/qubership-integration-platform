package org.qubership.integration.platform.ai.qipknowledge.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Explicit input for compiler-backed plan graph validation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanGraphValidationInput(ChainPlanGraph graph) {}
