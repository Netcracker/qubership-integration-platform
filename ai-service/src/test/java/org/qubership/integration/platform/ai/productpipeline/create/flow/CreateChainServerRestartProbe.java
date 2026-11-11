package org.qubership.integration.platform.ai.productpipeline.create.flow;

import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceHandlers;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.A2aTaskSnapshotPersister;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.FileCheckpointBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

/** Child-only Quarkus fixture. Production Flow, executor, journal, facade, and JDBC adapters stay active. */
@QuarkusTest
@TestProfile(CreateChainServerRestartProbe.Profile.class)
public class CreateChainServerRestartProbe {
  static final List<String> POINTS = List.of("RETRY_WAIT", "STAGE_COMMIT", "COMMAND_COMMIT", "TERMINAL_COMMIT");
  static final String SOURCE = "Keep endpoint /orders and literal customerRef unchanged.";
  static final ArtifactTypeRef INPUT = new ArtifactTypeRef("user-input", 1);
  static final ArtifactTypeRef OUTPUT = new ArtifactTypeRef("ids-bypass", 1);
  static final ArtifactTypeRef CATALOG = new ArtifactTypeRef("catalog-chain-snapshot", 1);

  @Inject ProductPipelineRunStore runs;
  @Inject ProductPipelineArtifactStore artifacts;
  @Inject CreateChainOrchestrator orchestrator;
  @Inject ProductPipelineProfileCatalog profiles;
  @Inject PersistenceInstanceHandlers persistence;
  @Inject io.serverlessworkflow.impl.persistence.PersistenceInstanceOperations persisted;
  @Inject ProvidedIdsFlow flow;
  @Inject CreateChainApplicationFacade facade;
  @Inject A2aTaskSnapshotPersister snapshots;

  static Path directory() { return Path.of(System.getProperty("recovery.probe.directory")); }
  static boolean prepare() { return "prepare".equals(System.getProperty("recovery.probe.mode")); }

