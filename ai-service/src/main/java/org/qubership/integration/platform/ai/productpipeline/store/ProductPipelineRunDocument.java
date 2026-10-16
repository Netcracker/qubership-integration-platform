package org.qubership.integration.platform.ai.productpipeline.store;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative CAS-protected run document: one snapshot plus append-only attempts and
 * transitions.
 */
public record ProductPipelineRunDocument(
    RunSnapshot run,
    List<StageAttempt> attempts,
    List<RunTransition> transitions,
    @JsonIgnore String blobVersion) {

  public ProductPipelineRunDocument {
    attempts = attempts == null ? List.of() : List.copyOf(attempts);
    transitions = transitions == null ? List.of() : List.copyOf(transitions);
  }

  public ProductPipelineRunDocument withBlobVersion(String version) {
    return new ProductPipelineRunDocument(run, attempts, transitions, version);
  }

  /**
   * Returns the durable transition already recorded for {@code commandId}, or empty when the
   * command has not been applied to this run.
   *
   * @throws CommandPayloadConflictException when {@code commandId} was applied with a different
   *     canonical payload
   */
  @JsonIgnore
  public Optional<RunTransition> appliedCommand(String commandId, String payloadHash) {
    if (commandId == null || commandId.isBlank()) {
      return Optional.empty();
    }
    Optional<RunTransition> applied =
        transitions.stream().filter(t -> commandId.equals(t.commandId())).findFirst();
    if (applied.isPresent()
        && !Objects.equals(payloadHash, applied.get().commandPayloadHash())) {
      throw new CommandPayloadConflictException(
          commandId, applied.get().commandPayloadHash(), payloadHash);
    }
    return applied;
  }
}
