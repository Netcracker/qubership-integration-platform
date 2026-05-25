package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogMutationGuardTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.SCENARIO_TYPE);
  }

  @Test
  void rejectOrNullBlocksCreateChainPlan() {
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.CREATE_CHAIN_PLAN.name());
    String reject = CatalogMutationGuard.rejectOrNull("createSystem");
    assertNotNull(reject);
    assertTrue(CatalogToolResult.isError(objectMapper, reject));
    assertTrue(reject.contains("CREATE_CHAIN_PLAN"));
  }

  @Test
  void rejectOrNullBlocksAskDesign() {
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.ASK_DESIGN.name());
    assertNotNull(CatalogMutationGuard.rejectOrNull("createChain"));
  }

  @Test
  void rejectOrNullAllowsImplementChain() {
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.IMPLEMENT_CHAIN.name());
    assertNull(CatalogMutationGuard.rejectOrNull("createSystem"));
  }

  @Test
  void rejectOrNullAllowsWhenScenarioMissing() {
    assertNull(CatalogMutationGuard.rejectOrNull("createElement"));
  }
}
