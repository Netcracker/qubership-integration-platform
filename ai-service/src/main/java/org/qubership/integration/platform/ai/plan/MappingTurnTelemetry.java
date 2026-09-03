package org.qubership.integration.platform.ai.plan;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Clarification;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.ConfirmationRequired;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteIntent;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.DeleteRule;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.Query;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.UpdateRule;

/**
 * Privacy-safe mapping-turn telemetry. Records outcome type, operation kinds, counts, clarification
 * reason code, validation result, and latency. Does not record raw prose, schema bodies,
 * expressions, or business values.
 */
@ApplicationScoped
public class MappingTurnTelemetry {

  private static final Logger LOG = Logger.getLogger(MappingTurnTelemetry.class);

  public static final String METRIC_NAME = "ai.mapping.turn";

  public record Event(
      String outcomeType,
      List<String> operationKinds,
      int affectedIntentCount,
      int affectedRuleCount,
      String clarificationReason,
      String validationResult,
      long latencyMs) {

    public Event {
      outcomeType = outcomeType == null ? "NONE" : outcomeType;
      operationKinds = operationKinds == null ? List.of() : List.copyOf(operationKinds);
      clarificationReason = clarificationReason == null ? "" : clarificationReason;
      validationResult = validationResult == null ? "NOT_APPLIED" : validationResult;
    }
  }

  private final MeterRegistry meterRegistry;
  private final List<Event> events;

  @Inject
  public MappingTurnTelemetry(MeterRegistry meterRegistry) {
    this(meterRegistry, null);
  }

  /** Test helper without CDI. Collects events for assertions. */
  public MappingTurnTelemetry() {
    this(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), new CopyOnWriteArrayList<>());
  }

  MappingTurnTelemetry(MeterRegistry meterRegistry, List<Event> events) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.events = events;
  }

  public List<Event> events() {
    return events == null ? List.of() : List.copyOf(events);
  }

  public void record(
      MappingTurnResult result, MappingTurnApplication application, long latencyMs) {
    Event event = toEvent(result, application, latencyMs);
    if (events != null) {
      events.add(event);
    }
    Counter.builder(METRIC_NAME)
        .tag("outcome", event.outcomeType())
        .tag("validation", event.validationResult())
        .register(meterRegistry)
        .increment();
    LOG.infof(
        "mapping turn outcome=%s operations=%s intents=%d rules=%d clarification=%s validation=%s"
            + " latencyMs=%d",
        event.outcomeType(),
        event.operationKinds(),
        event.affectedIntentCount(),
        event.affectedRuleCount(),
        event.clarificationReason(),
        event.validationResult(),
        event.latencyMs());
  }

  static Event toEvent(
      MappingTurnResult result, MappingTurnApplication application, long latencyMs) {
    String outcomeType = outcomeType(result);
    List<String> kinds = operationKinds(result);
    return new Event(
        outcomeType,
        kinds,
        affectedIntentCount(result),
        affectedRuleCount(result),
        clarificationReason(result),
        validationResult(result, application),
        Math.max(0L, latencyMs));
  }

  private static String outcomeType(MappingTurnResult result) {
    return switch (result) {
      case MappingTurnResult.Changes(var operations) -> operations.isEmpty() ? "NONE" : "CHANGES";
      case Query ignored -> "QUERY";
      case Clarification ignored -> "CLARIFICATION";
      case ConfirmationRequired ignored -> "CONFIRMATION_REQUIRED";
      case null -> "NONE";
    };
  }

  private static List<String> operationKinds(MappingTurnResult result) {
    if (!(result instanceof MappingTurnResult.Changes(var operations))) {
      return List.of();
    }
    Set<String> kinds = new LinkedHashSet<>();
    for (MappingTurnResult.Operation operation : operations) {
      kinds.add(kindName(operation));
    }
    return List.copyOf(kinds);
  }

  private static String kindName(MappingTurnResult.Operation operation) {
    return switch (operation) {
      case AddIntent ignored -> "ADD_INTENT";
      case AddRule ignored -> "ADD_RULE";
      case UpdateRule ignored -> "UPDATE_RULE";
      case DeleteRule ignored -> "DELETE_RULE";
      case DeleteIntent ignored -> "DELETE_INTENT";
    };
  }

  private static int affectedIntentCount(MappingTurnResult result) {
    if (!(result instanceof MappingTurnResult.Changes(var operations))) {
      return 0;
    }
    Set<String> intents = new LinkedHashSet<>();
    for (MappingTurnResult.Operation operation : operations) {
      switch (operation) {
        case AddIntent add -> intents.add("add:" + add.sourceRef() + "->" + add.targetRef());
        case AddRule add -> intents.add(add.mappingIntentId());
        case UpdateRule update -> intents.add(update.mappingIntentId());
        case DeleteRule delete -> intents.add(delete.mappingIntentId());
        case DeleteIntent delete -> intents.add(delete.mappingIntentId());
      }
    }
    return intents.size();
  }

  private static int affectedRuleCount(MappingTurnResult result) {
    if (!(result instanceof MappingTurnResult.Changes(var operations))) {
      return 0;
    }
    int count = 0;
    for (MappingTurnResult.Operation operation : operations) {
      switch (operation) {
        case AddIntent add -> count += add.rules().size();
        case AddRule ignored -> count++;
        case UpdateRule ignored -> count++;
        case DeleteRule ignored -> count++;
        case DeleteIntent ignored -> {
          // Intent deletion is counted as an affected intent, not as a rule change.
        }
      }
    }
    return count;
  }

  private static String clarificationReason(MappingTurnResult result) {
    if (result instanceof Clarification(var reason, var ignored)) {
      return sanitizeReason(reason);
    }
    if (result instanceof ConfirmationRequired(var kind, var ignoredId, var ignoredPath)) {
      return kind.name();
    }
    return "";
  }

  /**
   * Keeps a short reason code. Drops free-form prose that might repeat field paths or business
   * values.
   */
  static String sanitizeReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return "AMBIGUOUS";
    }
    String trimmed = reason.trim();
    int space = trimmed.indexOf(' ');
    String token = space < 0 ? trimmed : trimmed.substring(0, space);
    String code = token.replace('-', '_').replaceAll("\\W", "");
    if (code.isEmpty()) {
      return "AMBIGUOUS";
    }
    return code.toUpperCase();
  }

  private static String validationResult(
      MappingTurnResult result, MappingTurnApplication application) {
    if (application != null && application.applied()) {
      return "APPLIED";
    }
    if (result instanceof MappingTurnResult.Changes(var operations) && !operations.isEmpty()) {
      return "REJECTED";
    }
    return "NOT_APPLIED";
  }
}
