package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;

/**
 * Directed execution edge between two nodes in the plan.
 *
 * <p>{@code scopeNodeId} names the container branch this edge belongs to
 * (e.g. the {@code try-2} nodeId for edges inside a try branch).
 * Null means the edge is at the chain root scope.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainPlanEdge(
    @Description("Unique edge id within the plan graph")
        @JsonProperty(required = true) String edgeId,
    @Description("Source node id")
        @JsonProperty(required = true) String fromNodeId,
    @Description("Target node id")
        @JsonProperty(required = true) String toNodeId,
    @Description("Container branch scope node id; null for root-level edges") String scopeNodeId) {}
