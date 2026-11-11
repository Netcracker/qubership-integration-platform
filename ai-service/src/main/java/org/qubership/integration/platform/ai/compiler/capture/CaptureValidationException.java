package org.qubership.integration.platform.ai.compiler.capture;

import io.quarkiverse.langchain4j.runtime.PreventsErrorHandlerExecution;

/**
 * Stops an in-stream tool-call loop after a capture becomes terminal (successful first accept,
 * validation failure repeats, or the artifact is already captured).
 *
 * <p>Implements {@link PreventsErrorHandlerExecution} so the quarkus-langchain4j streaming tool loop
 * rethrows it instead of routing it through the default tool-execution error handler, which would
 * turn the exception into a text tool result fed back to the model and let the loop continue. On the
 * streaming path this marker is the only way to make a tool exception terminate the stream. When the
 * capture session is already accepted, {@code CaptureRepairRunner} treats the failure as success and
 * completes so harvest can run without waiting for an LLM end-turn.
 */
public class CaptureValidationException extends RuntimeException
    implements PreventsErrorHandlerExecution {

  public CaptureValidationException(String message) {
    super(message);
  }
}
