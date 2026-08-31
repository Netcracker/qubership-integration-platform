package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class DefaultDesignProcessSkillRunnerTest {

  @Test
  void appendsPromptMaterialFromPlannerAddon() {
    CompilerSkillDocument document =
        new CompilerSkillDocument(
            "cip-design-planner",
            "cip-design-planner",
            "skills/cip-design-planner/SKILL.md",
            "planner",
            QipKnowledgeCapabilityPhase.DECISION,
            true,
            new QipKnowledgePackVersion("test", "test"),
            "Upstream planner instructions");
    CompilerSkillAddonContext addon =
        new CompilerSkillAddonContext(
            List.of(),
            new CompilerSkillAddonDocument(
                "skills/cip-design-planner.addon.md",
                """
                # addon

                ## Upstream

                Machine metadata

                ## Binding-resolution override

                CATALOG_ONLY forbids APIHub planner steps.
                """),
            List.of());

    String prompt =
        DefaultDesignProcessSkillRunner.buildUserMessage(
            document,
            addon,
            "Binding resolution policy: CATALOG_ONLY",
            Optional.empty(),
            Optional.empty());

    assertTrue(prompt.contains("## Runtime addon"));
    assertTrue(prompt.contains("CATALOG_ONLY forbids APIHub planner steps"));
    assertTrue(prompt.contains("overrides conflicting workflow instructions"));
    assertFalse(prompt.contains("Machine metadata"));
  }

  @Test
  void appendsRepairEvidenceSectionOnRepairTurn() {
    CompilerSkillDocument document = minimalDocument();

    String prompt =
        DefaultDesignProcessSkillRunner.buildUserMessage(
            document,
            null,
            "Flow id: flow-1",
            Optional.empty(),
            Optional.of(
                "- outcomeClass: CONTRACT_FAILURE\n- failedStageId: design-planning\n"
                    + "- errorEvidence:\nplanner report mapping-generator step is missing mappingIntentId\n"));

    assertTrue(prompt.contains("## Repair evidence from a previous halt"));
    assertTrue(prompt.contains("CONTRACT_FAILURE"));
    assertTrue(prompt.contains("do not repeat the rejected one"));
    assertTrue(prompt.contains("mappingIntentId=<id>"));
  }

  @Test
  void omitsRepairEvidenceSectionOnFirstTurn() {
    CompilerSkillDocument document = minimalDocument();

    String prompt =
        DefaultDesignProcessSkillRunner.buildUserMessage(
            document, null, "Flow id: flow-1", Optional.empty(), Optional.empty());

    assertFalse(prompt.contains("Repair evidence"));
  }

  private static CompilerSkillDocument minimalDocument() {
    return new CompilerSkillDocument(
        "cip-design-planner",
        "cip-design-planner",
        "skills/cip-design-planner/SKILL.md",
        "planner",
        QipKnowledgeCapabilityPhase.DECISION,
        true,
        new QipKnowledgePackVersion("test", "test"),
        "Upstream planner instructions");
  }
}
