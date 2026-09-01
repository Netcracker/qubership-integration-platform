package org.qubership.integration.platform.ai.productpipeline.recovery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;

/**
 * Privacy-safe recovery telemetry: category, semantic actions, failure identity, attempt, and
 * outcome. Does not record raw requirements, generated artifacts, or exception payloads.
 *
 * <p>Metric: {@code ai.recovery.outcome} with tags {@code kind}, {@code category}, and {@code
 * outcome}.
 */
@ApplicationScoped
public class RecoveryOutcomeTelemetry {

  private static final Logger LOG = Logger.getLogger(RecoveryOutcomeTelemetry.class);

  public static final String METRIC_NAME = "ai.recovery.outcome";
  public static final String KIND_PRESENTED = "presented";
  public static final String KIND_SELECTED = "selected";
  public static final String KIND_OUTCOME = "outcome";
  public static final String OUTCOME_NO_PROGRESS = "no_progress";
  public static final String OUTCOME_PARTIAL_PROGRESS = "partial_progress";
  public static final String OUTCOME_SUCCESS = "success";
  public static final String OUTCOME_USER_EXIT = "user_exit";

  public record Event(
      String kind,
      String runId,
      String category,
      List<String> offeredActions,
      String selectedAction,
      String failureIdentity,
      int attempt,
      Boolean identityChanged,
      String outcome,
      boolean reachedMaterialization) {

    public Event {
      offeredActions = offeredActions == null ? List.of() : List.copyOf(offeredActions);
    }
  }

  private final MeterRegistry meterRegistry;
  private final List<Event> events;
  private final ConcurrentHashMap<String, OpenDialog> openByRun = new ConcurrentHashMap<>();

  @Inject
  public RecoveryOutcomeTelemetry(MeterRegistry meterRegistry) {
    this(meterRegistry, null);
  }

