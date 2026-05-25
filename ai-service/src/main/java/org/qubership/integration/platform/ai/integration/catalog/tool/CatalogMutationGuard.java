package org.qubership.integration.platform.ai.integration.catalog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * Blocks catalog write {@code @Tool} calls during read-only chat scenarios (planning / ask design).
 */
public final class CatalogMutationGuard {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CatalogMutationGuard() {}

  /**
   * @return non-null rejection JSON for the LLM when the call must be blocked; {@code null} when
   *     allowed.
   */
  public static String rejectOrNull(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return null;
    }
    String scenarioRaw = MDC.get(ChatMdc.SCENARIO_TYPE);
    if (scenarioRaw == null || scenarioRaw.isBlank()) {
      return null;
    }
    ScenarioType scenario;
    try {
      scenario = ScenarioType.valueOf(scenarioRaw.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (scenario != ScenarioType.CREATE_CHAIN_PLAN && scenario != ScenarioType.ASK_DESIGN) {
      return null;
    }
    return CatalogToolResult.error(
        MAPPER,
        toolName,
        CatalogToolResult.CODE_MUTATION_NOT_ALLOWED,
        "catalog mutations are not allowed in scenario " + scenario.name() + ".",
        "Finish planning with read-only catalog lookup and an approved ChainImplementationPlan;"
            + " run createSystem, import, createChain, and element tools only in IMPLEMENT_CHAIN"
            + " (or other execution scenarios).");
  }
}
