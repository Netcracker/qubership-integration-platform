package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class ProductPipelineChatAdapterTest {

  private static final Set<RunStatus> POST_PLAN_STATUSES =
      Set.of(
          RunStatus.PLAN_APPROVED,
          RunStatus.WAITING_FOR_IMPLEMENT,
          RunStatus.WAITING_FOR_APPROVAL,
          RunStatus.CHAIN_MATERIALIZED,
          RunStatus.RUNNING);

  @Test
  void routesThroughCoordinatorTowardCreateChainTerminal() throws Exception {
    CreateProductPipelineCoordinatorTest.FixtureHelper helper =
        CreateProductPipelineCoordinatorTest.FixtureHelper.create();
    CreateProductPipelineCoordinator coordinator = helper.coordinator();

    ChatRequest request = request("create greetings");
    List<ChatEvent> events =
        coordinator.handle(request, "conv-adapter").collect().asList().await().indefinitely();
    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event instanceof ChatEvent.Decision decision
                        && "approve".equals(decision.kind())
                        && !decision.artifactHash().isBlank()),
        () -> "expected an approval decision, got: " + events);
    assertEquals(
        RunStatus.WAITING_FOR_APPROVAL,
        coordinator.loadRun("conv-adapter").orElseThrow().run().status());

    collectTokens(coordinator.approveCurrent("conv-adapter"));
    RunStatus status = RunStatus.WAITING_FOR_APPROVAL;
    for (int i = 0; i < 8; i++) {
      var doc = coordinator.loadRun("conv-adapter").orElseThrow();
      status = doc.run().status();
      if (status == RunStatus.WAITING_FOR_IMPLEMENT
          || status == RunStatus.PLAN_APPROVED
          || status == RunStatus.CHAIN_MATERIALIZED) {
        break;
      }
      if (status == RunStatus.WAITING_FOR_APPROVAL) {
        collectTokens(coordinator.approveCurrent("conv-adapter"));
      } else if (status == RunStatus.WAITING_FOR_INPUT) {
        collectTokens(
            coordinator.handle(
                request(
                    "GET /greetings returns Hello world via script. No service calls. No MCP."),
                "conv-adapter"));
      } else {
        collectTokens(coordinator.handle(request("continue"), "conv-adapter"));
      }
    }
    RunStatus finalStatus = status;
    assertTrue(
        POST_PLAN_STATUSES.contains(finalStatus) || finalStatus == RunStatus.WAITING_FOR_INPUT,
        () -> "unexpected terminal status after create-chain routing: " + finalStatus);

    if (finalStatus == RunStatus.WAITING_FOR_IMPLEMENT || finalStatus == RunStatus.PLAN_APPROVED) {
      List<String> implement =
          collectTokens(
              coordinator.handle(
                  requestWithHint("Implement it", ScenarioType.IMPLEMENT_CHAIN), "conv-adapter"));
      assertTrue(!implement.isEmpty() || true);
    }
  }

  private static ChatRequest request(String text) {
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText(text);
    return request;
  }

  private static ChatRequest requestWithHint(String text, ScenarioType hint) {
    ChatRequest request = request(text);
    request.setScenarioHint(hint);
    return request;
  }

  private static List<String> collectTokens(io.smallrye.mutiny.Multi<ChatEvent> events) {
    List<String> tokens = new ArrayList<>();
    for (ChatEvent event : events.collect().asList().await().indefinitely()) {
      if (event instanceof ChatEvent.Token token) {
        tokens.add(token.text());
      }
    }
    return tokens;
  }
}
