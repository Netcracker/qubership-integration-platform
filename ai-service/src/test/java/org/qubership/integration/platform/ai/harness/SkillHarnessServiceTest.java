package org.qubership.integration.platform.ai.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.llm.agent.HarnessSkillAgent;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class SkillHarnessServiceTest {

  private static final String CONVERSATION_ID = "conv-harness-qute";
  private static final String CHAIN_ID = "chain-harness-1";
  private static final String SKILL_ID = "cip-variable-generator";

  private CompilerSkillDocumentService documentService;
  private CompilerSkillAddonRepository addonRepository;
  private HarnessSkillAgent harnessSkillAgent;
  private SkillHarnessService service;

  @BeforeEach
  void setUp() {
    documentService = mock(CompilerSkillDocumentService.class);
    addonRepository = mock(CompilerSkillAddonRepository.class);
    harnessSkillAgent = mock(HarnessSkillAgent.class);
    service = new SkillHarnessService(documentService, addonRepository, harnessSkillAgent);
    when(harnessSkillAgent.chat(anyString(), anyString()))
        .thenReturn(Multi.createFrom().item("ok"));
  }

  @Test
  void escapesQuteBracesInSkillBodyAndIncludesStrippedAddon() {
    when(documentService.loadByCapabilityId(SKILL_ID))
        .thenReturn(
            new CompilerSkillDocument(
                SKILL_ID,
                SKILL_ID,
                "skills/cip-variable-generator/SKILL.md",
                "variables",
                QipKnowledgeCapabilityPhase.GENERATOR,
                true,
                new QipKnowledgePackVersion("test", "test"),
                "Use #{VAR} placeholders."));
    when(addonRepository.loadForSkill(SKILL_ID))
        .thenReturn(
            new CompilerSkillAddonContext(
                List.of(),
                new CompilerSkillAddonDocument(
                    "skills/cip-variable-generator.addon.md",
                    """
                    # addon

                    ## Upstream

                    Machine metadata

                    ## Catalog harness note

                    Keep seeded UUIDs. Example token {uuid}.
                    """),
                List.of(
                    new CompilerSkillAddonDocument(
                        "examples/cip-variable-generator/valid-patch-env.json",
                        "{\"keyExpiry\":\"#<ORDER_TTL>\",\"note\":\"{uuid}\"}"))));

    SkillHarnessResponse response =
        service.run(
            new SkillHarnessRequest(
                CONVERSATION_ID, CHAIN_ID, SKILL_ID, "Configure the existing element."));

    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
    verify(harnessSkillAgent).chat(anyString(), userMessage.capture());
    String body = userMessage.getValue();
    assertTrue(body.contains("\\{VAR}"));
    assertTrue(body.contains("Keep seeded UUIDs"));
    assertTrue(body.contains("\\{uuid}"));
    assertTrue(body.contains("Addon example (examples/cip-variable-generator/valid-patch-env.json)"));
    assertTrue(body.contains("#<ORDER_TTL>"));
    assertFalse(body.contains("Machine metadata"));
  }

  @Test
  void skipsAddonSectionWhenSkillHasNoAddon() {
    when(documentService.loadByCapabilityId(SKILL_ID))
        .thenReturn(
            new CompilerSkillDocument(
                SKILL_ID,
                SKILL_ID,
                "skills/cip-variable-generator/SKILL.md",
                "variables",
                QipKnowledgeCapabilityPhase.GENERATOR,
                true,
                new QipKnowledgePackVersion("test", "test"),
                "plain skill"));
    when(addonRepository.loadForSkill(SKILL_ID)).thenReturn(CompilerSkillAddonContext.empty());

    service.run(
        new SkillHarnessRequest(CONVERSATION_ID, CHAIN_ID, SKILL_ID, "Configure it."));

    ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
    verify(harnessSkillAgent).chat(anyString(), userMessage.capture());
    assertFalse(userMessage.getValue().contains("## Skill addon"));
  }
}
