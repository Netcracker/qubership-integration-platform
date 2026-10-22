package org.qubership.integration.platform.ai.compiler.addon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

@org.junit.jupiter.api.Tag("certified-pack")
class ChainEditPlannerAddonContractTest {

  @Test
  void standaloneStructureAndScriptsFollowTheValidatedPlan() throws Exception {
    String structure =
        Files.readString(
            CipKnowledgePackFixturePaths.packRoot()
                .resolve("skills/cip-structure-generator/SKILL.md"));
    String script =
        Files.readString(
            CipKnowledgePackFixturePaths.packRoot()
                .resolve("skills/cip-script-generator/SKILL.md"));
    String planner =
        Files.readString(
            CipKnowledgePackFixturePaths.packRoot()
                .resolve("skills/cip-chain-edit-planner/SKILL.md"));

    assertTrue(planner.contains("failureDelivery"), planner);
    assertTrue(planner.contains("originalTargetNodeIds"), planner);
    assertTrue(structure.contains("validated structural plan"), structure);
    assertTrue(structure.contains("tryMoveExisting"), structure);
    assertTrue(structure.contains("finallyMoveExisting"), structure);
    assertTrue(structure.contains("catchScriptLabel"), structure);
    assertTrue(script.contains("OM_TASK_RESULT"), script);
    assertTrue(script.contains("reporterSchemaVariant"), script);
    assertTrue(script.contains("executionId"), script);
    assertTrue(script.contains("executionNumber"), script);
    assertFalse(script.contains("Always use CamelHttpResponseCode"), script);
  }
}
