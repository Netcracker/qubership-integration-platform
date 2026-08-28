package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.events.EventPublisher;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Ticket 03: persist one create-chain Flow instance, restore it from PostgreSQL, and resume the
 * opening input listen with a correlated in-process event.
 */
@QuarkusTest
class DurableCreateChainFlowInstanceIT {

  private static final URI EVENT_SOURCE = URI.create("urn:qip:create-chain-flow");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ProvidedIdsFlow flow;
  @Inject WorkflowApplication application;
  @Inject DataSource dataSource;
  @Inject PersistenceInstanceHandlers persistenceHandlers;
  @InjectMock ProvidedIdsFlowTasks tasks;

  @Test
  void startCreatesOnePersistedInstanceAndOpeningInputResumesIt() throws Exception {
    ConcurrentHashMap<String, AtomicInteger> executions = new ConcurrentHashMap<>();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              int n =
                  executions
                      .computeIfAbsent(context.runId(), ignored -> new AtomicInteger())
                      .incrementAndGet();
              return context.withDecision(n == 1 ? "WAIT_FOR_INPUT" : "STOP");
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(
            invocation ->
                new ProvidedIdsFlow.RunContext(
                    "run-1", "create-chain", "2", "manifest-sha", "CONTINUE"));

    ProvidedIdsFlow.RunContext context =
        new ProvidedIdsFlow.RunContext("run-1", "create-chain", "2", "manifest-sha", null);
    WorkflowInstance instance = flow.instance(context);
    instance.start();
    waitUntil(
        "create-chain instance must reach the opening input listen",
        () ->
            instance.status() == WorkflowStatus.WAITING
                && executions.getOrDefault("run-1", new AtomicInteger()).get() == 1);

    String flowInstanceId = instance.id();
    assertTrue(persistedInstanceExists(flowInstanceId));

    WorkflowInstance second =
        flow.instance(
            new ProvidedIdsFlow.RunContext("run-2", "create-chain", "2", "manifest-sha", null));
    second.start();
    waitUntil(
        "second create-chain instance must reach the opening input listen",
        () -> second.status() == WorkflowStatus.WAITING);
    assertNotEquals(flowInstanceId, second.id());
    assertTrue(persistedInstanceExists(second.id()));

    publish(resumeEvent("org.qubership.qip.create-chain.unrelated.v1", flowInstanceId));
    waitQuietly(Duration.ofMillis(300));
    assertEquals(WorkflowStatus.WAITING, instance.status());
    assertEquals(1, executions.getOrDefault("run-1", new AtomicInteger()).get());

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId));
    waitUntil(
        "correlated input must resume only the matching create-chain instance",
        () ->
            executions.getOrDefault("run-1", new AtomicInteger()).get() >= 2
                && instance.status() == WorkflowStatus.COMPLETED);
    assertEquals(WorkflowStatus.WAITING, second.status());

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId));
    waitQuietly(Duration.ofMillis(300));
    assertEquals(2, executions.getOrDefault("run-1", new AtomicInteger()).get());
    assertEquals(WorkflowStatus.WAITING, second.status());
    assertTrue(persistedInstanceExists(second.id()));
  }

  @Test
  void sameInstanceLoopsContinueThroughProvidedIdsGatesUntilMaterialized() throws Exception {
    ConcurrentLinkedQueue<String> decisions =
        new ConcurrentLinkedQueue<>(
            List.of(
                "WAIT_FOR_INPUT",
                "CONTINUE",
                "WAIT_FOR_IDS_APPROVAL",
                "WAIT_FOR_IDS_APPROVAL",
                "CONTINUE",
                "WAIT_FOR_PLAN_APPROVAL",
                "CONTINUE",
                "STOP"));
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              executions.incrementAndGet();
              String decision = decisions.poll();
              if (decision == null) {
                throw new IllegalStateException("unexpected extra stage execution");
              }
              return context.withDecision(decision);
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(invocation -> restoreContext(invocation.getArgument(0), "run-gates"));

    ProvidedIdsFlow.RunContext context =
        new ProvidedIdsFlow.RunContext("run-gates", "create-chain", "2", "manifest-sha", null);
    WorkflowInstance instance = flow.instance(context);
    instance.start();
    waitUntil(
        "provided-IDS instance must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING && executions.get() == 1);

    String flowInstanceId = instance.id();
    assertTrue(persistedInstanceExists(flowInstanceId));

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "CONTINUE"));
    waitUntil(
        "Continue after input must skip declared stages then wait for IDS approval",
        () -> executions.get() == 3 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "CONTINUE"));
    waitUntil(
        "clarification at IDS approval must rerun the producing stage on the same instance",
        () -> executions.get() == 4 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(resumeEvent(ProvidedIdsFlow.APPROVAL_EVENT_TYPE, flowInstanceId, "CONTINUE"));
    waitUntil(
        "IDS approval must continue to the plan-approval listen on the same instance",
        () -> executions.get() == 6 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE, flowInstanceId, "WAIT_FOR_IMPLEMENTATION"));
    waitUntil(
        "plan approval must reach a distinct implementation wait on the same instance",
        () -> executions.get() == 6 && instance.status() == WorkflowStatus.WAITING);
    waitQuietly(Duration.ofMillis(200));
    assertEquals(6, executions.get());
    assertEquals(WorkflowStatus.WAITING, instance.status());
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(resumeEvent(ProvidedIdsFlow.IMPLEMENT_EVENT_TYPE, flowInstanceId, "CONTINUE"));
    waitUntil(
        "implementation must resume design execution and complete materialization",
        () -> executions.get() == 8 && instance.status() == WorkflowStatus.COMPLETED);

    assertEquals(flowInstanceId, instance.id());
    publish(resumeEvent(ProvidedIdsFlow.IMPLEMENT_EVENT_TYPE, flowInstanceId, "CONTINUE"));
    waitQuietly(Duration.ofMillis(300));
    assertEquals(8, executions.get());
    assertEquals(WorkflowStatus.COMPLETED, instance.status());
    assertTrue(decisions.isEmpty());
  }

  @Test
  void sameInstanceLoopsContinueThroughGeneratedRouteUntilMaterialized() throws Exception {
    ConcurrentLinkedQueue<String> decisions =
        new ConcurrentLinkedQueue<>(
            List.of(
                "WAIT_FOR_INPUT",
                "CONTINUE",
                "WAIT_FOR_REQUIREMENT_APPROVAL",
                "WAIT_FOR_REQUIREMENT_APPROVAL",
                "CONTINUE",
                "WAIT_FOR_INPUT",
                "CONTINUE",
                "WAIT_FOR_IDS_APPROVAL",
                "CONTINUE",
                "WAIT_FOR_PLAN_APPROVAL",
                "CONTINUE",
                "STOP"));
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              assertPinnedIdentity(context);
              executions.incrementAndGet();
              String decision = decisions.poll();
              if (decision == null) {
                throw new IllegalStateException("unexpected extra generated-route execution");
              }
              return context.withDecision(decision);
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(invocation -> restoreContext(invocation.getArgument(0), "run-generated"));

    ProvidedIdsFlow.RunContext context =
        new ProvidedIdsFlow.RunContext("run-generated", "create-chain", "2", "manifest-sha", null);
    WorkflowInstance instance = flow.instance(context);
    instance.start();
    waitUntil(
        "generated instance must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING && executions.get() == 1);

    String flowInstanceId = instance.id();
    assertTrue(persistedInstanceExists(flowInstanceId));

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-generated", "CONTINUE"));
    waitUntil(
        "requirement work must reach the requirement-approval listen on the same instance",
        () -> executions.get() == 3 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-generated", "CONTINUE"));
    waitUntil(
        "clarification at requirement approval must rerun the producing stage",
        () -> executions.get() == 4 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE, flowInstanceId, "run-generated", "CONTINUE"));
    waitUntil(
        "brief approval must reach the IDS path-choice input listen",
        () -> executions.get() == 6 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-generated", "CONTINUE"));
    waitUntil(
        "GENERATE must author an IDS and wait for IDS approval on the same instance",
        () -> executions.get() == 8 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE, flowInstanceId, "run-generated", "CONTINUE"));
    waitUntil(
        "IDS approval must continue to the shared plan-approval listen",
        () -> executions.get() == 10 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE,
            flowInstanceId,
            "run-generated",
            "WAIT_FOR_IMPLEMENTATION"));
    waitUntil(
        "plan approval must reach the shared implementation wait",
        () -> executions.get() == 10 && instance.status() == WorkflowStatus.WAITING);
    waitQuietly(Duration.ofMillis(200));
    assertEquals(10, executions.get());
    assertEquals(WorkflowStatus.WAITING, instance.status());
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.IMPLEMENT_EVENT_TYPE, flowInstanceId, "run-generated", "CONTINUE"));
    waitUntil(
        "generated route must complete materialization on the original instance",
        () -> executions.get() == 12 && instance.status() == WorkflowStatus.COMPLETED);

    assertEquals(flowInstanceId, instance.id());
    assertTrue(decisions.isEmpty());
  }

  @Test
  void sameInstanceLoopsContinueThroughDerivedRouteUntilMaterialized() throws Exception {
    ConcurrentLinkedQueue<String> decisions =
        new ConcurrentLinkedQueue<>(
            List.of(
                "WAIT_FOR_INPUT",
                "CONTINUE",
                "WAIT_FOR_REQUIREMENT_APPROVAL",
                "CONTINUE",
                "WAIT_FOR_INPUT",
                "CONTINUE",
                "WAIT_FOR_PLAN_APPROVAL",
                "CONTINUE",
                "STOP"));
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              assertPinnedIdentity(context);
              executions.incrementAndGet();
              String decision = decisions.poll();
              if (decision == null) {
                throw new IllegalStateException("unexpected extra derived-route execution");
              }
              return context.withDecision(decision);
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(invocation -> restoreContext(invocation.getArgument(0), "run-derived"));

    ProvidedIdsFlow.RunContext context =
        new ProvidedIdsFlow.RunContext("run-derived", "create-chain", "2", "manifest-sha", null);
    WorkflowInstance instance = flow.instance(context);
    instance.start();
    waitUntil(
        "derived instance must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING && executions.get() == 1);

    String flowInstanceId = instance.id();
    assertTrue(persistedInstanceExists(flowInstanceId));

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-derived", "CONTINUE"));
    waitUntil(
        "derived requirement work must wait for brief approval on the same instance",
        () -> executions.get() == 3 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE, flowInstanceId, "run-derived", "CONTINUE"));
    waitUntil(
        "brief approval must reach the IDS path-choice input listen",
        () -> executions.get() == 5 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-derived", "CONTINUE"));
    waitUntil(
        "DERIVE must produce design artifacts and wait for plan approval without an IDS approval listen",
        () -> executions.get() == 7 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE,
            flowInstanceId,
            "run-derived",
            "WAIT_FOR_IMPLEMENTATION"));
    waitUntil(
        "derived plan approval must reach the shared implementation wait",
        () -> executions.get() == 7 && instance.status() == WorkflowStatus.WAITING);
    waitQuietly(Duration.ofMillis(200));
    assertEquals(7, executions.get());
    assertEquals(WorkflowStatus.WAITING, instance.status());
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.IMPLEMENT_EVENT_TYPE, flowInstanceId, "run-derived", "CONTINUE"));
    waitUntil(
        "derived route must complete materialization on the original instance",
        () -> executions.get() == 9 && instance.status() == WorkflowStatus.COMPLETED);

    assertEquals(flowInstanceId, instance.id());
    assertTrue(decisions.isEmpty());
  }

  @Test
  void restartAtRequirementApprovalPreservesInstanceIdentity() throws Exception {
    ConcurrentLinkedQueue<String> decisions =
        new ConcurrentLinkedQueue<>(List.of("WAIT_FOR_INPUT", "CONTINUE", "WAIT_FOR_REQUIREMENT_APPROVAL"));
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              executions.incrementAndGet();
              String decision = decisions.poll();
              if (decision == null) {
                throw new IllegalStateException("unexpected extra restart execution");
              }
              return context.withDecision(decision);
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(invocation -> restoreContext(invocation.getArgument(0), "run-restart-req"));

    WorkflowInstance instance =
        flow.instance(
            new ProvidedIdsFlow.RunContext(
                "run-restart-req", "create-chain", "2", "manifest-sha", null));
    instance.start();
    waitUntil(
        "restart fixture must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING && executions.get() == 1);

    String flowInstanceId = instance.id();
    publish(
        resumeEvent(
            ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-restart-req", "CONTINUE"));
    waitUntil(
        "restart fixture must reach the requirement-approval listen",
        () -> executions.get() == 3 && instance.status() == WorkflowStatus.WAITING);

    assertEquals(flowInstanceId, instance.id());
    assertTrue(persistedInstanceExists(flowInstanceId));
    assertEquals(WorkflowStatus.WAITING, instance.status());
  }

  @Test
  void restartBetweenStartAndInputPreservesInstanceIdentity() throws Exception {
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              return context.withDecision("WAIT_FOR_INPUT");
            });

    WorkflowInstance instance =
        flow.instance(
            new ProvidedIdsFlow.RunContext(
                "run-restart", "create-chain", "2", "manifest-sha", null));
    instance.start();
    waitUntil(
        "restart fixture must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING);

    String flowInstanceId = instance.id();
    assertTrue(persistedInstanceExists(flowInstanceId));
    assertEquals(WorkflowStatus.WAITING, instance.status());
  }

  @Test
  void scanAllRestoresALoopedInstanceWaitingAtAHumanGate() throws Exception {
    ConcurrentLinkedQueue<String> decisions =
        new ConcurrentLinkedQueue<>(List.of("WAIT_FOR_INPUT", "CONTINUE", "WAIT_FOR_IDS_APPROVAL"));
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              assertPinnedIdentity(context);
              executions.incrementAndGet();
              String decision = decisions.poll();
              if (decision == null) {
                throw new IllegalStateException("unexpected extra looped-restore execution");
              }
              return context.withDecision(decision);
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext restored =
                  restoreContext(invocation.getArgument(0), "run-looped-restore");
              assertPinnedIdentity(restored);
              return restored;
            });

    WorkflowInstance instance =
        flow.instance(
            new ProvidedIdsFlow.RunContext(
                "run-looped-restore", "create-chain", "2", "manifest-sha", null));
    instance.start();
    waitUntil(
        "looped restore fixture must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING && executions.get() == 1);

    String flowInstanceId = instance.id();
    publish(
        resumeEvent(
            ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-looped-restore", "CONTINUE"));
    waitUntil(
        "CONTINUE must re-enter routeDecision and wait at the next human gate",
        () -> executions.get() == 3 && instance.status() == WorkflowStatus.WAITING);

    assertTrue(
        hasDuplicateTaskPointer(flowInstanceId),
        "CONTINUE must persist more than one checkpoint for the same Flow task pointer");

    Set<String> restoredIds;
    try (Stream<WorkflowInstance> restored =
        persistenceHandlers.reader().scanAll(flow.definition())) {
      restoredIds = restored.map(restoredInstance -> restoredInstance.id()).collect(Collectors.toSet());
    }
    assertTrue(
        restoredIds.contains(flowInstanceId),
        "production scanAll restore must keep a looped create-chain instance");

    Optional<WorkflowInstance> restored =
        persistenceHandlers.reader().find(flow.definition(), flowInstanceId);
    assertTrue(restored.isPresent(), "reader.find must restore the looped instance identity");
    assertEquals(flowInstanceId, restored.get().id());
    assertEquals(WorkflowStatus.WAITING, restored.get().status());
  }

  @Test
  void retryWaitsThenReentersTheSamePersistedInstance() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              int n = executions.incrementAndGet();
              if (n == 1) {
                assertNull(context.technicalRetriesUsed());
                return context.withRetry(Duration.ofMillis(400L), 1);
              }
              assertEquals(1, context.technicalRetriesUsed());
              return context.withDecision("STOP");
            });
    when(tasks.restoreAfterRetry(any()))
        .thenAnswer(invocation -> restoreRetryContext(invocation.getArgument(0), "run-retry"));

    WorkflowInstance instance =
        flow.instance(
            new ProvidedIdsFlow.RunContext(
                "run-retry", "create-chain", "2", "manifest-sha", null));
    instance.start();
    waitUntil(
        "retry instance must reach the retry wait after the first technical failure",
        () -> executions.get() == 1 && instance.status() == WorkflowStatus.WAITING);

    String flowInstanceId = instance.id();
    assertTrue(persistedInstanceExists(flowInstanceId));
    waitQuietly(Duration.ofMillis(150));
    assertEquals(1, executions.get(), "retry delay must not re-execute the stage immediately");
    assertEquals(WorkflowStatus.WAITING, instance.status());

    waitUntil(
        "retry wait must re-execute the same instance after the persisted delay",
        () -> executions.get() == 2 && instance.status() == WorkflowStatus.COMPLETED);
    assertEquals(flowInstanceId, instance.id());
    assertEquals(2, executions.get());
  }

  @Test
  void validationFailureLoopsBackToTheOwningApprovalListenOnTheSameInstance() throws Exception {
    ConcurrentLinkedQueue<String> decisions =
        new ConcurrentLinkedQueue<>(
            List.of(
                "WAIT_FOR_INPUT",
                "CONTINUE",
                "WAIT_FOR_REQUIREMENT_APPROVAL",
                "WAIT_FOR_REQUIREMENT_APPROVAL",
                "WAIT_FOR_REQUIREMENT_APPROVAL",
                "CONTINUE",
                "STOP"));
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              executions.incrementAndGet();
              String decision = decisions.poll();
              if (decision == null) {
                throw new IllegalStateException("unexpected extra validation-recovery execution");
              }
              return context.withDecision(decision);
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(invocation -> restoreContext(invocation.getArgument(0), "run-reopen"));

    WorkflowInstance instance =
        flow.instance(
            new ProvidedIdsFlow.RunContext(
                "run-reopen", "create-chain", "2", "manifest-sha", null));
    instance.start();
    waitUntil(
        "validation-recovery instance must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING && executions.get() == 1);

    String flowInstanceId = instance.id();
    publish(
        resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-reopen", "CONTINUE"));
    waitUntil(
        "requirement work must reach the first brief-approval listen",
        () -> executions.get() == 3 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE, flowInstanceId, "run-reopen", "CONTINUE"));
    waitUntil(
        "validation failure must suspend at the owning approval listen instead of re-entering",
        () -> executions.get() == 4 && instance.status() == WorkflowStatus.WAITING);
    waitQuietly(Duration.ofMillis(200));
    assertEquals(4, executions.get(), "reopen must not re-execute the failed stage immediately");
    assertEquals(WorkflowStatus.WAITING, instance.status());
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-reopen", "CONTINUE"));
    waitUntil(
        "clarification at the reopened approval must rerun the producing stage",
        () -> executions.get() == 5 && instance.status() == WorkflowStatus.WAITING);
    assertSamePersistedInstance(instance, flowInstanceId);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE, flowInstanceId, "run-reopen", "CONTINUE"));
    waitUntil(
        "approval of the new candidate must continue only the affected path on the same instance",
        () -> executions.get() == 7 && instance.status() == WorkflowStatus.COMPLETED);

    assertEquals(flowInstanceId, instance.id());
    assertTrue(decisions.isEmpty());

    publish(
        resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-reopen", "CONTINUE"));
    waitQuietly(Duration.ofMillis(300));
    assertEquals(7, executions.get());
    assertEquals(WorkflowStatus.COMPLETED, instance.status());
  }

  @Test
  void restartWhileApprovalIsReopenedPreservesInstanceIdentity() throws Exception {
    ConcurrentLinkedQueue<String> decisions =
        new ConcurrentLinkedQueue<>(
            List.of(
                "WAIT_FOR_INPUT",
                "CONTINUE",
                "WAIT_FOR_REQUIREMENT_APPROVAL",
                "WAIT_FOR_REQUIREMENT_APPROVAL"));
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              executions.incrementAndGet();
              String decision = decisions.poll();
              if (decision == null) {
                throw new IllegalStateException("unexpected extra reopened-approval restart execution");
              }
              return context.withDecision(decision);
            });
    when(tasks.restoreAfterInput(any()))
        .thenAnswer(invocation -> restoreContext(invocation.getArgument(0), "run-reopen-restart"));

    WorkflowInstance instance =
        flow.instance(
            new ProvidedIdsFlow.RunContext(
                "run-reopen-restart", "create-chain", "2", "manifest-sha", null));
    instance.start();
    waitUntil(
        "reopened-approval restart fixture must reach the opening input listen",
        () -> instance.status() == WorkflowStatus.WAITING && executions.get() == 1);

    String flowInstanceId = instance.id();
    publish(
        resumeEvent(
            ProvidedIdsFlow.INPUT_EVENT_TYPE, flowInstanceId, "run-reopen-restart", "CONTINUE"));
    waitUntil(
        "reopened-approval restart fixture must reach the first brief-approval listen",
        () -> executions.get() == 3 && instance.status() == WorkflowStatus.WAITING);

    publish(
        resumeEvent(
            ProvidedIdsFlow.APPROVAL_EVENT_TYPE,
            flowInstanceId,
            "run-reopen-restart",
            "CONTINUE"));
    waitUntil(
        "validation failure must leave the instance waiting at the reopened approval listen",
        () -> executions.get() == 4 && instance.status() == WorkflowStatus.WAITING);

    assertEquals(flowInstanceId, instance.id());
    assertTrue(persistedInstanceExists(flowInstanceId));
    assertEquals(WorkflowStatus.WAITING, instance.status());
    waitQuietly(Duration.ofMillis(200));
    assertEquals(4, executions.get());
  }

  @Test
  void domainFailureEndsWithoutEnteringTheRetryWait() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              executions.incrementAndGet();
              return context.withDecision("STOP");
            });

    WorkflowInstance instance =
        flow.instance(
            new ProvidedIdsFlow.RunContext(
                "run-fail-closed", "create-chain", "2", "manifest-sha", null));
    instance.start();
    waitUntil(
        "fail-closed instance must complete without a retry wait",
        () -> executions.get() == 1 && instance.status() == WorkflowStatus.COMPLETED);
    waitQuietly(Duration.ofMillis(200));
    assertEquals(1, executions.get());
    assertEquals(WorkflowStatus.COMPLETED, instance.status());
  }

  private void assertSamePersistedInstance(WorkflowInstance instance, String flowInstanceId)
      throws Exception {
    assertEquals(flowInstanceId, instance.id());
    assertTrue(persistedInstanceExists(flowInstanceId));
  }

  private boolean persistedInstanceExists(String instanceId) throws Exception {
    return countPersistedInstances(instanceId) == 1;
  }

  private boolean hasDuplicateTaskPointer(String instanceId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT 1
                FROM task_info_entity
                WHERE workflow_instance_id = ?
                GROUP BY json_pointer
                HAVING COUNT(*) > 1
                """)) {
      statement.setString(1, instanceId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private int countPersistedInstances(String instanceId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM workflow_instance_entity
                WHERE instance_id = ?
                """)) {
      statement.setString(1, instanceId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private void publish(CloudEvent event) {
    for (EventPublisher publisher : application.eventPublishers()) {
      publisher.publish(event).toCompletableFuture().join();
    }
  }

  private static ProvidedIdsFlow.RunContext restoreRetryContext(
      Object payload, String defaultRunId) {
    ProvidedIdsFlow.RunContext restored = restoreContext(payload, defaultRunId);
    Integer used = restored.technicalRetriesUsed() == null ? 1 : restored.technicalRetriesUsed();
    return new ProvidedIdsFlow.RunContext(
        restored.runId(),
        restored.profileId(),
        restored.profileVersion(),
        restored.runManifestDigest(),
        "CONTINUE",
        used,
        restored.retryDelay() == null ? "PT0.4S" : restored.retryDelay());
  }

  private static ProvidedIdsFlow.RunContext restoreContext(Object payload, String defaultRunId) {
    Object current = unwrap(payload);
    if (current instanceof ProvidedIdsFlow.RunContext context) {
      return context.decision() == null ? context.withDecision("CONTINUE") : context;
    }
    Map<?, ?> map = asMap(current);
    if (map == null || map.isEmpty()) {
      return new ProvidedIdsFlow.RunContext(
          defaultRunId, "create-chain", "2", "manifest-sha", "CONTINUE");
    }
    Object runId = map.get("runId");
    Object decision = map.get("decision");
    return new ProvidedIdsFlow.RunContext(
        runId == null ? defaultRunId : runId.toString(),
        stringValue(map.get("profileId")),
        stringValue(map.get("profileVersion")),
        stringValue(map.get("runManifestDigest")),
        decision == null ? "CONTINUE" : decision.toString(),
        integerValue(map.get("technicalRetriesUsed")),
        stringValue(map.get("retryDelay")));
  }

  private static Map<?, ?> asMap(Object current) {
    if (current instanceof Map<?, ?> map) {
      return map;
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
      return JSON.convertValue(current, Map.class);
    } catch (Exception e) {
      return Map.of();
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

  private static void assertPinnedIdentity(ProvidedIdsFlow.RunContext context) {
    assertEquals("create-chain", context.profileId());
    assertEquals("2", context.profileVersion());
    assertEquals("manifest-sha", context.runManifestDigest());
  }

  private static CloudEvent resumeEvent(String type, String instanceId) {
    return resumeEvent(type, instanceId, "run-1", null);
  }

  private static CloudEvent resumeEvent(String type, String instanceId, String decision) {
    return resumeEvent(type, instanceId, "run-gates", decision);
  }

  private static CloudEvent resumeEvent(
      String type, String instanceId, String runId, String decision) {
    String body =
        decision == null
            ? "{\"runId\":\""
                + runId
                + "\",\"profileId\":\"create-chain\",\"profileVersion\":\"2\",\"runManifestDigest\":\"manifest-sha\"}"
            : "{\"runId\":\""
                + runId
                + "\",\"profileId\":\"create-chain\",\"profileVersion\":\"2\",\"runManifestDigest\":\"manifest-sha\",\"decision\":\""
                + decision
                + "\"}";
    return CloudEventBuilder.v1()
        .withId(UUID.randomUUID().toString())
        .withSource(EVENT_SOURCE)
        .withType(type)
        .withExtension("flowinstanceid", instanceId)
        .withData("application/json", body.getBytes(StandardCharsets.UTF_8))
        .build();
  }

  private static void waitUntil(String message, BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      waitQuietly(Duration.ofMillis(50));
    }
    throw new AssertionError(message);
  }

  private static void waitQuietly(Duration duration) {
    LockSupport.parkNanos(duration.toNanos());
    if (Thread.interrupted()) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting");
    }
  }
}
