package org.qubership.integration.platform.ai.compiler.capture;

/** A proven repeated proposal must change strategy before another model invocation. */
public final class CaptureNoProgressException extends CaptureValidationException {
  public CaptureNoProgressException(String message) {
    super(message);
  }
}
