package org.qubership.integration.platform.ai.qipknowledge.patch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Patch for chain-level metadata on {@link org.qubership.integration.platform.ai.plan.model.ChainSection}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainPatch(GraphPatchOperation operation, PlanProperty property) {}
