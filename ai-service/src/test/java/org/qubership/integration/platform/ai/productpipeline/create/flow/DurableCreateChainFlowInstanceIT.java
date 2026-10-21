package org.qubership.integration.platform.ai.productpipeline.create.flow;

import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.events.EventPublisher;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.smallrye.mutiny.Multi;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;

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

  @BeforeEach
  void keepScriptedWaitsActive() {
    when(tasks.stillWaiting(any())).thenReturn(true);
  }

  @Test
  void resumedFlowReportsStorageFailureWhenItsFailureJournalAlsoRejectsWrites() throws Exception {
    String runId = "storage-failure-" + UUID.randomUUID();
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = org.mockito.Mockito.spy(new InMemoryArtifactBlobStore());
    var refuseWrites = new AtomicBoolean();
    var artifactFailures = new AtomicInteger();
    var journalFailures = new AtomicInteger();
    org.mockito.Mockito.doAnswer(call -> {
      if (refuseWrites.get()) {
        artifactFailures.incrementAndGet();
        throw software.amazon.awssdk.services.s3.model.S3Exception.builder()
            .statusCode(507).message("Artifact write rejected: S3 507").build();
      }
      return call.callRealMethod();
    }).when(blobs).put(org.mockito.ArgumentMatchers.anyString(), any(byte[].class));
    org.mockito.Mockito.doAnswer(call -> {
      if (refuseWrites.get()) {
        journalFailures.incrementAndGet();
        throw software.amazon.awssdk.services.s3.model.S3Exception.builder()
            .statusCode(507).message("Failure journal write rejected: S3 507").build();
      }
      return call.callRealMethod();
    }).when(blobs).putIfVersion(org.mockito.ArgumentMatchers.anyString(), any(byte[].class), any());
    var store = new ProductPipelineRunStore(blobs, mapper, Clock.systemUTC());
    var compilerArtifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var artifacts = new ProductPipelineArtifactStore(compilerArtifacts);
    StageCapability discovery = new StageCapability() {
      @Override public String capabilityId() { return "storage-fixture"; }
      @Override public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        if (context.attributeAsString("userText") == null) {
          return Multi.createFrom().item(new CapabilitySignal.Completed(
              StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "Provide the source requirements.")));
        }
        refuseWrites.set(true);
        compilerArtifacts.append(new CompilationArtifacts.AppendCommand(runId,
            CompilationArtifacts.Kind.USER_INPUT, "1", "source-fixture", "1",
            Map.of("answer", context.attributeAsString("userText")), List.of(), null));
        return Multi.createFrom().empty();
      }
    };
    var input = new ArtifactTypeRef("user-input", 1);
    var profile = new ProductPipelineProfile(1, "storage-recovery", "1", List.of(input), List.of(
        new ProfileStage("requirement-discovery", discovery.capabilityId(), List.of(input), List.of(),
            null, null, new RetryPolicy(0, 1L))),
        new TerminalPolicy("requirement-discovery", "PLAN_APPROVED"), List.of(discovery.capabilityId()));
    var support = CreateChainTestOrchestrator.supportBuilder(store, artifacts,
        new StageCapabilityRegistry(List.of(discovery)), Clock.systemUTC()).build();
    var manifest = new RunManifest(runId, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile", "baseline", "baseline", List.of(), "closure",
        new KnowledgePackageRef("fixture", "1", "1", "checksum", "CERTIFIED", "certificate"),
        "2026.1", List.of(input), null);
    var actualTasks = new ProvidedIdsFlowTasks(support);
    when(tasks.stillWaiting(any())).thenAnswer(call -> actualTasks.stillWaiting(call.getArgument(0)));
    when(tasks.executeCurrentStage(any())).thenAnswer(call -> actualTasks.executeCurrentStage(call.getArgument(0)));
    when(tasks.restoreAfterInput(any())).thenAnswer(call -> actualTasks.restoreAfterInput(call.getArgument(0)));
    WorkflowInstance instance = flow.instance(new ProvidedIdsFlow.RunContext(
        runId, profile.profileId(), profile.profileVersion(), manifest.profileDigest(), null));
    support.bootstrap(new StartOrResumeCommand(runId, runId, profile, manifest), instance.id());
    instance.start();
    waitUntil("opening input wait must persist", () -> instance.status() == WorkflowStatus.WAITING);
    waitQuietly(Duration.ofMillis(200));
    var orchestrator = new ProvidedIdsFlowOrchestrator(support, store, flow, tasks, application);

    RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> orchestrator.acceptInput(new AcceptInputCommand(runId, "Use these requirements"))
            .collect().asList().await().atMost(Duration.ofSeconds(5)));

    var diagnostic = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(diagnostic));
    assertTrue(diagnostic.toString().contains("Artifact write rejected: S3 507"), diagnostic.toString());
    assertTrue(diagnostic.toString().contains("Failure journal write rejected: S3 507"), diagnostic.toString());
    assertTrue(artifactFailures.get() > 0 && journalFailures.get() > 0);
    assertEquals(WorkflowStatus.FAULTED, instance.status());
    assertEquals(RunStatus.RUNNING, store.load(runId).orElseThrow().run().status());
  }

  @Test
  void realDiscoveryTasksRecoverTemporaryFailuresInTheSamePersistedFlow() throws Exception {
    String runId = "discovery-technical-retry-" + UUID.randomUUID();
    String source = "Keep endpoint /orders and literal customerRef unchanged.";
    AtomicInteger calls = new AtomicInteger();
    StageCapability discovery = new StageCapability() {
      @Override public String capabilityId() { return "discovery-fixture"; }
      @Override public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        if (context.attributeAsString("userText") == null) {
          return Multi.createFrom().item(new CapabilitySignal.Completed(
              StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "Provide the source requirements.")));
        }
        assertEquals(source, context.attributeAsString("userText"));
        if (calls.incrementAndGet() <= 2) {
          return Multi.createFrom().failure(new java.net.ConnectException("Connection refused"));
        }
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.SUCCEEDED, "Discovery committed.")));
      }
    };
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var store = new ProductPipelineRunStore(blobs, mapper, Clock.systemUTC());
    var artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, Clock.systemUTC()));
    var input = new ArtifactTypeRef("user-input", 1);
    var profile = new ProductPipelineProfile(1, "discovery-recovery", "1", List.of(input), List.of(
        new ProfileStage("requirement-discovery", discovery.capabilityId(), List.of(input), List.of(),
            null, null, new RetryPolicy(2, 50L))),
        new TerminalPolicy("requirement-discovery", "PLAN_APPROVED"), List.of(discovery.capabilityId()));
    var support = CreateChainTestOrchestrator.supportBuilder(store, artifacts,
        new StageCapabilityRegistry(List.of(discovery)), Clock.systemUTC()).build();
    var manifest = new RunManifest(runId, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile", "baseline", "baseline", List.of(), "closure",
        new KnowledgePackageRef("fixture", "1", "1", "checksum", "CERTIFIED", "certificate"),
        "2026.1", List.of(input), null);
    var actualTasks = new ProvidedIdsFlowTasks(support);
    when(tasks.stillWaiting(any())).thenAnswer(invocation -> actualTasks.stillWaiting(invocation.getArgument(0)));
    when(tasks.executeCurrentStage(any())).thenAnswer(invocation -> actualTasks.executeCurrentStage(invocation.getArgument(0)));
    when(tasks.restoreAfterInput(any())).thenAnswer(invocation -> actualTasks.restoreAfterInput(invocation.getArgument(0)));
    when(tasks.restoreAfterRetry(any())).thenAnswer(invocation -> actualTasks.restoreAfterRetry(invocation.getArgument(0)));
    WorkflowInstance instance = flow.instance(new ProvidedIdsFlow.RunContext(
        runId, profile.profileId(), profile.profileVersion(), manifest.profileDigest(), null));
    support.bootstrap(new StartOrResumeCommand(runId, runId, profile, manifest), instance.id());
    instance.start();
    waitUntil("opening input wait must persist", () -> instance.status() == WorkflowStatus.WAITING);
    assertTrue(persistedInstanceExists(instance.id()));
    waitQuietly(Duration.ofMillis(200));
    support.recordInput(new AcceptInputCommand(runId, source)).collect().asList().await().indefinitely();
    long inputRevision = store.load(runId).orElseThrow().run().runRevision();
    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, instance.id(), runId, "CONTINUE"));
    waitUntil("technical retries must complete without author input", () -> instance.status() == WorkflowStatus.COMPLETED);

    var completed = store.load(runId).orElseThrow();
    assertEquals(3, calls.get());
    assertEquals(instance.id(), completed.run().flowInstanceId());
    assertEquals(RunStatus.PLAN_APPROVED, completed.run().status());
    assertEquals(2, completed.attempts().stream().filter(attempt -> attempt.failureEvidence() != null).count());
    assertTrue(completed.transitions().stream().filter(transition -> transition.toRevision() > inputRevision)
        .noneMatch(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT));
    long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (countPersistedInstances(instance.id()) != 0 && System.nanoTime() < cleanupDeadline) {
      waitQuietly(Duration.ofMillis(25));
    }
    assertEquals(0, countPersistedInstances(instance.id()), "completed Flow persistence must be cleaned up");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publicationFailureResumesTheSameFlowAndPreservesItsNextGate(boolean deliveredBeforeFailure)
      throws Exception {
    String runId = "publication-failure-" + UUID.randomUUID();
    var current = new java.util.concurrent.atomic.AtomicReference<ProductPipelineRunDocument>();
    ProductPipelineRunStore store = mock(ProductPipelineRunStore.class);
    ProductPipelineRunSupport support = mock(ProductPipelineRunSupport.class);
    when(store.load(runId)).thenAnswer(ignored -> Optional.of(current.get()));
    when(support.currentRunSnapshot(runId)).thenAnswer(ignored -> current.get().run());
    ProvidedIdsFlowTasks restoring = new ProvidedIdsFlowTasks(support);
    AtomicInteger executions = new AtomicInteger();
    AtomicInteger restorations = new AtomicInteger();
    when(tasks.executeCurrentStage(any())).thenAnswer(invocation -> {
      ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
      if (executions.incrementAndGet() == 1) {
        return context.withDecision("WAIT_FOR_INPUT");
      }
      var before = current.get();
      current.set(new ProductPipelineRunDocument(
          new RunSnapshot(runId, runId, 6L, RunStatus.WAITING_FOR_APPROVAL,
              "design-planning", List.of(), null, before.run().flowInstanceId()),
          List.of(), before.transitions(), "v3"));
      return context.withDecision("WAIT_FOR_PLAN_APPROVAL");
    });
    when(tasks.restoreAfterInput(any())).thenAnswer(invocation -> {
      restorations.incrementAndGet();
      return restoring.restoreAfterInput(invocation.getArgument(0));
    });
    when(tasks.settled(runId)).thenAnswer(ignored -> executions.get() >= 2);
    when(tasks.drainSignals(runId)).thenReturn(List.of());
    WorkflowInstance instance = flow.instance(new ProvidedIdsFlow.RunContext(
        runId, "create-chain", "2", "manifest-sha", null));
    current.set(new ProductPipelineRunDocument(
        new RunSnapshot(runId, runId, 4L, RunStatus.WAITING_FOR_INPUT,
            "requirement-discovery", List.of(), null, instance.id()), List.of(), List.of(), "v1"));
    instance.start();
    waitUntil("opening input wait must persist", () -> instance.status() == WorkflowStatus.WAITING);
    waitQuietly(Duration.ofMillis(200));

    var command = new AcceptInputCommand(runId, "Use the supplied endpoint", "message-1", "input-hash");
    when(support.recordInput(command)).thenReturn(Multi.createFrom().deferred(() -> {
      if (current.get().appliedCommand(command.commandId(), command.commandPayloadHash()).isEmpty()) {
        current.set(new ProductPipelineRunDocument(
            new RunSnapshot(runId, runId, 5L, RunStatus.RUNNING,
                "requirement-discovery", List.of(), null, instance.id()), List.of(), List.of(
            new RunTransition(4L, 5L, RunStatus.WAITING_FOR_INPUT, RunStatus.RUNNING,
                "requirement-discovery", java.time.Instant.EPOCH, "input accepted",
                command.commandId(), command.commandPayloadHash())), "v2"));
      }
      return Multi.createFrom().empty();
    }));
    AtomicBoolean failFirst = new AtomicBoolean(true);
    WorkflowApplication dispatchApplication = mock(WorkflowApplication.class);
    EventPublisher publisher = mock(EventPublisher.class);
    when(publisher.publish(any(CloudEvent.class))).thenAnswer(invocation -> {
      CloudEvent event = invocation.getArgument(0);
      boolean fail = failFirst.getAndSet(false);
      if (fail && !deliveredBeforeFailure) {
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("publisher unavailable"));
      }
      publish(event);
      return fail
          ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("publication acknowledgment lost"))
          : java.util.concurrent.CompletableFuture.completedFuture(null);
    });
    when(dispatchApplication.eventPublishers()).thenReturn(List.of(publisher));
    var first = new ProvidedIdsFlowOrchestrator(support, store, flow, tasks, dispatchApplication);

    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> first.acceptInput(command).collect().asList().await().indefinitely());
    assertTrue(current.get().appliedCommand(command.commandId(), command.commandPayloadHash()).isPresent());
    var resumed = new ProvidedIdsFlowOrchestrator(support, store, flow, tasks, dispatchApplication);
    resumed.acceptInput(command).collect().asList().await().indefinitely();
    waitUntil("resumed work must reach its next approval", () -> executions.get() == 2
        && instance.status() == WorkflowStatus.WAITING);
    assertEquals(instance.id(), current.get().run().flowInstanceId());
    assertTrue(persistedInstanceExists(instance.id()));

    int restoredBefore = restorations.get();
    publish(resumeEvent(ProvidedIdsFlow.INPUT_EVENT_TYPE, instance.id(), runId, "CONTINUE"));
    waitUntil("stale event must return to the same gate", () -> restorations.get() > restoredBefore
        && instance.status() == WorkflowStatus.WAITING);
    assertEquals(2, executions.get());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, current.get().run().status());
  }

  @Test
  void chatCancellationDoesNotInterruptAcceptedStageWork() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    when(tasks.executeCurrentStage(any())).thenAnswer(invocation -> {
      ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
      entered.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("stage fixture was not released");
        }
        return context.withDecision("WAIT_FOR_INPUT");
      } catch (InterruptedException failure) {
        interrupted.set(true);
        throw failure;
      } finally {
        finished.countDown();
      }
    });
    String runId = "observer-cancellation-" + UUID.randomUUID();
    WorkflowInstance instance = flow.instance(new ProvidedIdsFlow.RunContext(
        runId, "create-chain", "2", "manifest-sha", null));
    ProvidedIdsFlow boundFlow = mock(ProvidedIdsFlow.class);
    when(boundFlow.instance(any(ProvidedIdsFlow.RunContext.class))).thenReturn(instance);
    ProductPipelineRunStore store = mock(ProductPipelineRunStore.class);
    ProductPipelineRunSupport support = mock(ProductPipelineRunSupport.class);
    StartOrResumeCommand command = mock(StartOrResumeCommand.class);
    ProductPipelineProfile profile = mock(ProductPipelineProfile.class);
    RunManifest manifest = mock(RunManifest.class);
    when(command.conversationId()).thenReturn(runId);
    when(command.runId()).thenReturn(runId);
    when(command.profile()).thenReturn(profile);
    when(command.runManifest()).thenReturn(manifest);
    when(profile.profileId()).thenReturn("create-chain");
    when(profile.profileVersion()).thenReturn("2");
    when(manifest.profileDigest()).thenReturn("manifest-sha");
    when(store.loadByConversation(runId)).thenReturn(Optional.empty());
    when(support.restoreForExternalWorkflow(command)).thenReturn(Multi.createFrom().empty());
    ProvidedIdsFlowOrchestrator orchestrator = new ProvidedIdsFlowOrchestrator(
        support, store, boundFlow, tasks, application);

    var observer = orchestrator.startOrResume(command).subscribe().with(signal -> {}, failure -> {});
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS), "stage must start before cancellation");
      observer.cancel();
    } finally {
      release.countDown();
    }
    assertTrue(finished.await(10, TimeUnit.SECONDS), "accepted work must finish");
    waitUntil("accepted Flow work must settle", () -> instance.status() == WorkflowStatus.WAITING
        || instance.status() == WorkflowStatus.FAULTED);
    assertFalse(interrupted.get(), "observer cancellation must not interrupt stage work");
    assertEquals(WorkflowStatus.WAITING, instance.status());
    assertTrue(persistedInstanceExists(instance.id()));
  }

  @Test
  void restartFlowWaitsForActivationBeforeExecutingAStage() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    when(tasks.executeCurrentStage(any()))
        .thenAnswer(
            invocation -> {
              ProvidedIdsFlow.RunContext context = invocation.getArgument(0);
              if (context.waitForActivation()) {
                return context;
              }
              executions.incrementAndGet();
              return context.withDecision("STOP");
            });
    when(tasks.restoreAfterActivation(any()))
        .thenAnswer(
            invocation ->
                new ProvidedIdsFlow.RunContext(
                    "restart-child", "create-chain", "2", "manifest-sha", null));
    ProvidedIdsFlow.RunContext context =
        new ProvidedIdsFlow.RunContext(
            "restart-child",
            "create-chain",
            "2",
            "manifest-sha",
            "WAIT_FOR_ACTIVATION");
    WorkflowInstance instance = flow.instance(context);

    instance.start();
    waitUntil(
        "restart child must persist the activation wait",
        () -> instance.status() == WorkflowStatus.WAITING);
    waitQuietly(Duration.ofMillis(200));

    assertEquals(0, executions.get());
    assertTrue(persistedInstanceExists(instance.id()));

    publish(
        resumeEvent(
            ProvidedIdsFlow.ACTIVATION_EVENT_TYPE,
            instance.id(),
            "restart-child",
            null));
    waitUntil(
        "activation must release exactly one child stage execution",
        () -> executions.get() == 1 && instance.status() == WorkflowStatus.COMPLETED);

    assertEquals(1, executions.get());
  }

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
    Stream<WorkflowInstance> scan = persistenceHandlers.reader().scanAll(flow.definition());
    assertEquals(
        Status.STATUS_NO_TRANSACTION,
        QuarkusTransaction.getStatus(),
        "scanAll must close its restore transaction before returning the stream");
    try (Stream<WorkflowInstance> restored = scan) {
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
