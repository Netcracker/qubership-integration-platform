package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Metadata for the chain to be created.
 *
 * <p>{@code folderPath} is the catalog folder the author asked for, as they wrote it, copied from
 * the approved brief; null puts the chain at the root.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainSection(
    String name,
    String description,
    Boolean maskingEnabled,
    List<String> maskedFieldNames,
    String semanticRevisionId,
    String compilerContractVersion,
    String folderPath) {

  public ChainSection(String name, String description) {
    this(name, description, null, null, null, null, null);
  }

  /** Keeps existing call sites on the six-field constructor. */
  public ChainSection(
      String name,
      String description,
      Boolean maskingEnabled,
      List<String> maskedFieldNames,
      String semanticRevisionId,
      String compilerContractVersion) {
    this(
        name,
        description,
        maskingEnabled,
        maskedFieldNames,
        semanticRevisionId,
        compilerContractVersion,
        null);
  }
}
