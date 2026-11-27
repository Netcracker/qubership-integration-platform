package org.qubership.integration.platform.ai.chat;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;

/**
 * Installs a thread binding around each signal of a {@link Multi} or {@link Uni}, on the thread that
 * delivers that signal. The previous binding is restored before the signal returns, so a binding
 * never outlives its signal on a pooled thread.
 */
public final class BindingScope {

  private BindingScope() {}

  /** Installs a binding and returns the action that restores the binding it replaced. */
  @FunctionalInterface
  public interface Installer {
    Runnable install();
  }

  public static <T> Multi<T> wrap(Multi<T> upstream, Installer installer) {
    return Multi.createFrom()
        .emitter(
            emitter -> {
              Runnable restore = installer.install();
              Cancellable subscription;
              try {
                subscription =
                    upstream
                        .subscribe()
                        .with(
                            item -> deliver(installer, () -> emitter.emit(item)),
                            failure -> deliver(installer, () -> emitter.fail(failure)),
                            () -> deliver(installer, emitter::complete));
              } finally {
                restore.run();
              }
              emitter.onTermination(subscription::cancel);
            });
  }

  public static <T> Uni<T> wrap(Uni<T> upstream, Installer installer) {
    return Uni.createFrom()
        .emitter(
            emitter -> {
              Runnable restore = installer.install();
              Cancellable subscription;
              try {
                subscription =
                    upstream
                        .subscribe()
                        .with(
                            item -> deliver(installer, () -> emitter.complete(item)),
                            failure -> deliver(installer, () -> emitter.fail(failure)));
              } finally {
                restore.run();
              }
              emitter.onTermination(subscription::cancel);
            });
  }

  private static void deliver(Installer installer, Runnable signal) {
    Runnable restore = installer.install();
    try {
      signal.run();
    } finally {
      restore.run();
    }
  }
}
