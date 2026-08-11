package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class ChatExecutionServiceTest {

  private static final String CONVERSATION_ID = "conv-chat-trace";
  private static final Instant FIXED = Instant.parse("2026-07-27T10:00:00Z");

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobs, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runStore = new ProductPipelineRunStore(blobs, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
  }

  @Test
  void recoverFailedSseEmitsErrorThenDone() {
    List<String> frames =
        ChatExecutionService.recoverFailedSse(
                "e648092b-45b0-4249-b3d8-88a991127fd3",
                new RuntimeException("invalid_request_error: dangling tool_call"))
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(2, frames.size());
    assertTrue(frames.get(0).startsWith("event: error\n"));
    assertTrue(frames.get(0).contains("dangling tool_call"));
    assertEquals(
        "event: done\ndata: e648092b-45b0-4249-b3d8-88a991127fd3\n\n", frames.get(1));
  }

  @Test
  void chatTraceReadsProductRunWithoutLegacyBundleStore() {
    ProductPipelineRunDocument created = createRun("run-trace-1");
    appendGraph(created.run().runId(), sampleGraph("Greetings"));

    String description =
        ChatExecutionService.describeActivePlanForTrace(
            CONVERSATION_ID, runStore, artifactStore);

    assertEquals("Greetings nodes=1", description);
  }

  @Test
  void describeActivePlanReturnsNoneWithoutProductRun() {
    String description =
        ChatExecutionService.describeActivePlanForTrace(
            CONVERSATION_ID, runStore, artifactStore);

    assertEquals("(none)", description);
  }

  @Test
  void describeActivePlanFallsBackToMaterializationResult() {
    ProductPipelineRunDocument created = createRun("run-trace-2");
    artifactStore.append(
        new AppendCommand(
            created.run().runId(),
            Kind.MATERIALIZATION_RESULT,
            "1",
            "test",
            "1",
            Map.of("status", "complete"),
            List.of(),
            null,
            provenance(created.run().runId())));

    String description =
        ChatExecutionService.describeActivePlanForTrace(
            CONVERSATION_ID, runStore, artifactStore);

    assertEquals("(materialized)", description);
  }

  private ProductPipelineRunDocument createRun(String runId) {
    return runStore.create(
        new RunSnapshot(
            runId,
            CONVERSATION_ID,
            1L,
            RunStatus.RUNNING,
            "planning",
            List.of(new StageSnapshot("planning", StageStatus.RUNNING, List.of(), null)),
            null));
  }

  private void appendGraph(String runId, ChainPlanGraph graph) {
    artifactStore.append(
        new AppendCommand(
            runId,
            Kind.CHAIN_PLAN_GRAPH,
            "1",
            "test",
            "1",
            graph,
            List.of(),
            null,
            provenance(runId)));
  }

  private static ArtifactProvenance provenance(String runId) {
    return new ArtifactProvenance(
        runId, "planning", "create-chain", "1", "profile-sha", "test", "1", "closure-sha");
  }

  private static ChainPlanGraph sampleGraph(String chainName) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection(chainName, "Sample"),
        List.of(new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }
}
