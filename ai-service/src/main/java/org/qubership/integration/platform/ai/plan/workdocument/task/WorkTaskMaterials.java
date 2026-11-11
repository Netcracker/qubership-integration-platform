package org.qubership.integration.platform.ai.plan.workdocument.task;

import java.util.List;
import java.util.Map;

/** Server-owned schemas, constraints, and source text for one task. */
public record WorkTaskMaterials(
    List<SchemaFragment> schemas, List<String> globalConstraints, Map<String, String> sourceEvidence) {

  public WorkTaskMaterials {
    schemas = schemas == null ? List.of() : List.copyOf(schemas);
    globalConstraints = globalConstraints == null ? List.of() : List.copyOf(globalConstraints);
    sourceEvidence = sourceEvidence == null ? Map.of() : Map.copyOf(sourceEvidence);
  }
}
