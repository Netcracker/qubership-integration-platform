package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;

/**
 * A connection between two new elements of the same branch body.
 *
 * <p>Carries neither an id nor a scope. Both are decided by the branch this connection is declared
 * in, so a capture cannot attach the connection to a branch it does not belong to, nor collide with
 * the id of a connection the chain already has.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditSubgraphConnection(
    @Description("Id of the new element this connection leaves from") String fromNodeId,
    @Description("Id of the new element this connection arrives at") String toNodeId) {}
