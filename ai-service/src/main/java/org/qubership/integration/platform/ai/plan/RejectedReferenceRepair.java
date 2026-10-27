package org.qubership.integration.platform.ai.plan;

import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;

/**
 * Canonical element ids are checked by the capture editor.
 *
 * <p>A rejected attempt does not authorize a reference change, so this repair leaves the supplied
 * facts unchanged. Callers must not store the result as repair authority.
 */
final class RejectedReferenceRepair {
  record Repair(List<FactInput> facts, Set<Long> evidenceSequences) {}

  private RejectedReferenceRepair() {}

  static Repair repair(List<FactInput> facts) {
    return new Repair(List.copyOf(facts), Set.of());
  }
}
