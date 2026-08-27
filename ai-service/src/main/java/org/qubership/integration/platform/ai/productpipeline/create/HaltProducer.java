package org.qubership.integration.platform.ai.productpipeline.create;

import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationCapability;

/**
 * Stage or adapter that can halt a create-chain run. Exhaustive over producers that reach {@code
 * haltRecoverable}; {@link HaltProducerCauseTable} lists the causes each may emit.
 *
 * <p>Author follow-up matching ({@code go back}, named stages) stays English-phrase matching. That
 * is not a halt producer and is out of this table on purpose.
 */
public enum HaltProducer {
  REQUIREMENT_DISCOVERY,
  REQUIREMENT_ANALYSIS,
  DESIGN_INPUT,
  DESIGN_PLANNING,
  PLANNING,
  DESIGN_EXECUTION,
  SPECIFICATION_IMPORT,
  MATERIALIZATION,
  CATALOG_BINDING,
  COMPILER_VALIDATOR,
  STAGE_EXECUTOR;

  /** Maps a profile capability id onto the producer that capability is. Unknown ids are the executor. */
  public static HaltProducer ofCapability(String capabilityId) {
    if (capabilityId == null || capabilityId.isBlank()) {
      return STAGE_EXECUTOR;
    }
    return switch (capabilityId) {
      case RequirementDiscoveryCapability.CAPABILITY_ID -> REQUIREMENT_DISCOVERY;
      case RequirementAnalysisCapability.CAPABILITY_ID -> REQUIREMENT_ANALYSIS;
      case DesignInputCapability.CAPABILITY_ID -> DESIGN_INPUT;
      case DesignPlanningCapability.CAPABILITY_ID -> DESIGN_PLANNING;
      case PlanningCapability.CAPABILITY_ID -> PLANNING;
      case DesignExecutionCapability.CAPABILITY_ID -> DESIGN_EXECUTION;
      case SpecificationImportCapability.CAPABILITY_ID -> SPECIFICATION_IMPORT;
      case MaterializationCapability.CAPABILITY_ID -> MATERIALIZATION;
      default -> STAGE_EXECUTOR;
    };
  }
}
