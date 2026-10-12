package org.qubership.integration.platform.ai.compiler.capture;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

/** Runs agent chat streams with a separate repair allowance for each rejection. */
@ApplicationScoped
public class CaptureRepairRunner {

  private static final Logger LOG = Logger.getLogger(CaptureRepairRunner.class);

  // A stream of distinct rejections must not create an unbounded model-call loop.
  static final int MAX_TOTAL_REPAIR_ATTEMPTS = 12;

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
    Context toolSessionContext = ToolSession.attachedContext();
    Context toolSinkContext = ToolInvocationSink.attachedContext();
    Function<String, Multi<String>> boundAgentChat =
        message ->
            ToolInvocationSink.propagateBinding(
                toolSinkContext,
                ToolSession.propagateBinding(toolSessionContext, agentChat.apply(message)));
    return Multi.createFrom()
        .deferred(
            () -> runAttempt(
                boundAgentChat,
                captureAccepted,
                lastFailure,
                onToolArgumentsFailure,
                captureToolName,
                initialUserMessage,
                initialUserMessage,
                0,
                boundedBudget,
                new HashMap<>(),
                retryValidationFailures,
                repairMessageFactory,
                onBeforeRepairRetry));
  }

  private Multi<String> runAttempt(
      Function<String, Multi<String>> agentChat,
      BooleanSupplier captureAccepted,
      Supplier<Optional<CaptureAttemptFeedback>> lastFailure,
      Runnable onToolArgumentsFailure,
      String captureToolName,
      String initialUserMessage,
      String userMessage,
      int repairIndex,
      int repairAttemptsBudget,
      Map<String, Integer> repairsByFailure,
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
                  initialUserMessage,
                  repairIndex,
                  repairAttemptsBudget,
                  repairsByFailure,
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
                  initialUserMessage,
                  repairIndex,
                  repairAttemptsBudget,
                  repairsByFailure,
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
      String initialUserMessage,
      int repairIndex,
      int repairAttemptsBudget,
      Map<String, Integer> repairsByFailure,
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
          initialUserMessage,
          repairIndex,
          repairAttemptsBudget,
          repairsByFailure,
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
      String initialUserMessage,
      int repairIndex,
      int repairAttemptsBudget,
      Map<String, Integer> repairsByFailure,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry) {
    if (captureAccepted.getAsBoolean()) {
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
        initialUserMessage,
        repairIndex,
        repairAttemptsBudget,
        repairsByFailure,
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
      String initialUserMessage,
      int repairIndex,
      int repairAttemptsBudget,
      Map<String, Integer> repairsByFailure,
      boolean retryValidationFailures,
      Function<CaptureAttemptFeedback, String> repairMessageFactory,
      Runnable onBeforeRepairRetry) {
    if (repairIndex >= MAX_TOTAL_REPAIR_ATTEMPTS) {
      return Multi.createFrom().empty();
    }
    Optional<CaptureAttemptFeedback> failure = lastFailure.get();
    if (failure.isEmpty()) {
      return Multi.createFrom().empty();
    }
    if (!outerAllowed(failure.get(), captureToolName)) {
      return Multi.createFrom().empty();
    }
    String failureKey =
        failure.get().kind().name() + '\u0000'
            + (failure.get().summary() == null ? "" : failure.get().summary().strip());
    int used = repairsByFailure.getOrDefault(failureKey, 0);
    if (used >= repairAttemptsBudget) {
      return Multi.createFrom().empty();
    }
    repairsByFailure.put(failureKey, used + 1);
    String repairMessage =
        repairMessageFactory != null
            ? QuteUserMessageEscaping.escapeForAiServiceUserMessage(repairMessageFactory.apply(failure.get())
                + org.qubership.integration.platform.ai.productpipeline.recovery.RepairHistory.block(failure.get().repairHistoryJson()))
            : initialUserMessage + "\n\n"
                + QuteUserMessageEscaping.escapeForAiServiceUserMessage(messageBuilder.build(failure.get(), captureToolName));
    LOG.infof(
        "Capture repair retry after validation failure on %s"
            + " (repairIndex=%d, failureAttempt=%d, detail=%s)",
        captureToolName,
        repairIndex + 1,
        used + 1,
        AiTraceLog.preview(failure.get().summary(), 500));
    onBeforeRepairRetry.run();
    return runAttempt(
        agentChat,
        captureAccepted,
        lastFailure,
        onToolArgumentsFailure,
        captureToolName,
        initialUserMessage,
        repairMessage,
        repairIndex + 1,
        repairAttemptsBudget,
        repairsByFailure,
        retryValidationFailures,
        repairMessageFactory,
        onBeforeRepairRetry);
  }

  /**
   * Whether the failure matrix still permits a repair turn (ADR 0005).
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
