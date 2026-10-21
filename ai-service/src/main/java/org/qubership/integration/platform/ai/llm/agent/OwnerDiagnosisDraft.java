package org.qubership.integration.platform.ai.llm.agent;

/**
 * Structured reply from the diagnosis turn. The model authors the explanation only. The runtime
 * selects the owner and writes the instruction; a field the model names for either is ignored.
 */
public record OwnerDiagnosisDraft(String narrative) {

  public OwnerDiagnosisDraft {
    narrative = narrative == null ? "" : narrative;
  }
}
