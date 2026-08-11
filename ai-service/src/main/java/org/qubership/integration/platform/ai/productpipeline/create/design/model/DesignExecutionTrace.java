package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionPhase;

/** Append-only executor phase trace for one approved design execution. */
public record DesignExecutionTrace(String schemaVersion, List<Entry> entries) {

  public DesignExecutionTrace {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    entries = DesignArtifacts.copyList(entries);
  }

  public record Entry(
      DesignExecutionPhase phase,
      String stepId,
      List<Reference> inputRefs,
      List<Reference> outputRefs,
      String outcome) {

    public Entry {
      phase = DesignArtifacts.requireNonNull(phase, "phase");
      stepId = DesignArtifacts.optionalText(stepId);
      inputRefs = DesignArtifacts.copyList(inputRefs);
      outputRefs = DesignArtifacts.copyList(outputRefs);
      outcome = DesignArtifacts.requireText(outcome, "outcome");
    }
  }
}
