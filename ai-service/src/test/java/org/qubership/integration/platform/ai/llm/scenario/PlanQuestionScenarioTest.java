package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationFactsService;
import org.qubership.integration.platform.ai.plan.presentation.PlanPresentationViewService;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class PlanQuestionScenarioTest {

  private static final String CONVERSATION_ID = "conv-ask-plan-1";
  private static final Instant FIXED = Instant.parse("2026-07-27T10:00:00Z");

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private PlanQuestionScenario scenario;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    CompilationArtifacts artifacts =
        new CompilationArtifacts(blobs, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runStore = new ProductPipelineRunStore(blobs, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    scenario =
        new PlanQuestionScenario(
            runStore,
            artifactStore,
            new PlanPresentationViewService(new ObjectMapper()),
            new PlanPresentationFactsService());
  }

  @Test
  void planQuestionReadsLatestProductGraphWithoutLegacyBundleStore() {
    putGraph(greetingsGraph());

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest("show graph"), CONVERSATION_ID, ScenarioType.ASK_PLAN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(1));

    sub.awaitCompletion();
    ChatEvent.Token token = (ChatEvent.Token) sub.getItems().getFirst();
    assertTrue(token.text().contains("```mermaid"));
    assertTrue(token.text().contains("HTTP Trigger"));
  }

  @Test
  void returnsScriptDetailsForShowScriptQuestion() {
    putGraph(
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Greetings", "Returns hello"),
            List.of(
                new ChainPlanNode("n1", "http-trigger", "HTTP Trigger", null, null, List.of()),
                new ChainPlanNode(
                    "n2",
                    "script",
                    "Return Greeting",
                    null,
                    null,
                    List.of(new PlanProperty("script", "exchange.in.body = 'Hello world!'")))),
            List.of()));

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest("show script"), CONVERSATION_ID, ScenarioType.ASK_PLAN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(1));

    sub.awaitCompletion();
    ChatEvent.Token token = (ChatEvent.Token) sub.getItems().getFirst();
    assertTrue(token.text().contains("Hello world!"));
    assertTrue(token.text().contains("Return Greeting"));
  }

  @Test
  void planQuestionWithoutProductGraphReturnsNoPlanMessage() {
    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest("show graph"), CONVERSATION_ID, ScenarioType.ASK_PLAN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(1));

    sub.awaitCompletion();
    ChatEvent.Token token = (ChatEvent.Token) sub.getItems().getFirst();
    assertTrue(token.text().contains("No captured chain plan found"));
    assertFalse(token.text().contains("```mermaid"));
  }

  private void putGraph(ChainPlanGraph graph) {
    ProductPipelineRunDocument created =
        runStore.create(
            new RunSnapshot(
                "run-ask-plan-1",
                CONVERSATION_ID,
                1L,
                RunStatus.RUNNING,
                "planning",
                List.of(new StageSnapshot("planning", StageStatus.RUNNING, List.of(), null)),
                null));
    artifactStore.append(
        new AppendCommand(
            created.run().runId(),
            Kind.CHAIN_PLAN_GRAPH,
            "1",
            "test",
            "1",
            graph,
            List.of(),
            null,
            new ArtifactProvenance(
                created.run().runId(),
                "planning",
                "create-chain",
                "1",
                "profile-sha",
                "test",
                "1",
                "closure-sha")));
  }

  private static ChainPlanGraph greetingsGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Greetings", "Returns hello"),
        List.of(
            new ChainPlanNode("n1", "http-trigger", "HTTP Trigger", null, null, List.of()),
            new ChainPlanNode("n2", "script", "Return Greeting", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "n1", "n2", null)));
  }

  private static ChatRequest chatRequest(String text) {
    ChatRequest request = mock(ChatRequest.class);
    org.mockito.Mockito.when(request.getEffectiveUserText()).thenReturn(text);
    return request;
  }
}
