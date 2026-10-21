package org.qubership.integration.platform.ai.compiler;

import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;

/** Runtime route for one compiler skill capture turn. */
public record CaptureRoute(String capabilityId, CaptureTool captureTool) {

  public CaptureRoute {
    if (capabilityId == null || capabilityId.isBlank()) {
      throw new IllegalArgumentException("capabilityId is required");
    }
    if (captureTool == null) {
      throw new IllegalArgumentException("captureTool is required");
    }
  }
}
