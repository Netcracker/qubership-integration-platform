package org.qubership.integration.platform.ai.flow.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ticket 01: persist a suspended listen task, restore the same instance identity from PostgreSQL,
 * and resume it with a correlated in-process CloudEvent.
 */
@QuarkusTest
class DurableFlowSuspendRestartIT {

  private static final URI EVENT_SOURCE = URI.create("urn:qip:flow-lifecycle-probe");

  @Inject DurableFlowLifecycleProbe probe;
  @Inject WorkflowApplication application;
  @Inject DataSource dataSource;
  @Inject PersistenceInstanceHandlers persistenceHandlers;

  @BeforeEach
  void resetProbe() {
    DurableFlowLifecycleProbe.reset();
  }

  @Test
  void suspendsPersistsRestoresAndResumesOnlyTheCorrelatedInstance() throws Exception {
    WorkflowInstance first = startUntilListen(new DurableFlowLifecycleProbe.ProbeInput("first"));
    WorkflowInstance second = startUntilListen(new DurableFlowLifecycleProbe.ProbeInput("second"));
    String firstId = first.id();
    String secondId = second.id();

    assertNotEquals(firstId, secondId);
    assertEquals(1, DurableFlowLifecycleProbe.startedCount(firstId));
    assertEquals(1, DurableFlowLifecycleProbe.startedCount(secondId));
    assertEquals(0, DurableFlowLifecycleProbe.resumedCount(firstId));
    assertEquals(0, DurableFlowLifecycleProbe.resumedCount(secondId));
    assertTrue(persistedInstanceExists(firstId), "first instance must be checkpointed in PostgreSQL");
    assertTrue(persistedInstanceExists(secondId), "second instance must be checkpointed in PostgreSQL");

    assertTrue(
        restoredInstanceIdsContain(firstId, secondId),
        "persistence reader must restore both suspended instance identities");

    publish(resumeEvent("org.qubership.qip.flow.lifecycle.unrelated.v1", firstId));
    publish(resumeEvent(DurableFlowLifecycleProbe.RESUME_EVENT_TYPE, "missing-instance"));
    waitQuietly(Duration.ofMillis(300));
    assertEquals(0, DurableFlowLifecycleProbe.resumedCount(firstId));
    assertEquals(0, DurableFlowLifecycleProbe.resumedCount(secondId));
    assertEquals(WorkflowStatus.WAITING, first.status());
    assertEquals(WorkflowStatus.WAITING, second.status());

    publish(resumeEvent(DurableFlowLifecycleProbe.RESUME_EVENT_TYPE, firstId));
    waitUntil(
        "first instance must resume after the correlated event",
        () -> DurableFlowLifecycleProbe.resumedCount(firstId) == 1
            && first.status() == WorkflowStatus.COMPLETED);

    assertEquals(0, DurableFlowLifecycleProbe.resumedCount(secondId));
    assertEquals(WorkflowStatus.WAITING, second.status());

    publish(resumeEvent(DurableFlowLifecycleProbe.RESUME_EVENT_TYPE, firstId));
    waitQuietly(Duration.ofMillis(300));
    assertEquals(1, DurableFlowLifecycleProbe.resumedCount(firstId));
    assertEquals(0, DurableFlowLifecycleProbe.resumedCount(secondId));

    publish(resumeEvent(DurableFlowLifecycleProbe.RESUME_EVENT_TYPE, secondId));
    waitUntil(
        "second instance must resume after its own correlated event",
        () -> DurableFlowLifecycleProbe.resumedCount(secondId) == 1
            && second.status() == WorkflowStatus.COMPLETED);
    assertEquals(1, DurableFlowLifecycleProbe.resumedCount(firstId));
  }

  private WorkflowInstance startUntilListen(DurableFlowLifecycleProbe.ProbeInput input) {
    WorkflowInstance instance = probe.instance(input);
    instance.start();
    waitUntil(
        "instance " + instance.id() + " must reach the listen task",
        () ->
            instance.status() == WorkflowStatus.WAITING
                && DurableFlowLifecycleProbe.startedCount(instance.id()) == 1);
    return instance;
  }

  private boolean restoredInstanceIdsContain(String firstId, String secondId) {
    try (Stream<WorkflowInstance> restored =
        persistenceHandlers.reader().scanAll(probe.definition())) {
      Set<String> ids = restored.map(instance -> instance.id()).collect(Collectors.toSet());
      return ids.contains(firstId) && ids.contains(secondId);
    }
  }

  private boolean persistedInstanceExists(String instanceId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT 1
                FROM workflow_instance_entity
                WHERE instance_id = ?
                """)) {
      statement.setString(1, instanceId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private void publish(CloudEvent event) {
    for (EventPublisher publisher : application.eventPublishers()) {
      publisher.publish(event).toCompletableFuture().join();
    }
  }

  private static CloudEvent resumeEvent(String type, String instanceId) {
    return CloudEventBuilder.v1()
        .withId(UUID.randomUUID().toString())
        .withSource(EVENT_SOURCE)
        .withType(type)
        .withExtension("flowinstanceid", instanceId)
        .withData("application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8))
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
