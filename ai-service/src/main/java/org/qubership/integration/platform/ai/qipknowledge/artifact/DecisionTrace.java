package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Trace of decision steps taken during planning. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionTrace(
    List<DecisionStep> steps, List<QipKnowledgeCitation> citations, String summary) {}
