package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
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
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.transport.A2aCommandId;
import org.qubership.integration.platform.ai.a2a.transport.A2aDispatchCrashGate;
import org.qubership.integration.platform.ai.a2a.transport.A2aSseTestSupport;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.FileCheckpointBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.profile.CompilerPipelinePolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/** Child-only HTTP receipt probe with real Flow, facade, transport, and JDBC persistence. */
@QuarkusTest
@TestProfile(CreateChainReceiptRestartProbe.Profile.class)
public class CreateChainReceiptRestartProbe {
  private static final String MESSAGE_ID = "restart-message";
  private static volatile boolean armed;
  @Inject A2aMessageReceiptRepository receipts;
  @Inject A2aTaskRepository tasks;
  @Inject ProductPipelineRunStore runs;
  @Inject ProductPipelineArtifactStore artifacts;
  @Inject CreateChainApplicationFacade facade;

  static Path directory() { return CreateChainServerRestartProbe.directory(); }
  static boolean prepare() { return CreateChainServerRestartProbe.prepare(); }
  static String window() {
    String selected = System.getProperty("recovery.probe.window");
    return selected.substring(selected.indexOf(':') + 1);
  }
  static String command() {
    String selected = System.getProperty("recovery.probe.window");
    return selected.contains(":") ? selected.substring(0, selected.indexOf(':')) : "INITIAL";
  }
  static boolean approval() { return command().endsWith("APPROVE"); }

