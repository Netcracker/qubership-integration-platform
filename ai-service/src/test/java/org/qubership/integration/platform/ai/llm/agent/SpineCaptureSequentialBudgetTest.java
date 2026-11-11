package org.qubership.integration.platform.ai.llm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpineCaptureSequentialBudgetTest {

  @Test
  void scriptBodyRepairAgentSequentialIsFour() {
    RegisterAiService annotation = ScriptBodyRepairAgent.class.getAnnotation(RegisterAiService.class);
    assertEquals(4, annotation.maxSequentialToolInvocations());
  }

  @Test
  void compilerSkillAgentSequentialIsThree() {
    RegisterAiService annotation = CompilerSkillAgent.class.getAnnotation(RegisterAiService.class);
    assertEquals(3, annotation.maxSequentialToolInvocations());
  }

  /**
   * Six is the floor these agents need, not the number they must hold.
   *
   * <p>Discovery and pattern selection walk the catalog over several calls, and pinning an exact
   * budget makes any legitimate widening — {@code selectApiHubCandidate} raised gather to eight —
   * look like a regression. What matters is that nobody starves them below six.
   */
  @Test
  void discoveryAndPatternAgentsKeepAtLeastSixToolCalls() {
    for (Class<?> agent :
        List.of(GatherRequirementsAgent.class, PatternSelectorAgent.class, DiscoveryAgent.class)) {
      int budget = agent.getAnnotation(RegisterAiService.class).maxSequentialToolInvocations();
      assertTrue(budget >= 6, agent.getSimpleName() + " dropped to " + budget);
    }
  }
}
