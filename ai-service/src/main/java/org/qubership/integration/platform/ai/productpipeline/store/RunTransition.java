package org.qubership.integration.platform.ai.productpipeline.store;

import java.time.Instant;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition;

/**
 * Append-only record of one run-status transition.
 *
 * <p>{@code commandId} and {@code commandPayloadHash} carry durable evidence that an external
 * command produced this transition. They are written in the same compare-and-set update as the
 * transition itself, so evidence cannot outlive a rolled-back commit or be lost with a process.
 * Both are {@code null} for internal transitions and for documents written before evidence existed.
 *
 * <p>{@code clarification} carries an optional offer, an optional answer, or both. Stored JSON
 * with no clarification field loads as {@code null}.
 */
public record RunTransition(
    long fromRevision,
    long toRevision,
    RunStatus fromStatus,
    RunStatus toStatus,
    String stageId,
    Instant at,
    String reason,
    String commandId,
    String commandPayloadHash,
    ClarificationTransition clarification) {

  /** Records a transition that carries no clarification envelope. */
  public RunTransition(
      long fromRevision,
      long toRevision,
      RunStatus fromStatus,
      RunStatus toStatus,
      String stageId,
      Instant at,
      String reason,
      String commandId,
      String commandPayloadHash) {
    this(
        fromRevision,
        toRevision,
        fromStatus,
        toStatus,
        stageId,
        at,
        reason,
        commandId,
        commandPayloadHash,
        null);
  }

  /** Records an internal transition that no external command claims. */
  public RunTransition(
      long fromRevision,
      long toRevision,
      RunStatus fromStatus,
      RunStatus toStatus,
      String stageId,
      Instant at,
      String reason) {
    this(fromRevision, toRevision, fromStatus, toStatus, stageId, at, reason, null, null);
  }
}
