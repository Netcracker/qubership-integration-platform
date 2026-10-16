package org.qubership.integration.platform.ai.qipknowledge.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic support matrix generated with the versioned knowledge indexes. */
public record ElementSupportMatrix(
    int schemaVersion,
    String compilerContractVersion,
    Map<ElementSupportStatus, Integer> counts,
    List<ElementSupportEntry> elements) {

  public ElementSupportMatrix {
    counts =
        counts == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    elements = elements == null ? List.of() : List.copyOf(elements);
  }

  public ElementSupportEntry require(String elementType) {
    return elements.stream()
        .filter(entry -> entry.elementType().equals(elementType))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Element is absent from support matrix: " + elementType));
  }
}
