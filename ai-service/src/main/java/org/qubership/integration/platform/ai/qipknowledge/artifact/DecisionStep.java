package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One decision step in a compiler-backed decision walk. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionStep(
    String decisionId,
    String question,
    String selectedOption,
    String rationale,
    List<String> alternatives) {}
