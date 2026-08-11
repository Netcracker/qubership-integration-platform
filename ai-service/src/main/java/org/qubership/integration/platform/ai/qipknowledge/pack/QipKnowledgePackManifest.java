package org.qubership.integration.platform.ai.qipknowledge.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
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
    List<String> unsupportedCapabilityIds) {}