  /** Test helper without CDI. Collects events for assertions. */
  public RecoveryOutcomeTelemetry() {
    this(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), new CopyOnWriteArrayList<>());
  }

  RecoveryOutcomeTelemetry(MeterRegistry meterRegistry, List<Event> events) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.events = events;
  }

  public List<Event> events() {
    return events == null ? List.of() : List.copyOf(events);
  }

  /**
   * Records that a recovery dialog was shown. {@code unusedEvidence} is accepted so callers cannot
   * accidentally treat raw exception text as a field of the event; it is discarded.
   */
  public void recordPresented(
      String runId, String gateId, String failureIdentity, String unusedEvidence) {
    if (runId == null || runId.isBlank() || !PipelineGates.isContextualRecoveryGate(gateId)) {
      return;
    }
    String category = ChatEvent.recoveryCategoryOf(gateId);
    if (category == null) {
      return;
    }
    List<String> offered = offeredActions(gateId);
    String identity = failureIdentity == null ? "" : failureIdentity;
    OpenDialog previous = openByRun.get(runId);
    int attempt = 1;
    if (previous != null) {
      boolean changed = !identity.equals(previous.identity);
      emitOutcome(
          previous,
          changed ? OUTCOME_PARTIAL_PROGRESS : OUTCOME_NO_PROGRESS,
          changed,
          false);
      attempt = previous.attempt + 1;
    }
    OpenDialog open = new OpenDialog(runId, category, offered, identity, attempt, false, null);
    openByRun.put(runId, open);
    emit(
        new Event(
            KIND_PRESENTED,
            runId,
            category,
            offered,
            null,
            identity,
            attempt,
            null,
            null,
            false));
    LOG.infof(
        "recovery presented: runId=%s, category=%s, attempt=%d, identity=%s",
        runId, category, attempt, identity);
  }

  public void recordSelected(String runId, String semanticAction) {
    if (runId == null || runId.isBlank() || semanticAction == null || semanticAction.isBlank()) {
      return;
    }
    OpenDialog open = openByRun.get(runId);
    if (open == null || open.selected) {
      return;
    }
    open.selected = true;
    open.selectedAction = semanticAction;
    emit(
        new Event(
            KIND_SELECTED,
            runId,
            open.category,
            open.offered,
            semanticAction,
            open.identity,
            open.attempt,
            null,
            null,
            false));
    LOG.infof(
        "recovery selected: runId=%s, category=%s, action=%s, attempt=%d",
        runId, open.category, semanticAction, open.attempt);
  }

  public void recordSuccess(String runId) {
    OpenDialog open = runId == null ? null : openByRun.remove(runId);
    if (open == null) {
      return;
    }
    emitOutcome(open, OUTCOME_SUCCESS, null, true);
  }

  public void recordUserExit(String runId) {
    if (runId == null || runId.isBlank()) {
      return;
    }
    OpenDialog open = openByRun.get(runId);
    if (open == null) {
      return;
    }
    if (!open.selected) {
      recordSelected(runId, PipelineGates.STOP_WITH_REPORT_ACTION);
      open = openByRun.get(runId);
    }
    openByRun.remove(runId);
    if (open != null) {
      emitOutcome(open, OUTCOME_USER_EXIT, null, false);
    }
  }

  public String semanticAction(String gateId, String pipelineAction) {
    if (ChatEvent.RETRY_CREATION_ACTION.equals(pipelineAction)
        || ChatEvent.EDIT_REQUIREMENTS_ACTION.equals(pipelineAction)
        || ChatEvent.REBUILD_PLAN_ACTION.equals(pipelineAction)
        || PipelineGates.STOP_WITH_REPORT_ACTION.equals(pipelineAction)) {
      return pipelineAction;
    }
    if (PipelineGates.RETRY_ACTION.equals(pipelineAction)
        && (PipelineGates.RECOVERY_RETRY_TECHNICAL.equals(gateId)
            || PipelineGates.RECOVERY_REGENERATE_EXECUTION.equals(gateId))) {
      return ChatEvent.RETRY_CREATION_ACTION;
    }
    if (PipelineGates.REVISE_ACTION.equals(pipelineAction)
        && PipelineGates.RECOVERY_REVISE_BRIEF.equals(gateId)) {
      return ChatEvent.EDIT_REQUIREMENTS_ACTION;
    }
    if (PipelineGates.REVISE_ACTION.equals(pipelineAction)
        && PipelineGates.RECOVERY_REBUILD_PLAN.equals(gateId)) {
      return ChatEvent.REBUILD_PLAN_ACTION;
    }
    return pipelineAction;
  }

  private void emitOutcome(
      OpenDialog open, String outcome, Boolean identityChanged, boolean materialized) {
    emit(
        new Event(
            KIND_OUTCOME,
            open.runId,
            open.category,
            open.offered,
            open.selectedAction,
            open.identity,
            open.attempt,
            identityChanged,
            outcome,
            materialized));
    LOG.infof(
        "recovery outcome: runId=%s, category=%s, outcome=%s, attempt=%d, identityChanged=%s, materialized=%s",
        open.runId, open.category, outcome, open.attempt, identityChanged, materialized);
  }

  private void emit(Event event) {
    if (events != null) {
      events.add(event);
    }
    String outcome = event.outcome() == null ? "-" : event.outcome();
    Counter.builder(METRIC_NAME)
        .description("Contextual recovery dialog presentation, selection, and outcome")
        .tag("kind", event.kind())
        .tag("category", event.category() == null ? "-" : event.category())
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  private static List<String> offeredActions(String gateId) {
    List<String> actions = ChatEvent.actionsForGate(gateId);
    return actions == null ? List.of() : actions;
  }

  private static final class OpenDialog {
    private final String runId;
    private final String category;
    private final List<String> offered;
    private final String identity;
    private final int attempt;
    private boolean selected;
    private String selectedAction;

    private OpenDialog(
        String runId,
        String category,
        List<String> offered,
        String identity,
        int attempt,
        boolean selected,
        String selectedAction) {
      this.runId = runId;
      this.category = category;
      this.offered = offered;
      this.identity = identity;
      this.attempt = attempt;
      this.selected = selected;
      this.selectedAction = selectedAction;
    }
  }
}
