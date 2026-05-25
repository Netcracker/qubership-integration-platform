package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.model.ScenarioType;

/** Result of embedding-based scenario classification. */
public record RoutingMatch(ScenarioType scenario, double score, double margin) {}
