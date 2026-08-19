package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.langchain4j.exception.ToolArgumentsException;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

@ExtendWith(MockitoExtension.class)
class CaptureRepairRunnerTest {

  private CaptureAttemptFeedbackStore feedbackStore;
  private CaptureRepairRunner runner;

  @BeforeEach
  void setUp() {
    feedbackStore = new CaptureAttemptFeedbackStore();
    CaptureRepairMessageBuilder messageBuilder =
        new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class));
    runner = new CaptureRepairRunner(messageBuilder, feedbackStore, 1);
  }

  @Test
  void aFailureThatForbidsTheOuterTurnIsNotRetried() {
    AtomicInteger calls = new AtomicInteger();

    List<String> tokens =
        runner
            .runWithRepair(
                message -> {
                  calls.incrementAndGet();
                  feedbackStore.recordClassifiedPlanFailure(
                      "conv-1",
                      CaptureFailureKind.VALIDATION,
                      CaptureFailureClass.IDENTICAL_SPAM,
                      false,
                      "Structure validation failed:\nremoved existing node '<id>'");
                  return Multi.createFrom().empty();
                },
                () -> false,
                () -> feedbackStore.lastPlanFailure("conv-1"),
                () -> {},
                "captureChainStructure",
                "initial")
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(0, tokens.size());
    assertEquals(1, calls.get());
  }

  @Test
  void retriesAfterValidationFailureUntilCaptured() {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean captured = new AtomicBoolean(false);

    List<String> tokens =
        runner
            .runWithRepair(
                message -> {
                  calls.incrementAndGet();
                  if (calls.get() == 1) {
                    feedbackStore.recordPlanValidationFailure(
                        "conv-1", "Plan validation failed:\nnode error");
                  } else {
                    captured.set(true);
                  }
                  return Multi.createFrom().empty();
                },
                captured::get,
                () -> feedbackStore.lastPlanFailure("conv-1"),
                () -> {},
                "captureChainPlan",
                "initial")
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(0, tokens.size());
    assertEquals(2, calls.get());
    assertTrue(captured.get());
  }

  @Test
  void retriesAfterRepeatedValidationFailureInterruptsStream() {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean captured = new AtomicBoolean(false);

    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              if (calls.get() == 1) {
                feedbackStore.recordPlanValidationFailure(
                    "conv-1", "Plan validation failed:\nnode error");
                return Multi.createFrom()
                    .failure(new CaptureValidationException("Plan validation failed:\nnode error"));
              }
              captured.set(true);
              return Multi.createFrom().empty();
            },
            captured::get,
            () -> feedbackStore.lastPlanFailure("conv-1"),
            () -> {},
            "captureChainPlan",
            "initial")
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(2, calls.get());
    assertTrue(captured.get());
  }

  @Test
  void stopsAfterCaptureWithoutExtraAttempts() {
    AtomicInteger calls = new AtomicInteger();

    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              return Multi.createFrom().item("token");
            },
            () -> true,
            Optional::<CaptureAttemptFeedback>empty,
            () -> {},
            "captureChainPlan",
            "initial")
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(1, calls.get());
  }

  @Test
  void skipsRepairRetryWhenCaptureAlreadyStored() {
    AtomicInteger calls = new AtomicInteger();

    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              return Multi.createFrom()
                  .failure(
                      new CaptureValidationException(
                          "Requirement brief already captured. Do not call captureRequirementBrief"
                              + " again; finish this turn without further tool calls."));
            },
            () -> true,
            () -> feedbackStore.lastPlanFailure("conv-1"),
            () -> {},
            "captureRequirementBrief",
            "initial")
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(1, calls.get());
  }

  @Test
  void recordsToolArgumentsExceptionWithoutRetryingIntoBrokenToolHistory() {
    AtomicInteger calls = new AtomicInteger();

    assertThrows(
        ToolArgumentsException.class,
        () ->
            runner
                .runWithRepair(
                    message -> {
                      calls.incrementAndGet();
                      return Multi.createFrom().failure(new ToolArgumentsException("bad json"));
                    },
                    () -> false,
                    () -> feedbackStore.lastPlanFailure("conv-1"),
                    () ->
                        feedbackStore.recordPlanFailure(
                            "conv-1", CaptureFailureKind.TOOL_ARGUMENTS, "ToolArgumentsException"),
                    "captureChainPlan",
                    "initial")
                .collect()
                .asList()
                .await()
                .indefinitely());

    assertEquals(1, calls.get());
    assertTrue(feedbackStore.lastPlanFailure("conv-1").isPresent());
  }

  @Test
  void skipsFullRetryWhenValidationRepairIsDisabled() {
    AtomicInteger calls = new AtomicInteger();

    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              feedbackStore.recordPlanValidationFailure(
                  "conv-1", "Plan validation failed:\nnode error");
              return Multi.createFrom().empty();
            },
            () -> false,
            () -> feedbackStore.lastPlanFailure("conv-1"),
            () -> {},
            "captureChainPlan",
            "initial",
            false)
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(1, calls.get());
  }

  @Test
  void usesCustomRepairMessageFactory() {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean captured = new AtomicBoolean(false);

    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              if (calls.get() == 1) {
                feedbackStore.recordPlanValidationFailure(
                    "conv-1", "Plan validation failed:\nnode error");
              } else {
                assertEquals("custom-repair", message);
                captured.set(true);
              }
              return Multi.createFrom().empty();
            },
            captured::get,
            () -> feedbackStore.lastPlanFailure("conv-1"),
            () -> {},
            "captureChainPlan",
            "initial",
            true,
            feedback -> "custom-repair")
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(2, calls.get());
    assertTrue(captured.get());
  }

  @Test
  void stopsAtConfiguredRepairLimitWithoutCapture() {
    CaptureRepairRunner boundedRunner =
        new CaptureRepairRunner(
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)),
            feedbackStore,
            2);
    AtomicInteger calls = new AtomicInteger();

    boundedRunner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              feedbackStore.recordPlanValidationFailure(
                  "conv-1", "Plan validation failed:\nnode error");
              return Multi.createFrom().empty();
            },
            () -> false,
            () -> feedbackStore.lastPlanFailure("conv-1"),
            () -> {},
            "captureChainPlan",
            "initial")
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(3, calls.get());
  }

  @Test
  void terminalValidationFailuresUseConfiguredOuterRepairBudget() {
    CaptureRepairRunner boundedRunner =
        new CaptureRepairRunner(
            new CaptureRepairMessageBuilder(mock(DeterministicElementSchemaService.class)),
            feedbackStore,
            2);
    AtomicInteger calls = new AtomicInteger();

    boundedRunner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              feedbackStore.recordPlanValidationFailure(
                  "conv-1", "Plan validation failed:\nnode error");
              return Multi.createFrom()
                  .failure(
                      new CaptureValidationException(
                          "Plan validation failed:\nnode error"));
            },
            () -> false,
            () -> feedbackStore.lastPlanFailure("conv-1"),
            () -> {},
            "captureChainPlan",
            "initial")
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(3, calls.get());
  }

  @Test
  void invokesOnBeforeRepairRetryAfterTerminalValidationFailure() {
    AtomicInteger repairHookCalls = new AtomicInteger();
    AtomicInteger agentCalls = new AtomicInteger();

    runner
        .runWithRepair(
            message -> {
              agentCalls.incrementAndGet();
              if (agentCalls.get() == 1) {
                feedbackStore.recordPatchValidationFailure(
                    "conv-1", "cip-trigger-generator", "invalid HTTP method");
                return Multi.createFrom()
                    .failure(
                        new CaptureValidationException("Repeated graph patch validation failure"));
              }
              return Multi.createFrom().empty();
            },
            () -> false,
            () -> feedbackStore.lastPatchFailure("conv-1", "cip-trigger-generator"),
            () -> {},
            "captureGraphPatch",
            "initial",
            true,
            null,
            repairHookCalls::incrementAndGet)
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(1, repairHookCalls.get());
    assertEquals(2, agentCalls.get());
  }

  @Test
  void invokesOnBeforeRepairRetryAfterSoftValidationFailure() {
    AtomicInteger repairHookCalls = new AtomicInteger();
    AtomicInteger agentCalls = new AtomicInteger();
    AtomicBoolean captured = new AtomicBoolean(false);

    runner
        .runWithRepair(
            message -> {
              agentCalls.incrementAndGet();
              if (agentCalls.get() == 1) {
                feedbackStore.recordPatchValidationFailure(
                    "conv-1", "cip-trigger-generator", "invalid HTTP method");
              } else {
                captured.set(true);
              }
              return Multi.createFrom().empty();
            },
            captured::get,
            () -> feedbackStore.lastPatchFailure("conv-1", "cip-trigger-generator"),
            () -> {},
            "captureGraphPatch",
            "initial",
            true,
            null,
            repairHookCalls::incrementAndGet)
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(1, repairHookCalls.get());
    assertEquals(2, agentCalls.get());
    assertTrue(captured.get());
  }

  @Test
  void respectsExplicitRepairBudgetPerInvocation() {
    AtomicInteger calls = new AtomicInteger();

    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              feedbackStore.recordPatchValidationFailure(
                  "conv-1", "cip-trigger-generator", "invalid HTTP method");
              return Multi.createFrom().empty();
            },
            () -> false,
            () -> feedbackStore.lastPatchFailure("conv-1", "cip-trigger-generator"),
            () -> {},
            "captureGraphPatch",
            "initial",
            true,
            null,
            () -> {},
            1)
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(2, calls.get());
  }

  @Test
  void stopsWithoutRetryWhenNoFeedbackRecorded() {
    AtomicInteger calls = new AtomicInteger();

    runner
        .runWithRepair(
            message -> {
              calls.incrementAndGet();
              return Multi.createFrom().empty();
            },
            () -> false,
            Optional::<CaptureAttemptFeedback>empty,
            () -> {},
            "captureChainPlan",
            "initial")
        .collect()
        .asList()
        .await()
        .indefinitely();

    assertEquals(1, calls.get());
  }
}
