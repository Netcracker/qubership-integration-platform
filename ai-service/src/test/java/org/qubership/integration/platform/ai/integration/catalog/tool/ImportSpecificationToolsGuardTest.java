package org.qubership.integration.platform.ai.integration.catalog.tool;

import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportSpecificationToolsGuardTest {

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.SCENARIO_TYPE);
  }

  @Test
  void allowsImportOnlyInImportSpecificationScenario() {
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.IMPORT_SPECIFICATION.name());
    assertNull(ImportSpecificationTools.rejectUnlessImportScenario());
  }

  @Test
  void blocksImportInImplementChainScenario() {
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.IMPLEMENT_CHAIN.name());
    String blocked = ImportSpecificationTools.rejectUnlessImportScenario();
    assertNotNull(blocked);
    assertTrue(blocked.contains("IMPORT_SPECIFICATION"));
  }
}
