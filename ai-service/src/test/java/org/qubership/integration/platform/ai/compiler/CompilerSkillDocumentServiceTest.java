package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemCipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackTestSupport;

class CompilerSkillDocumentServiceTest {

  private CompilerSkillDocumentService service;

  @BeforeEach
  void setUp(@TempDir Path outputDir) throws Exception {
    CipKnowledgePackTestSupport.configureAddonPackRoot();
    CipKnowledgePackBuildGenerator.generate(CipKnowledgePackFixturePaths.packRoot(), outputDir);
    FilesystemCipKnowledgePackRepository repository =
        new FilesystemCipKnowledgePackRepository(
            outputDir, CipKnowledgePackFixturePaths.packVersion());
    service = new CompilerSkillDocumentService(repository);
  }

  @Test
  void loadsFullSkillMarkdownByCapabilityId() {
    CompilerSkillDocument document = service.loadByCapabilityId("cip-error-handling-generator");

    assertEquals("cip-error-handling-generator", document.capabilityId());
    assertEquals(
        "skills/cip-error-handling-generator/SKILL.md", document.sourcePath());
    assertTrue(document.supported());
    assertFalse(document.markdown().isBlank());
    assertTrue(document.markdown().contains("GEN-04"));
  }

  @Test
  void loadsApmProcessSkillMarkdownBySkillId() {
    CompilerSkillDocument document = service.loadByCapabilityId("brainstorming");

    assertEquals("brainstorming", document.capabilityId());
    assertEquals("skills/brainstorming/SKILL.md", document.sourcePath());
    assertFalse(document.supported());
    assertTrue(document.markdown().contains("Brainstorming Ideas Into Designs"));
  }
}