  public static void main(String[] args) {
    var listener = new SummaryGeneratingListener();
    try (var session = LauncherFactory.openSession()) {
      var launcher = session.getLauncher();
      launcher.registerTestExecutionListeners(listener);
      launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
          org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(CreateChainServerRestartProbe.class.getName())).build());
    }
    listener.getSummary().printTo(new java.io.PrintWriter(System.out, true));
    listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out, true));
    if (listener.getSummary().getTestsSucceededCount() != 1) {
      System.exit(1);
    }
  }

  @Test
  void stopOrVerifyTheServer() throws Exception {
    if (prepare()) {
      for (String point : POINTS) {
        var manifest = new RunManifest(point, null, List.of(), "product", "create-chain", "2",
            "profile", "baseline", "baseline", List.of(), "closure",
            new KnowledgePackageRef("fixture", "1", "1", "checksum", "CERTIFIED", "certificate"),
            "2026.1", List.of(INPUT, OUTPUT, CATALOG), null);
        orchestrator.startOrResume(new StartOrResumeCommand(point, point, profiles.require("create-chain", "2"), manifest))
            .collect().asList().await().indefinitely();
        Files.writeString(directory().resolve(point + ".flow"), runs.load(point).orElseThrow().run().flowInstanceId());
        snapshots.persistAndBuildSdkTask(point, point, new CallerContext("local", "local-user"),
            CreateChainA2aStateMapper.project(new CreateChainExecutionSnapshot(point, point,
                CreateChainExecutionStatus.WORKING, 1L, null, ""), List.of()), List.of());
        orchestrator.acceptInput(new AcceptInputCommand(point, SOURCE, "message-" + point, "hash-" + point))
            .subscribe().with(ignored -> {}, failure -> failure.printStackTrace());
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        var run = runs.load("RETRY_WAIT").orElseThrow();
        var instance = persistence.reader().find(flow.definition(), run.run().flowInstanceId());
        var saved = persisted.readWorkflowInfo(flow.definition(), run.run().flowInstanceId());
        if (!run.attempts().isEmpty() && run.attempts().getLast().outcome() == StageStatus.FAILED
            && instance.map(value -> value.status() == WorkflowStatus.WAITING).orElse(false)
            && saved.map(value -> value.tasks().values().stream().anyMatch(task ->
                    task instanceof io.serverlessworkflow.impl.persistence.CompletedTaskInfo completed
                        && completed.model().as(ProvidedIdsFlow.RunContext.class)
                            .map(ProvidedIdsFlow.RunContext::waitForRetry).orElse(false)
                        && completed.nextPosition() != null)).orElse(false)) {
          Files.writeString(directory().resolve("RETRY_WAIT.ready"), "ready");
          break;
        }
        Thread.sleep(25);
      }
      assertTrue(Files.exists(directory().resolve("RETRY_WAIT.ready")), "retry timer must persist before process termination");
      for (String point : POINTS) {
        while (!Files.exists(directory().resolve(point + ".ready")) && System.nanoTime() < deadline) {
          Thread.sleep(25);
        }
        assertTrue(Files.exists(directory().resolve(point + ".ready")), point + " commit must be durable");
      }
      Files.writeString(directory().resolve("ready"), "all windows committed");
      new CountDownLatch(1).await();
      return;
    }
    // Startup restores the Flow; a durable read repairs its public projection without another command.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < deadline && POINTS.stream().anyMatch(point ->
        runs.load(point).orElseThrow().run().status() != RunStatus.CHAIN_MATERIALIZED
            || snapshots.loadDurable(point).orElseThrow().task().status().state()
                != org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED)) {
      Thread.sleep(50);
    }
    assertAll(POINTS.stream().map(point -> (Executable) () -> {
      var run = runs.load(point).orElseThrow();
      assertEquals(RunStatus.CHAIN_MATERIALIZED, run.run().status(), point + " must resume automatically");
      assertEquals(Files.readString(directory().resolve(point + ".flow")), run.run().flowInstanceId());
      assertEquals(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED,
          snapshots.loadDurable(point).orElseThrow().task().status().state(), point + " public state must catch up");
      assertEquals(1, artifacts.history(point, Kind.IDS_BYPASS).size());
      var sources = artifacts.history(point, Kind.USER_INPUT);
      assertEquals(1, sources.size());
      assertEquals(SOURCE, artifacts.payload(sources.getFirst(), UserInput.class).text());
      assertEquals("RETRY_WAIT".equals(point) ? 1L : 0L,
          run.attempts().stream().filter(attempt -> attempt.outcome() == StageStatus.FAILED).count());
      assertEquals(1L, run.transitions().stream()
          .filter(transition -> ("message-" + point).equals(transition.commandId())).count());
      assertEquals(List.of("RETRY_WAIT", "COMMAND_COMMIT").contains(point) ? 1L : 0L,
          Files.readAllLines(directory().resolve(point + ".first-calls")).stream()
          .filter("resume"::equals).count(), point + " first stage replay count");
    }));
    Files.writeString(directory().resolve("result"), "COMPLETED");
  }

  public static class Profile implements QuarkusTestProfile {
    @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(FixtureBeans.class); }
    @Override public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url", System.getProperty("recovery.probe.jdbc"),
          "quarkus.datasource.username", "recovery", "quarkus.datasource.password", "recovery",
          "quarkus.datasource.devservices.enabled", "false", "quarkus.http.test-port", "0");
    }
  }

  @ApplicationScoped
  @Alternative
  public static class FixtureBeans {
    @Produces @ApplicationScoped
    ArtifactBlobStore blobs(ObjectMapper mapper) {
      var delegate = FileCheckpointBlobStore.open(directory().resolve("blobs"));
      return new ArtifactBlobStore() {
        @Override public void put(String key, byte[] content) { delegate.put(key, content); }
        @Override public Optional<byte[]> get(String key) { return delegate.get(key); }
        @Override public List<String> list(String prefix) { return delegate.list(prefix); }
        @Override public Optional<VersionedBlob> getVersioned(String key) { return delegate.getVersioned(key); }
        @Override public void putIfVersion(String key, byte[] content, String version) {
          delegate.putIfVersion(key, content, version);
          if (!prepare() || !key.startsWith("product-pipeline-runs/")) { return; }
          try {
            var run = mapper.readValue(content, ProductPipelineRunDocument.class).run();
            boolean stop = switch (run.runId()) {
              case "COMMAND_COMMIT" -> run.status() == RunStatus.RUNNING && run.runRevision() > 2;
              case "STAGE_COMMIT" -> run.stages().getFirst().status() == StageStatus.SUCCEEDED;
              case "TERMINAL_COMMIT" -> run.status() == RunStatus.CHAIN_MATERIALIZED;
              default -> false;
            };
            if (stop) {
              Files.writeString(directory().resolve(run.runId() + ".ready"), "committed");
              new CountDownLatch(1).await();
            }
          } catch (Exception failure) { throw new IllegalStateException("Restart fixture failed", failure); }
        }
      };
    }

    @Produces @ApplicationScoped
    ProductPipelineProfileCatalog profiles() {
      return new ProductPipelineProfileCatalog(List.of(new ProductPipelineProfile(1, "create-chain", "2",
          List.of(INPUT), List.of(
          new ProfileStage("first", "first-fixture", List.of(INPUT), List.of(OUTPUT), null, null, new RetryPolicy(2, 15_000L)),
          new ProfileStage("second", "second-fixture", List.of(OUTPUT), List.of(CATALOG), null, null, new RetryPolicy(2, 1L))),
          new TerminalPolicy("second", "CHAIN_MATERIALIZED"), List.of("first-fixture", "second-fixture"))));
    }

    @Produces @ApplicationScoped
    StageCapabilityRegistry capabilities() {
      StageCapability first = capability("first-fixture", context -> {
        String text = context.attributeAsString("userText");
        if (text == null) {
          return Multi.createFrom().item(new CapabilitySignal.Completed(StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "")));
        }
        assertEquals(SOURCE, text);
        try {
          Files.writeString(directory().resolve(context.runId() + ".first-calls"), prepare() ? "prepare\n" : "resume\n",
              java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        if (prepare() && "RETRY_WAIT".equals(context.runId())) {
          return Multi.createFrom().failure(new java.net.ConnectException("Connection refused"));
        }
        return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.SUCCEEDED,
            List.of(new ArtifactCandidate(Kind.IDS_BYPASS, Map.of("type", "ids-bypass", "schemaVersion", 1), List.of())), "", null)));
      });
      return new StageCapabilityRegistry(List.of(first, capability("second-fixture", context ->
          Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(StageOutcomeClass.SUCCEEDED,
              List.of(new ArtifactCandidate(Kind.CATALOG_CHAIN_SNAPSHOT,
                  new ChainCatalogFacts("fixture-" + context.runId(), "Fixture result", "", 0, 0, "",
                      List.of(), List.of(), "DRAFT"), List.of())), "", null))))));
    }

    @Produces @ApplicationScoped
    ProductPipelineRunSupport support(ProductPipelineRunStore runs, ProductPipelineArtifactStore artifacts,
        StageCapabilityRegistry capabilities, ProductPipelineProfileCatalog profiles) {
      return CreateChainTestOrchestrator.supportBuilder(runs, artifacts, capabilities, Clock.systemUTC())
          .profileCatalog(profiles).build();
    }
  }

  private static StageCapability capability(String id,
      java.util.function.Function<StageExecutionContext, Multi<CapabilitySignal>> execute) {
    return new StageCapability() {
      @Override public String capabilityId() { return id; }
      @Override public Multi<CapabilitySignal> execute(StageExecutionContext context) { return execute.apply(context); }
    };
  }
}
