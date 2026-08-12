package org.qubership.integration.platform.ai.productpipeline.facade;

/**
 * Latest durable state of a pipeline run, named without reference to the pipeline that produced it.
 *
 * <p>Execution status is absent on purpose: status names are pipeline-owned, and {@link
 * #pendingAction()} already answers the only question a neutral reader asks — what, if anything,
 * the run waits for.
 */
// ponytail: no neutral status enum until a caller needs more than "is it waiting".
public interface ExecutionSnapshot {

  String taskId();

  String runId();

  /** Run revision an approval must name to be accepted. */
  long revision();

  /** The open wait, or {@code null} when the run waits for nothing. */
  PendingAction pendingAction();

  /** Failure detail for a failed run, or an empty string. */
  String failureMessage();
}
