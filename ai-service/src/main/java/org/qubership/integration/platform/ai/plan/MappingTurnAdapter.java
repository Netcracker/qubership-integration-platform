package org.qubership.integration.platform.ai.plan;

import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Interprets one author message against the current requirement brief. Production uses the chat
 * model; tests supply a deterministic adapter.
 */
@FunctionalInterface
public interface MappingTurnAdapter {

  MappingTurnResult interpret(RequirementBrief brief, String authorMessage);
}
