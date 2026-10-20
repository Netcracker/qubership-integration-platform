package org.qubership.integration.platform.ai.productpipeline.facade;

/**
 * Latest durable state of a pipeline run, named without reference to the pipeline that produced it.
 *
 * <p>Execution status names stay pipeline-owned. {@link #pendingAction()} answers what the run
 * waits for; {@link #finished()} answers whether it has anything left to do, which is what a reader
 * outside the pipeline needs before claiming a turn, now that a conversation outlives the run bound
 * to it.
 */
public interface ExecutionSnapshot {

  /**
   * True when the run has reached an end, successful or not.
   *
   * <p>A finished run keeps its binding to the conversation but stops owning what the reader says
   * next: the chain it built can then be asked about or changed like any other.
   */
  boolean finished();

  String taskId();

  String runId();

  /** Run revision an approval must name to be accepted. */
  long revision();

  /** The open wait, or {@code null} when the run waits for nothing. */
  PendingAction pendingAction();

  /** Failure detail for a failed run, or an empty string. */
  String failureMessage();
}
