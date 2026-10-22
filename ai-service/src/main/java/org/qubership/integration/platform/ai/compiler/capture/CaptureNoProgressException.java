package org.qubership.integration.platform.ai.compiler.capture;

import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;

/** A proven repeated proposal must change strategy before another model invocation. */
public final class CaptureNoProgressException extends CaptureValidationException {
  private final CaptureFailureEvidence evidence;

  public CaptureNoProgressException(String message) {
    this(message, null);
  }

  public CaptureNoProgressException(String message, CaptureFailureEvidence evidence) {
    super(message);
    this.evidence = evidence;
  }

  public CaptureFailureEvidence evidence() {
    return evidence;
  }
}
