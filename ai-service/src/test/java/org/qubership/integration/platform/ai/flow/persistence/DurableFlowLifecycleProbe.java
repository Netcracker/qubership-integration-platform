package org.qubership.integration.platform.ai.flow.persistence;

import static io.quarkiverse.flow.dsl.FlowDSL.consumed;
import static io.quarkiverse.flow.dsl.FlowDSL.listen;
import static io.quarkiverse.flow.dsl.FlowDSL.toOne;
import static io.quarkiverse.flow.dsl.FlowDSL.withInstanceId;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Representative listen/resume workflow used to prove durable Flow suspension without changing
 * create-chain runtime ownership.
 */
@ApplicationScoped
public class DurableFlowLifecycleProbe extends Flow {

  static final String NAMESPACE = "qip";
  static final String NAME = "durable-flow-lifecycle-probe";
  static final String VERSION = "1.0.0";
  static final String RESUME_EVENT_TYPE = "org.qubership.qip.flow.lifecycle.resume.v1";

  private static final ConcurrentHashMap<String, AtomicInteger> STARTED = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, AtomicInteger> RESUMED = new ConcurrentHashMap<>();

  static void reset() {
    STARTED.clear();
    RESUMED.clear();
  }

  static int startedCount(String instanceId) {
    return STARTED.getOrDefault(instanceId, new AtomicInteger()).get();
  }

  static int resumedCount(String instanceId) {
    return RESUMED.getOrDefault(instanceId, new AtomicInteger()).get();
  }

  @Override
  public Workflow descriptor() {
    return workflow(NAME, NAMESPACE, VERSION)
        .tasks(
            withInstanceId("markStarted", DurableFlowLifecycleProbe::markStarted, ProbeInput.class),
            listen(
                "waitResume",
                toOne(consumed(RESUME_EVENT_TYPE).extensionByInstanceId("flowinstanceid"))),
            withInstanceId("markResumed", DurableFlowLifecycleProbe::markResumed, Object.class))
        .build();
  }

  private static ProbeInput markStarted(String instanceId, ProbeInput input) {
    STARTED.computeIfAbsent(instanceId, ignored -> new AtomicInteger()).incrementAndGet();
    return input;
  }

  private static Object markResumed(String instanceId, Object payload) {
    RESUMED.computeIfAbsent(instanceId, ignored -> new AtomicInteger()).incrementAndGet();
    return payload;
  }

  record ProbeInput(String probe) {}
}
