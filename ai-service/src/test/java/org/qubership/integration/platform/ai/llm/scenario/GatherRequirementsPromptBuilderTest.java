package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class GatherRequirementsPromptBuilderTest {

  private CompilerSkillDocumentService skillDocumentService;
  private CompilerSkillAddonRepository addonRepository;
  private RequirementDraftStore draftStore;
  private GatherRequirementsPromptBuilder builder;

  @BeforeEach
  void setUp() {
    skillDocumentService = mock(CompilerSkillDocumentService.class);
    addonRepository = mock(CompilerSkillAddonRepository.class);
    draftStore = new RequirementDraftStore();
    when(skillDocumentService.loadByCapabilityId(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(brainstormingDocument());
    when(addonRepository.loadForSkill(RequirementDraftTool.SOURCE_SKILL_ID))
        .thenReturn(brainstormingAddon());
    builder =
        new GatherRequirementsPromptBuilder(skillDocumentService, addonRepository, draftStore);
  }

  @Test
  void wrapIncludesProcessSkillAndAddonFactsContract() {
    String input = builder.wrap("conv-1", "Create chain named Greetings via script", "en");

    assertTrue(input.contains("<compiler-process-skill id=\"brainstorming\""));
    assertTrue(input.contains("Brainstorming Ideas Into Designs"));
    assertTrue(input.contains("Compiler skill addon (skills/brainstorming.addon.md):"));
    assertTrue(input.contains("explicit `facts`"));
    assertTrue(input.contains("QIP platform defaults"));
    assertTrue(input.contains("Follow the compiler process skill and the brainstorming addon"));
    assertTrue(input.contains("pinned response locale en"));
    assertTrue(input.contains("Create chain named Greetings via script"));
    assertFalse(input.contains("searchCatalogSystems, getApiSpecifications, and listCatalogOperations"));
  }

  @Test
  void wrapSkipsProcessSkillWhenDraftAlreadyReadyForPlan() {
    draftStore.put("conv-1", new RequirementDraft(true, "already ready"));

    String input = builder.wrap("conv-1", "More detail");

    assertFalse(input.contains("<compiler-process-skill"));
    assertTrue(input.contains("More detail"));
  }

  private static CompilerSkillAddonContext brainstormingAddon() {
    return new CompilerSkillAddonContext(
        List.of(),
        new CompilerSkillAddonDocument(
            "skills/brainstorming.addon.md",
            """
            # brainstorming addon

            ## QIP platform defaults

            - Script steps use the QIP `script` element with Groovy.

            Every `READY_FOR_PLAN` capture must include explicit `facts` with stable polarity.
            """),
        List.of());
  }

  private static CompilerSkillDocument brainstormingDocument() {
    return new CompilerSkillDocument(
        "brainstorming",
        "brainstorming",
        "skills/brainstorming/SKILL.md",
        "Brainstorming Ideas Into Designs",
        QipKnowledgeCapabilityPhase.UNSUPPORTED,
        false,
        new QipKnowledgePackVersion("cip_compiler_v2", "cip_compiler_v2"),
        "# Brainstorming Ideas Into Designs\n");
  }
}
