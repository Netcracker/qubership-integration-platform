package org.qubership.integration.platform.ai.schema;

import java.util.List;
import java.util.Set;

/** Parsed JSON Schema {@code if}/{@code then}/{@code else} branch with a single const discriminator. */
public record SchemaIfThenBranch(
    String discriminatorKey,
    String discriminatorValue,
    Set<String> thenRequired,
    List<SchemaIfThenBranch> nestedThen,
    Set<String> elseRequired,
    List<SchemaIfThenBranch> nestedElse) {

  public SchemaIfThenBranch {
    thenRequired = Set.copyOf(thenRequired);
    nestedThen = List.copyOf(nestedThen);
    elseRequired = Set.copyOf(elseRequired);
    nestedElse = List.copyOf(nestedElse);
  }
}
