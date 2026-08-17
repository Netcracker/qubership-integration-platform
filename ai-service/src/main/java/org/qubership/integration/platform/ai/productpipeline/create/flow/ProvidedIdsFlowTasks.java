package org.qubership.integration.platform.ai.productpipeline.create.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.serverlessworkflow.impl.WorkflowModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.qubership.integration.platform.ai.productpipeline.runtime.PipelineSignal;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutionResult;

/** Executes one profile stage per Flow task and records live signals for the current command. */
@ApplicationScoped
public class ProvidedIdsFlowTasks {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String CONTINUE = "CONTINUE";

  private final ProductPipelineRunSupport runSupport;
  private final Map<String, List<PipelineSignal>> signalsByRun = new ConcurrentHashMap<>();
  private final Map<String, ProvidedIdsFlow.RunContext> contextByRun = new ConcurrentHashMap<>();
  private final Set<String> settledRuns = ConcurrentHashMap.newKeySet();

  @Inject
  public ProvidedIdsFlowTasks(ProductPipelineRunSupport runSupport) {
    this.runSupport = Objects.requireNonNull(runSupport, "runSupport");
  }

  ProvidedIdsFlow.RunContext begin(String runId) {
    signalsByRun.put(runId, new CopyOnWriteArrayList<>());
    ProvidedIdsFlow.RunContext context =
        new ProvidedIdsFlow.RunContext(runId, null, null, null, null);
    contextByRun.put(runId, context);
    return context;
  }

  public ProvidedIdsFlow.RunContext executeCurrentStage(ProvidedIdsFlow.RunContext input) {
    Objects.requireNonNull(input, "input");
    runSupport.ensureDurablePinsLoaded(input.runId());
    String stageId = runSupport.currentStageId(input.runId());
    int used = input.technicalRetriesUsed() == null ? 0 : input.technicalRetriesUsed();
    runSupport.restoreTechnicalRetryCount(input.runId(), stageId, used);
    StageExecutionResult result =
        runSupport.stageExecutor().execute(input.runId(), stageId).await().indefinitely();
    List<PipelineSignal> lifecycle =
        runSupport.applyStageLifecycle(input.runId(), result).collect().asList().await().indefinitely();
    // Lifecycle already includes stage signals plus review text inserted in front of a wait.
    // Appending those extras onto result.signals() puts the review after WaitingForApproval,
    // and chat then replaces the narrative with the compact Goal/Facts block.
    List<PipelineSignal> combined =
        lifecycle.isEmpty() ? result.signals() : lifecycle;
    signalsByRun.computeIfAbsent(input.runId(), ignored -> new CopyOnWriteArrayList<>()).addAll(combined);
    ProvidedIdsFlow.RunContext next = nextContext(input, result.decision(), used);
    contextByRun.put(input.runId(), next);
    markSettled(input.runId(), next.decision());
    return next;
  }

  public ProvidedIdsFlow.RunContext restoreAfterInput(Object payload) {
    ProvidedIdsFlow.RunContext restored = restoreContext(payload);
    String decision = restored.decision() == null ? CONTINUE : restored.decision();
    ProvidedIdsFlow.RunContext next = restored.withDecision(decision);
    contextByRun.put(next.runId(), next);
    markSettled(next.runId(), decision);
    return next;
  }

  public ProvidedIdsFlow.RunContext restoreAfterRetry(Object payload) {
    ProvidedIdsFlow.RunContext restored = restoreContext(payload);
    ProvidedIdsFlow.RunContext next = restored.withContinueKeepingRetries();
    contextByRun.put(next.runId(), next);
    markSettled(next.runId(), next.decision());
    return next;
  }

  Result finish(ProvidedIdsFlow.RunContext input) {
    if (input == null) {
      return new Result(List.of());
    }
    return new Result(drainSignals(input.runId()));
  }

  void discard(ProvidedIdsFlow.RunContext input) {
    if (input != null) {
      signalsByRun.remove(input.runId());
    }
  }

  List<PipelineSignal> drainSignals(String runId) {
    settledRuns.remove(runId);
    List<PipelineSignal> signals = signalsByRun.remove(runId);
    return signals == null ? List.of() : List.copyOf(signals);
  }

  boolean settled(String runId) {
    return settledRuns.contains(runId);
  }

  private void markSettled(String runId, String decision) {
    if (reenters(decision)) {
      settledRuns.remove(runId);
    } else {
      settledRuns.add(runId);
    }
  }

