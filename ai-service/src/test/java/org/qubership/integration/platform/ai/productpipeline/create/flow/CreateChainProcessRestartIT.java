package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
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
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/** OS process recovery at run commits, using real stage/Flow tasks and the file-backed test store. */
public class CreateChainProcessRestartIT {

  private static final String RUN_ID = "process-restart";
  private static final String SOURCE = "Keep endpoint /orders and literal customerRef unchanged.";

  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"RETRY_COMMIT", "STAGE_COMMIT", "TERMINAL_COMMIT"})
  void killedProcessResumesCommittedWorkWithoutReplayingSuccessfulStages(String point) throws Exception {
    Path log = directory.resolve("first.log");
    Process first = child("prepare", point, log);
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!Files.exists(directory.resolve("ready")) && first.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertTrue(Files.exists(directory.resolve("ready")), () -> readLog(log));
      first.destroyForcibly();
      assertTrue(first.waitFor(10, TimeUnit.SECONDS), "prepared JVM must terminate");
    } finally {
      first.destroyForcibly();
      first.waitFor(10, TimeUnit.SECONDS);
    }
    Path resumedLog = directory.resolve("resumed.log");
    Process resumed = child("resume", point, resumedLog);
    try {
      assertTrue(resumed.waitFor(30, TimeUnit.SECONDS), "resumed JVM must finish");
      assertEquals(0, resumed.exitValue(), () -> readLog(resumedLog));
      assertEquals("COMPLETED", Files.readString(directory.resolve("result")));
    } finally {
      resumed.destroyForcibly();
      resumed.waitFor(10, TimeUnit.SECONDS);
    }
  }

  private Process child(String mode, String point, Path log) throws Exception {
    return new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        getClass().getName(), mode, point, directory.toString())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
  }

  private static String readLog(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception failure) {
      return failure.toString();
    }
  }

  public static void main(String[] args) throws Exception {
    boolean prepare = "prepare".equals(args[0]);
    String point = args[1];
    Path directory = Path.of(args[2]);
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = FileCheckpointBlobStore.open(directory.resolve("blobs"));
    var runStore = new ProductPipelineRunStore(blobs, mapper, Clock.systemUTC());
    var artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, Clock.systemUTC()));
    var input = new ArtifactTypeRef("user-input", 1);
    var output = new ArtifactTypeRef("ids-bypass", 1);
    var profile = new ProductPipelineProfile(1, "process-recovery", "1", List.of(input), List.of(
        new ProfileStage("first", "first-cap", List.of(input), List.of(output), null, null, new RetryPolicy(1, 1L)),
        new ProfileStage("second", "second-cap", List.of(output), List.of(), null, null, new RetryPolicy(1, 1L))),
        new TerminalPolicy("second", "PLAN_APPROVED"), List.of("first-cap", "second-cap"));
    AtomicInteger firstCalls = new AtomicInteger();
    StageCapability first = capability("first-cap", context -> {
      if (context.attributeAsString("userText") == null) {
        return Multi.createFrom().item(new CapabilitySignal.Completed(
            StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "Provide the source requirements.")));
      }
      if (!prepare && !"RETRY_COMMIT".equals(point)) {
        throw new AssertionError("A committed stage must not execute again.");
      }
      assertEquals(SOURCE, context.attributeAsString("userText"));
      if (prepare && firstCalls.incrementAndGet() == 1) {
        return Multi.createFrom().failure(new java.net.ConnectException("Connection refused"));
      }
      return Multi.createFrom().item(new CapabilitySignal.Completed(new StageOutcome(
          StageOutcomeClass.SUCCEEDED, List.of(new ArtifactCandidate(Kind.IDS_BYPASS,
              Map.of("type", "ids-bypass", "schemaVersion", 1), List.of())), "First stage committed.", null)));
    });
    StageCapability second = capability("second-cap", context -> {
      if (!prepare && "TERMINAL_COMMIT".equals(point)) {
        throw new AssertionError("A completed run must not execute again.");
      }
      assertTrue(context.inputRefs().stream().anyMatch(reference -> reference.kind() == Kind.IDS_BYPASS));
      return Multi.createFrom().item(new CapabilitySignal.Completed(
          StageOutcome.of(StageOutcomeClass.SUCCEEDED, "Second stage committed.")));
    });
    var runtime = ProductPipelineRunSupport.builder(runStore, artifacts,
        new StageCapabilityRegistry(List.of(first, second)), Clock.systemUTC()).build();
    var manifest = new RunManifest(RUN_ID, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile", "baseline", "baseline", List.of(), "closure",
        new KnowledgePackageRef("fixture", "1", "1", "checksum", "CERTIFIED", "certificate"),
        "2026.1", List.of(input, output), null);
    var start = new StartOrResumeCommand("process-conversation", RUN_ID, profile, manifest);
    if (prepare) {
      runtime.bootstrap(start, "process-flow");
      assertInstanceOf(StageDecision.WaitForInput.class,
          runtime.stageExecutor().execute(RUN_ID, "first").await().indefinitely().decision());
      runtime.recordInput(new AcceptInputCommand(RUN_ID, SOURCE)).collect().asList().await().indefinitely();
      assertInstanceOf(StageDecision.Retry.class,
          runtime.stageExecutor().execute(RUN_ID, "first").await().indefinitely().decision());
      if (!"RETRY_COMMIT".equals(point)) {
        var committed = runtime.stageExecutor().execute(RUN_ID, "first").await().indefinitely();
        assertInstanceOf(StageDecision.Continue.class, committed.decision());
        if ("TERMINAL_COMMIT".equals(point)) {
          runtime.applyStageLifecycle(RUN_ID, committed).collect().asList().await().indefinitely();
          assertInstanceOf(StageDecision.Complete.class,
              runtime.stageExecutor().execute(RUN_ID, "second").await().indefinitely().decision());
        }
      }
      Files.writeString(directory.resolve("committed.json"), mapper.writeValueAsString(runStore.load(RUN_ID).orElseThrow()));
      Files.writeString(directory.resolve("ready"), point);
      new CountDownLatch(1).await();
      return;
    }
    ProductPipelineRunDocument before = mapper.readValue(
        Files.readString(directory.resolve("committed.json")), ProductPipelineRunDocument.class);
    assertEquals(before.run(), runStore.load(RUN_ID).orElseThrow().run());
    runtime.restoreForExternalWorkflow(start).collect().asList().await().indefinitely();
    assertEquals("RETRY_COMMIT".equals(point) ? 1 : 0,
        runtime.restoreTechnicalRetryCount(RUN_ID, runtime.currentStageId(RUN_ID)));
    var tasks = new ProvidedIdsFlowTasks(runtime);
    var stale = new ProvidedIdsFlow.RunContext(RUN_ID, profile.profileId(), profile.profileVersion(), "manifest",
        "CONTINUE", "RETRY_COMMIT".equals(point) ? 0 : 1, "PT0.001S");
    var result = tasks.executeCurrentStage(stale);
    if ("CONTINUE".equals(result.decision())) {
      result = tasks.executeCurrentStage(result);
    }
    assertEquals("STOP", result.decision());
    var after = runStore.load(RUN_ID).orElseThrow();
    assertEquals(RunStatus.PLAN_APPROVED, after.run().status());
    assertEquals(before.run().flowInstanceId(), after.run().flowInstanceId());
    if ("TERMINAL_COMMIT".equals(point)) {
      assertEquals(before.run(), after.run());
      assertEquals(before.attempts(), after.attempts());
      assertEquals(before.transitions(), after.transitions());
    }
    if (!"RETRY_COMMIT".equals(point)) {
      assertEquals(before.run().stages().getFirst().outputRefs(), after.run().stages().getFirst().outputRefs());
    }
    assertEquals(1, artifacts.history(RUN_ID, Kind.IDS_BYPASS).size());
    var sources = artifacts.history(RUN_ID, Kind.USER_INPUT);
    assertEquals(1, sources.size());
    assertEquals(SOURCE, artifacts.payload(sources.getFirst(), UserInput.class).text());
    Files.writeString(directory.resolve("result"), "COMPLETED");
  }

  private static StageCapability capability(String id,
      java.util.function.Function<StageExecutionContext, Multi<CapabilitySignal>> execute) {
    return new StageCapability() {
      @Override public String capabilityId() { return id; }
      @Override public Multi<CapabilitySignal> execute(StageExecutionContext context) { return execute.apply(context); }
    };
  }
}