  public static void main(String[] args) {
    var listener = new SummaryGeneratingListener();
    try (var session = LauncherFactory.openSession()) {
      var launcher = session.getLauncher();
      launcher.registerTestExecutionListeners(listener);
      launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
          org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(CreateChainReceiptRestartProbe.class.getName())).build());
    }
    listener.getSummary().printTo(new java.io.PrintWriter(System.out, true));
    listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out, true));
    if (listener.getSummary().getTestsSucceededCount() != 1) { System.exit(1); }
  }

  @Test
  void stopOrReplayTheSameHttpMessage() throws Exception {
    if (prepare()) {
      String taskId = null;
      if (!"INITIAL".equals(command())) {
        var setup = post(A2aSseTestSupport.textMessageBody("setup-message", null,
            approval() ? CreateChainServerRestartProbe.SOURCE : ""));
        assertEquals(200, setup.statusCode(), setup.asString());
        assertEquals("TASK_STATE_INPUT_REQUIRED", setup.jsonPath().getString("task.status.state"));
        taskId = setup.jsonPath().getString("task.id");
      }
      String body;
      if (approval()) {
        var pending = (CreateChainPendingAction.Approve) facade.snapshot(taskId).orElseThrow().pendingAction();
        body = "TEXT_APPROVE".equals(command())
            ? A2aSseTestSupport.textMessageBody(MESSAGE_ID, taskId,
                "approve " + CreateChainA2aStateMapper.approvalToken(pending.artifactHash()))
            : new ObjectMapper().writeValueAsString(Map.of("message", Map.of("messageId", MESSAGE_ID,
                "taskId", taskId, "role", "ROLE_USER", "metadata", Map.of("skillId", "create-chain@2"),
                "parts", List.of(Map.of("data", Map.of("action", "approve", "artifactType", pending.artifactType(),
                    "artifactHash", pending.artifactHash(), "revision", pending.revision()))))));
      } else {
        body = A2aSseTestSupport.textMessageBody(MESSAGE_ID, taskId, CreateChainServerRestartProbe.SOURCE);
      }
      Files.writeString(directory().resolve("request.json"), body);
      armed = true;
      new Thread(() -> {
        try {
          var response = post(body);
          Files.writeString(directory().resolve("http-response"), response.statusCode() + "\n" + response.asString());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
      }).start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!Files.exists(directory().resolve("checkpoint.ready")) && System.nanoTime() < deadline) { Thread.sleep(25); }
      assertTrue(Files.exists(directory().resolve("checkpoint.ready")), "the selected receipt window must commit");
      var receipt = receipts.findCallerReceipt("local", "local-user", MESSAGE_ID).orElseThrow();
      Files.writeString(directory().resolve("task"), receipt.taskId());
      runs.loadByConversation(receipt.taskId()).ifPresent(doc -> {
        try { Files.writeString(directory().resolve("flow"), doc.run().flowInstanceId()); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
      });
      Files.writeString(directory().resolve("window"), window());
      Files.writeString(directory().resolve("ready"), "checkpoint and identity saved");
      new CountDownLatch(1).await();
      return;
    }
    String body = Files.readString(directory().resolve("request.json"));
    String taskId = Files.readString(directory().resolve("task"));
    var changed = new ObjectMapper().readTree(body);
    var part = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("message").path("parts").get(0);
    if (part.has("text")) { part.put("text", part.path("text").asText() + " changed"); }
    else { ((com.fasterxml.jackson.databind.node.ObjectNode) part.path("data")).put("artifactHash", "changed"); }
    assertEquals(422, post(changed.toString()).statusCode(),
        "a changed command cannot reuse the receipt");
    var response = post(body);
    assertEquals(200, response.statusCode(), response.asString());
    assertEquals(taskId, response.jsonPath().getString("task.id"));
    assertEquals("TASK_STATE_COMPLETED", response.jsonPath().getString("task.status.state"));
    var receipt = receipts.findCallerReceipt("local", "local-user", MESSAGE_ID).orElseThrow();
    assertTrue(receipt.completed(), "the same message receipt must be finalized");
    assertEquals(tasks.findByTaskId(taskId).orElseThrow().revision(), receipt.responseTaskRevision());
    var run = runs.loadByConversation(taskId).orElseThrow();
    assertEquals(RunStatus.CHAIN_MATERIALIZED, run.run().status());
    if (Files.exists(directory().resolve("flow"))) {
      assertEquals(Files.readString(directory().resolve("flow")), run.run().flowInstanceId());
    }
    String commandId = A2aCommandId.derive("local", "local-user", MESSAGE_ID) + (approval() ? ":approve" : ":accept-input");
    assertEquals(1L, run.transitions().stream().filter(t -> commandId.equals(t.commandId())).count());
    var sources = artifacts.history(run.run().runId(), Kind.USER_INPUT);
    assertEquals(1, sources.size());
    assertEquals(CreateChainServerRestartProbe.SOURCE, artifacts.payload(sources.getFirst(), UserInput.class).text());
    assertEquals(1, artifacts.history(run.run().runId(), Kind.IDS_BYPASS).size());
    assertEquals(1, artifacts.history(run.run().runId(), Kind.CATALOG_CHAIN_SNAPSHOT).size());
    assertEquals(1, Files.readAllLines(directory().resolve(run.run().runId() + ".first-calls")).size());
    long revision = tasks.findByTaskId(taskId).orElseThrow().revision();
    assertEquals(200, post(body).statusCode());
    assertEquals(revision, tasks.findByTaskId(taskId).orElseThrow().revision(), "a completed replay must not write again");
    assertEquals(400, post(A2aSseTestSupport.textMessageBody("new-message", taskId, "Change endpoint")).statusCode(),
        "a new command cannot continue a terminal task");
    Files.writeString(directory().resolve("result"), "COMPLETED");
  }

  private static io.restassured.response.Response post(String body) {
    return given().urlEncodingEnabled(false).header("A2A-Version", "1.0").contentType(ContentType.JSON).body(body)
        .post(java.net.URI.create("/message:send"));
  }

  public static class Profile extends CreateChainServerRestartProbe.Profile {
    @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(FixtureBeans.class, RestartGate.class); }
    @Override public Map<String, String> getConfigOverrides() {
      var config = new java.util.HashMap<>(super.getConfigOverrides());
      config.put("qip.ai.a2a.dispatch-lease", "PT2S");
      config.put("qip.ai.a2a.dispatch-heartbeat-interval", "PT0.25S");
      return config;
    }
  }

  @ApplicationScoped @Alternative
  public static class RestartGate extends A2aDispatchCrashGate {
    @Override public void check(Point point) {
      if (prepare() && armed && point.name().equals(window())) { stop(); }
    }
  }

  private static void stop() {
    try {
      Files.writeString(directory().resolve("checkpoint.ready"), "committed");
      new CountDownLatch(1).await();
    } catch (Exception failure) { throw new IllegalStateException("Receipt restart fixture failed", failure); }
  }

  @ApplicationScoped @Alternative
  public static class FixtureBeans extends CreateChainServerRestartProbe.FixtureBeans {
    @Override @Produces @ApplicationScoped
    StageCapabilityRegistry capabilities() {
      var base = super.capabilities();
      if (!approval()) { return base; }
      var first = base.require("first-fixture");
      var candidate = new StageCapability() {
        @Override public String capabilityId() { return first.capabilityId(); }
        @Override public io.smallrye.mutiny.Multi<CapabilitySignal> execute(
            StageExecutionContext context) {
          return first.execute(context).map(signal -> {
            if (signal instanceof CapabilitySignal.Completed completed
                && completed.outcome().outcomeClass() == StageOutcomeClass.SUCCEEDED) {
              var outcome = completed.outcome();
              return new CapabilitySignal.Completed(
                  new StageOutcome(
                      StageOutcomeClass.CANDIDATE,
                      outcome.candidates(), outcome.message(), outcome.retryDelayMs()));
            }
            return signal;
          });
        }
      };
      return new StageCapabilityRegistry(
          List.of(candidate, base.require("second-fixture")));
    }

    @Override @Produces @ApplicationScoped
    org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport support(
        ProductPipelineRunStore runs, ProductPipelineArtifactStore artifacts,
        StageCapabilityRegistry capabilities,
        ProductPipelineProfileCatalog profiles) {
      return org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
          .builder(runs, artifacts, capabilities, Clock.systemUTC()).profileCatalog(profiles)
          .compilerRunPinResolver(pinResolver()).build();
    }

    @Override @Produces @ApplicationScoped
    ArtifactBlobStore blobs(ObjectMapper mapper) {
      var delegate = FileCheckpointBlobStore.open(directory().resolve("blobs"));
      return new ArtifactBlobStore() {
        @Override public void put(String key, byte[] content) { delegate.put(key, content); }
        @Override public Optional<byte[]> get(String key) { return delegate.get(key); }
        @Override public List<String> list(String prefix) { return delegate.list(prefix); }
        @Override public Optional<VersionedBlob> getVersioned(String key) { return delegate.getVersioned(key); }
        @Override public void putIfVersion(String key, byte[] content, String version) {
          delegate.putIfVersion(key, content, version);
          if (prepare() && armed && "TERMINAL_COMMIT".equals(window()) && key.startsWith("product-pipeline-runs/")) {
            try {
              if (mapper.readValue(content, ProductPipelineRunDocument.class).run().status() == RunStatus.CHAIN_MATERIALIZED) {
                stop();
              }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
          }
        }
      };
    }

    @Override @Produces @ApplicationScoped
    ProductPipelineProfileCatalog profiles() {
      var base = super.profiles().require("create-chain", "2");
      var first = base.stages().getFirst();
      var stages = approval() ? List.of(new ProfileStage("design-input", first.capabilityId(), first.consumes(),
          first.produces(), new ApprovalPolicy(CreateChainServerRestartProbe.OUTPUT), null, first.retry()),
          base.stages().get(1)) : base.stages();
      return new ProductPipelineProfileCatalog(List.of(new ProductPipelineProfile(base.schemaVersion(),
          base.profileId(), base.profileVersion(), base.runInputs(), stages, base.terminal(), base.dependencyRoots(),
          new CompilerPipelinePolicy(List.of(1), List.of(), List.of(), List.of()))));
    }

    @Produces @ApplicationScoped
    CreateRunSelectionService selections(CreateRunBindingStore bindings, ProductPipelineProfileCatalog profiles) {
      return new CreateRunSelectionService("2026.1", FakeKnowledgeClient.defaultFixture(), bindings, profiles,
          pinResolver(), Clock.systemUTC());
    }

    private static CompilerRunPinResolver pinResolver() {
      var resolver = org.mockito.Mockito.mock(CompilerRunPinResolver.class);
      org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
          .thenReturn(new CompilerRunPin("fixture", "1", "digest", 1, "fixture", "digest",
              new ResolvedCompilerDag(List.of(), List.of(), "dag"), List.of(), Map.of(), Map.of(), List.of(),
              null, null, null, null, null, null));
      return resolver;
    }
  }
}
