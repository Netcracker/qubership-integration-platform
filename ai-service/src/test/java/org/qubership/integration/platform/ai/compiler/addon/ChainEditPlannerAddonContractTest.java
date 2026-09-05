package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class ChainEditPlannerAddonContractTest {

  @Test
  void structureAndScriptAddonsMustFollowTheValidatedPlan() throws Exception {
    String structure =
        Files.readString(
            QipKnowledgePackFixturePaths.addonRoot()
                .resolve("skills/cip-structure-generator.addon.md"));
    String script =
        Files.readString(
            QipKnowledgePackFixturePaths.addonRoot()
                .resolve("skills/cip-script-generator.addon.md"));
    String planner =
        Files.readString(
            QipKnowledgePackFixturePaths.addonRoot()
                .resolve("skills/cip-chain-edit-planner.addon.md"));

    assertTrue(planner.contains("CHAIN_EDIT_STRUCTURAL_PLAN"), planner);
    assertTrue(structure.contains("validated structural plan"), structure);
    assertTrue(structure.contains("valid-om-gp02-wrap-subgraph.json"), structure);
    assertTrue(script.contains("OM_TASK_RESULT"), script);
    assertTrue(script.contains("valid-patch-catch-om-task-failed.json"), script);
    assertFalse(script.contains("Always use CamelHttpResponseCode"), script);
  }
}
