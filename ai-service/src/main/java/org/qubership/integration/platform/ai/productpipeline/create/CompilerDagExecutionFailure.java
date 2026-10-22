package org.qubership.integration.platform.ai.productpipeline.create;

import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;

/** Failed compiler work with unapproved progress; callers cannot consume it as a completed result. */
public final class CompilerDagExecutionFailure extends IllegalStateException {
  private final CompilerDagExecutionResult rejectedProgress;
  private final CaptureFailureEvidence nativeFailure;

  CompilerDagExecutionFailure(String message, CompilerDagExecutionResult rejectedProgress,
      CaptureFailureEvidence nativeFailure) {
    this(message, rejectedProgress, nativeFailure, null);
  }

  CompilerDagExecutionFailure(String message, CompilerDagExecutionResult rejectedProgress,
      CaptureFailureEvidence nativeFailure, Throwable cause) {
    super(message, cause);
    this.rejectedProgress = rejectedProgress;
    this.nativeFailure = nativeFailure;
  }

  public CompilerDagExecutionResult rejectedProgress() { return rejectedProgress; }

  public CaptureFailureEvidence nativeFailure() { return nativeFailure; }
}
