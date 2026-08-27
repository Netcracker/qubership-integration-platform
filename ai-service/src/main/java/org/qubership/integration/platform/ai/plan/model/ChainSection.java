package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Metadata for the chain to be created. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainSection(
    String name,
    String description,
    Boolean maskingEnabled,
    List<String> maskedFieldNames,
    String semanticRevisionId,
    String compilerContractVersion) {

  public ChainSection(String name, String description) {
    this(name, description, null, null, null, null);
  }
}
