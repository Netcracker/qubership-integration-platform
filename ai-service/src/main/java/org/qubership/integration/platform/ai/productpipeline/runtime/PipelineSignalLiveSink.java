package org.qubership.integration.platform.ai.productpipeline.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Forwards stage {@link PipelineSignal}s to the in-flight create-chain command while Flow is still
 * blocked in {@code waitUntil}. Without this, {@code drainSignals} only reaches chat after the
 * stage finishes — so generator skills appear only after the chain is built.
 */
public final class PipelineSignalLiveSink {

  private static final ConcurrentHashMap<String, Consumer<PipelineSignal>> BY_RUN =
      new ConcurrentHashMap<>();

  private PipelineSignalLiveSink() {}

  public static void bind(String runId, Consumer<PipelineSignal> emit) {
    if (runId == null || runId.isBlank() || emit == null) {
      return;
    }
    BY_RUN.put(runId.trim(), emit);
  }

  public static void unbind(String runId) {
    if (runId == null || runId.isBlank()) {
      return;
    }
    BY_RUN.remove(runId.trim());
  }

  public static boolean isBound(String runId) {
    if (runId == null || runId.isBlank()) {
      return false;
    }
    return BY_RUN.containsKey(runId.trim());
  }

  public static void emit(String runId, PipelineSignal signal) {
    if (runId == null || runId.isBlank() || signal == null) {
      return;
    }
    Consumer<PipelineSignal> emit = BY_RUN.get(runId.trim());
    if (emit != null) {
      emit.accept(signal);
    }
  }
}
