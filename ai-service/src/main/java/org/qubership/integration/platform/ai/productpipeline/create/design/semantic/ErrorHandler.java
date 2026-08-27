package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Ordered catch-2 handler inside an error scope. List order is the handler order. */
public record ErrorHandler(
    String handlerId, String exceptionClass, String entryNodeId, List<String> exitNodeIds) {

  public ErrorHandler {
    handlerId = DesignArtifacts.requireText(handlerId, "handlerId");
    exceptionClass = DesignArtifacts.requireText(exceptionClass, "exceptionClass");
    entryNodeId = DesignArtifacts.requireText(entryNodeId, "entryNodeId");
    exitNodeIds = DesignArtifacts.copyList(exitNodeIds);
  }
}
