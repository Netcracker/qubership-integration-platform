package org.qubership.integration.platform.ai.productpipeline.recovery;

import java.util.List;
import java.util.Map;

/** One lossless semantic or schema validation finding. */
public record SemanticFinding(
    String code,
    String violatedRule,
    String occurrenceId,
    String nodeId,
    String elementType,
    List<String> missingKeys,
    List<String> unexpectedKeys,
    List<String> oneOfBranchHints,
    String selectedOneOf,
    Map<String, String> schemaDefaults,
    List<String> presentKeys,
    String rawValidatorJson) {

  public SemanticFinding {
    missingKeys = missingKeys == null ? List.of() : List.copyOf(missingKeys);
    unexpectedKeys = unexpectedKeys == null ? List.of() : List.copyOf(unexpectedKeys);
    oneOfBranchHints = oneOfBranchHints == null ? List.of() : List.copyOf(oneOfBranchHints);
    schemaDefaults = schemaDefaults == null ? Map.of() : Map.copyOf(schemaDefaults);
    presentKeys = presentKeys == null ? List.of() : List.copyOf(presentKeys);
    selectedOneOf = selectedOneOf == null ? "" : selectedOneOf;
  }
}
