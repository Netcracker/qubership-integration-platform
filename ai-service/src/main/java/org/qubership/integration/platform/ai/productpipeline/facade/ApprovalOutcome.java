package org.qubership.integration.platform.ai.productpipeline.facade;

/**
 * Result of an approval command, named without reference to the pipeline that produced it.
 *
 * <p>Only acceptance carries a payload. Every refusal — a stale revision, a wrong hash, a wrong
 * type, a duplicate — stays an opaque {@code ApprovalOutcome}, because the caller recovers by
 * re-reading the snapshot to see what the run waits for now.
 */
public interface ApprovalOutcome {

  /** The command was applied and the run advanced. */
  interface Accepted extends ApprovalOutcome {

    ExecutionSnapshot snapshot();
  }
}
