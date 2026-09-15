package org.qubership.integration.platform.ai.productpipeline.runtime;

import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;

/** Creates and activates a derived run from one durable checkpoint. */
public record RestartRunCommand(
    String conversationId,
    String parentRunId,
    long parentRunRevision,
    String childRunId,
    ProductPipelineProfile profile,
    RestartCheckpoint checkpoint,
    String commandId,
    String commandPayloadHash) {

  public RestartRunCommand {
    requireText(conversationId, "conversationId");
    requireText(parentRunId, "parentRunId");
    requireText(childRunId, "childRunId");
    requireText(commandId, "commandId");
    requireText(commandPayloadHash, "commandPayloadHash");
    if (parentRunRevision < 1) {
      throw new IllegalArgumentException("parentRunRevision must be >= 1");
    }
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(checkpoint, "checkpoint");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
