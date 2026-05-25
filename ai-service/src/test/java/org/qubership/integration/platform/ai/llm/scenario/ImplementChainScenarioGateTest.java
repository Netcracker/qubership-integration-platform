package org.qubership.integration.platform.ai.llm.scenario;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ImplementChainScenarioGateTest {

  @Inject
  @ForScenario(ScenarioType.IMPLEMENT_CHAIN)
  ScenarioHandler scenario;

  @Test
  void refusesWithoutApprovedPlan() {
    ChatRequest req = new ChatRequest();
    req.setMessage("implement now");

    List<String> tokens =
        scenario
            .handle(req, "conv-gate-no-approve", ScenarioType.IMPLEMENT_CHAIN)
            .collect()
            .asList()
            .await()
            .indefinitely();

    assertEquals(1, tokens.size());
    assertTrue(tokens.get(0).contains("No approved chain implementation plan"), tokens.get(0));
  }
}
