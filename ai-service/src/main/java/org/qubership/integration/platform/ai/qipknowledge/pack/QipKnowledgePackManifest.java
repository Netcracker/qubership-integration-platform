package org.qubership.integration.platform.ai.qipknowledge.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manifest describing one ingested QIP skill pack version. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QipKnowledgePackManifest(
    QipKnowledgePackVersion version,
    String sourcePath,
    Instant createdAt,
    Map<String, String> fileChecksums,
    List<String> skillIds,
    List<String> supportedCapabilityIds,
    List<String> unsupportedCapabilityIds,
    String compilerContractVersion,
    String compilerContractSha256,
    Map<String, String> addonSha256) {

  public QipKnowledgePackManifest {
    addonSha256 = addonSha256 != null ? Map.copyOf(new LinkedHashMap<>(addonSha256)) : Map.of();
  }

  public QipKnowledgePackManifest(
      QipKnowledgePackVersion version,
      String sourcePath,
      Instant createdAt,
      Map<String, String> fileChecksums,
      List<String> skillIds,
      List<String> supportedCapabilityIds,
      List<String> unsupportedCapabilityIds) {
    this(
        version,
        sourcePath,
        createdAt,
        fileChecksums,
        skillIds,
        supportedCapabilityIds,
        unsupportedCapabilityIds,
        null,
        null,
        Map.of());
  }

  public QipKnowledgePackManifest withCompilerContractPin(
      String compilerContractVersion,
      String compilerContractSha256,
      Map<String, String> addonSha256) {
    return new QipKnowledgePackManifest(
        version,
        sourcePath,
        createdAt,
        fileChecksums,
        skillIds,
        supportedCapabilityIds,
        unsupportedCapabilityIds,
        compilerContractVersion,
        compilerContractSha256,
        addonSha256);
  }
}
