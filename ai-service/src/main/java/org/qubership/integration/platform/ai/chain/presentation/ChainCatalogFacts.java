package org.qubership.integration.platform.ai.chain.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Deterministic snapshot of a chain read back from the runtime catalog. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainCatalogFacts(
    String chainId,
    String chainName,
    String chainDescription,
    int elementCount,
    int dependencyCount,
    String triggerSummary,
    List<ChainCatalogElement> elements,
    List<ChainCatalogDependency> dependencies,
    String lifecycleStatus) {

  public ChainCatalogFacts {
    chainName = chainName == null ? "" : chainName;
    chainDescription = chainDescription == null ? "" : chainDescription;
    triggerSummary = triggerSummary == null ? "" : triggerSummary;
    lifecycleStatus = lifecycleStatus == null ? "" : lifecycleStatus;
    elements = elements == null ? List.of() : List.copyOf(elements);
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
  }
}