  private ProvidedIdsFlow.RunContext restoreContext(Object payload) {
    if (payload instanceof ProvidedIdsFlow.RunContext context) {
      return context;
    }
    Map<String, Object> map = asMap(payload);
    Object runId = map.get("runId");
    if (runId == null) {
      throw new IllegalStateException("cannot restore create-chain Flow context from " + payload);
    }
    String decision = stringValue(map.get("decision"));
    Integer retriesUsed = integerValue(map.get("technicalRetriesUsed"));
    String retryDelay = stringValue(map.get("retryDelay"));
    ProvidedIdsFlow.RunContext stored = contextByRun.get(runId.toString());
    if (stored != null) {
      return new ProvidedIdsFlow.RunContext(
          stored.runId(),
          stored.profileId(),
          stored.profileVersion(),
          stored.runManifestDigest(),
          decision,
          retriesUsed != null ? retriesUsed : stored.technicalRetriesUsed(),
          retryDelay != null ? retryDelay : stored.retryDelay());
    }
    return new ProvidedIdsFlow.RunContext(
        runId.toString(),
        stringValue(map.get("profileId")),
        stringValue(map.get("profileVersion")),
        stringValue(map.get("runManifestDigest")),
        decision,
        retriesUsed,
        retryDelay);
  }

  private static Map<String, Object> asMap(Object payload) {
    Object current = unwrap(payload);
    if (current instanceof ProvidedIdsFlow.RunContext context) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("runId", context.runId());
      map.put("profileId", context.profileId());
      map.put("profileVersion", context.profileVersion());
      map.put("runManifestDigest", context.runManifestDigest());
      map.put("decision", context.decision());
      map.put("technicalRetriesUsed", context.technicalRetriesUsed());
      map.put("retryDelay", context.retryDelay());
      return map;
    }
    if (current instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, value) -> copy.put(String.valueOf(key), value));
      return copy;
    }
    if (current instanceof List<?> list && !list.isEmpty()) {
      return asMap(list.get(0));
    }
    if (current instanceof Collection<?> collection && !collection.isEmpty()) {
      return asMap(collection.iterator().next());
    }
    try {
      if (current instanceof String text) {
        return asMap(JSON.readValue(text, Object.class));
      }
      if (current instanceof byte[] bytes) {
        return asMap(JSON.readValue(bytes, Object.class));
      }
      if (current instanceof JsonNode node) {
        return asMap(JSON.convertValue(node, Object.class));
      }
      return JSON.convertValue(current, MAP);
    } catch (Exception e) {
      throw new IllegalStateException("cannot restore create-chain Flow context from " + payload, e);
    }
  }

  private static Object unwrap(Object payload) {
    if (payload instanceof WorkflowModel model) {
      Object javaObject = model.asJavaObject();
      if (javaObject != null && javaObject != payload) {
        return unwrap(javaObject);
      }
    }
    if (payload instanceof List<?> list && !list.isEmpty()) {
      return unwrap(list.get(0));
    }
    if (payload instanceof Collection<?> collection && !collection.isEmpty()) {
      return unwrap(collection.iterator().next());
    }
    return payload;
  }

  private static ProvidedIdsFlow.RunContext nextContext(
      ProvidedIdsFlow.RunContext input, StageDecision decision, int usedBeforeAttempt) {
    if (decision instanceof StageDecision.Retry retry) {
      return input.withRetry(retry.delay(), usedBeforeAttempt + 1);
    }
    return input.withDecision(decisionName(decision));
  }

  private static String decisionName(StageDecision decision) {
    return switch (decision) {
      case StageDecision.WaitForInput ignored -> "WAIT_FOR_INPUT";
      case StageDecision.WaitForApproval wait -> approvalDecision(wait.stageId());
      case StageDecision.WaitForImplementation ignored -> "WAIT_FOR_IMPLEMENTATION";
      case StageDecision.Retry ignored -> "RETRY";
      case StageDecision.ReopenApproval reopen -> approvalDecision(reopen.approvalStageId());
      case StageDecision.Continue ignored -> CONTINUE;
      default -> "STOP";
    };
  }

  private static String approvalDecision(String stageId) {
    if ("requirement-analysis".equals(stageId)) {
      return "WAIT_FOR_REQUIREMENT_APPROVAL";
    }
    if ("design-input".equals(stageId)) {
      return "WAIT_FOR_IDS_APPROVAL";
    }
    if ("design-planning".equals(stageId)) {
      return "WAIT_FOR_PLAN_APPROVAL";
    }
    return "STOP";
  }

  private static boolean reenters(String decision) {
    return CONTINUE.equals(decision) || "RETRY".equals(decision);
  }

  private static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }

  private static Integer integerValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.valueOf(value.toString());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  record Result(List<PipelineSignal> signals) {}
}
