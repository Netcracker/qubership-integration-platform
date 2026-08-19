package org.qubership.integration.platform.ai.compiler.capture;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Runs agent chat streams with bounded capture repair turns. */
@ApplicationScoped
public class CaptureRepairRunner {

  private static final Logger LOG = Logger.getLogger(CaptureRepairRunner.class);

  private final CaptureRepairMessageBuilder messageBuilder;
  private final int maxRepairAttempts;

  @Inject
  public CaptureRepairRunner(
      CaptureRepairMessageBuilder messageBuilder,
      CaptureAttemptFeedbackStore feedbackStore,
      AppConfig appConfig) {
    this.messageBuilder = messageBuilder;
    this.maxRepairAttempts = appConfig.capture().maxRepairAttempts();
  }

  CaptureRepairRunner(
      CaptureRepairMessageBuilder messageBuilder,
      CaptureAttemptFeedbackStore feedbackStore,
      int maxRepairAttempts) {
    this.messageBuilder = messageBuilder;
    this.maxRepairAttempts = maxRepairAttempts;
  }

  public Multi<String> runWithRepair(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      String initialUserMessage) {
    return runWithRepair(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        initialUserMessage,
        true,
        null,
        () -> {});
  }

  public Multi<String> runWithRepair(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      String initialUserMessage,
      boolean retryValidationFailures) {
    return runWithRepair(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        initialUserMessage,
        retryValidationFailures,
        null,
        () -> {});
  }

  public Multi<String> runWithRepair(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      String initialUserMessage,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory) {
    return runWithRepair(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        initialUserMessage,
        retryValidationFailures,
        repairMessageFactory,
        () -> {});
  }

  public Multi<String> runWithRepair(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      String initialUserMessage,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry) {
    return runWithRepair(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        initialUserMessage,
        retryValidationFailures,
        repairMessageFactory,
        onBeforeRepairRetry,
        maxRepairAttempts);
  }

  public Multi<String> runWithRepair(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      String initialUserMessage,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry,
      int repairAttemptsBudget) {
    int boundedBudget = Math.max(0, repairAttemptsBudget);
    return runAttempt(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        initialUserMessage,
        0,
        boundedBudget,
        retryValidationFailures,
        repairMessageFactory,
        onBeforeRepairRetry);
  }

  private Multi<String> runAttempt(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      String userMessage,
      int repairIndex,
      int repairAttemptsBudget,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry) {
    AtomicBoolean validationFailureRecovered = new AtomicBoolean(false);
    return agentChat
        .apply(userMessage)
        .onFailure()
        .recoverWithMulti(
            error -> {
              if (isCaptureValidationFailure(error)) {
                validationFailureRecovered.set(true);
              }
              return recoverAfterStreamFailure(
                  error,
                  agentChat,
                  captureAccepted,
                  lastFailure,
                  onToolArgumentsFailure,
                  captureToolName,
                  repairIndex,
                  repairAttemptsBudget,
                  retryValidationFailures,
                  repairMessageFactory,
                  onBeforeRepairRetry);
            })
        .onCompletion()
        .switchTo(
            () -> {
              if (validationFailureRecovered.get()) {
                return Multi.createFrom().empty();
              }
              return continueAfterSuccessfulStream(
                  agentChat,
                  captureAccepted,
                  lastFailure,
                  onToolArgumentsFailure,
                  captureToolName,
                  repairIndex,
                  repairAttemptsBudget,
                  retryValidationFailures,
                  repairMessageFactory,
                  onBeforeRepairRetry);
            });
  }

  private Multi<String> recoverAfterStreamFailure(
      Throwable error,
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      int repairIndex,
      int repairAttemptsBudget,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry) {
    if (isCaptureValidationFailure(error)) {
      if (captureAccepted.getAsBoolean()) {
        return Multi.createFrom().empty();
      }
      if (!retryValidationFailures) {
        return Multi.createFrom().empty();
      }
      return retryAfterValidationFailure(
          agentChat,
          captureAccepted,
          lastFailure,
          onToolArgumentsFailure,
          captureToolName,
          repairIndex,
          repairAttemptsBudget,
          retryValidationFailures,
          repairMessageFactory,
          onBeforeRepairRetry);
    }
    if (!ToolArgumentsFailures.isToolArgumentsFailure(error)) {
      return Multi.createFrom().failure(error);
    }
    onToolArgumentsFailure.run();
    LOG.warnf(
        "Capture repair skipped after ToolArgumentsException on %s because the failed tool call"
            + " may already be in chat history (repairIndex=%d)",
        captureToolName,
        repairIndex);
    return Multi.createFrom().failure(error);
  }

  private Multi<String> continueAfterSuccessfulStream(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      int repairIndex,
      int repairAttemptsBudget,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry) {
    if (captureAccepted.getAsBoolean()) {
      return Multi.createFrom().empty();
    }
    if (repairIndex >= repairAttemptsBudget) {
      return Multi.createFrom().empty();
    }
    Optional<CaptureAttemptFeedback> failure = lastFailure.get();
    if (failure.isEmpty()) {
      return Multi.createFrom().empty();
    }
    if (failure.get().kind() == CaptureFailureKind.TOOL_ARGUMENTS) {
      return Multi.createFrom().empty();
    }
    if (!outerAllowed(failure.get(), captureToolName)) {
      return Multi.createFrom().empty();
    }
    if (!retryValidationFailures) {
      return Multi.createFrom().empty();
    }
    return retryAfterValidationFailure(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        repairIndex,
        repairAttemptsBudget,
        retryValidationFailures,
        repairMessageFactory,
        onBeforeRepairRetry);
  }

  private Multi<String> retryAfterValidationFailure(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      int repairIndex,
      int repairAttemptsBudget,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry) {
    if (repairIndex >= repairAttemptsBudget) {
      return Multi.createFrom().empty();
    }
    Optional<CaptureAttemptFeedback> failure = lastFailure.get();
    if (failure.isEmpty()) {
      return Multi.createFrom().empty();
    }
    if (!outerAllowed(failure.get(), captureToolName)) {
      return Multi.createFrom().empty();
    }
    String repairMessage =
        repairMessageFactory != null
            ? repairMessageFactory.apply(failure.get())
            : messageBuilder.build(failure.get(), captureToolName);
    LOG.infof(
        "Capture repair retry after validation failure on %s (repairIndex=%d)",
        captureToolName,
        repairIndex + 1);
    onBeforeRepairRetry.run();
    return runAttempt(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        repairMessage,
        repairIndex + 1,
        repairAttemptsBudget,
        retryValidationFailures,
        repairMessageFactory,
        onBeforeRepairRetry);
  }

  /**
   * Whether the failure matrix still permits a repair turn (ADR 0003).
   *
   * <p>A PERMANENT failure cannot be answered by the same skill, and a repeated identical
   * rejection has already spent its in-turn credit. Sending either one another "fix and call the
   * tool again" spends a turn on a request that has already been refused for the same reason.
   */
  private static boolean outerAllowed(CaptureAttemptFeedback failure, String captureToolName) {
    if (failure.outerAllowed()) {
      return true;
    }
    LOG.infof(
        "Capture repair refused on %s because the last failure forbids an outer turn"
            + " (failureClass=%s)",
        captureToolName,
        failure.failureClass());
    return false;
  }

  private static boolean isCaptureValidationFailure(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof CaptureValidationException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
