package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Advisory golden pattern selection metadata for planning. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SelectedPattern(
    String patternId,
    String name,
    String reason,
    QipKnowledgeCitation citation,
    List<String> requiredCapabilities,
    String summary) {}
