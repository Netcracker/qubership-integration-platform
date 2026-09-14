package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.llm.agent.DesignPlanCaptureAgent;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;

class DefaultDesignPlanSkillRunnerTest {

  @AfterEach
  void cleanup() {
    ToolSession.clear();
    DesignPlanCaptureSession.unbind("conversation");
  }

  @Test
  void cleansConversationBindingsWhenTheAgentFails() throws Exception {
    CompilerSkillDocumentService documents = mock(CompilerSkillDocumentService.class);
    CompilerSkillAddonRepository addons = mock(CompilerSkillAddonRepository.class);
    DesignPlanCaptureAgent agent = mock(DesignPlanCaptureAgent.class);
    CompilerSkillDocument document =
        new CompilerSkillDocument(
            CipDesignPlannerAdapter.SKILL_ID,
            "source",
            "path",
            "Planner",
            null,
            true,
            null,
            "planner skill");
    when(documents.loadByCapabilityId(CipDesignPlannerAdapter.SKILL_ID)).thenReturn(document);
    when(addons.loadForSkill(CipDesignPlannerAdapter.SKILL_ID))
        .thenReturn(CompilerSkillAddonContext.empty());
    when(agent.chat(anyString(), anyString())).thenThrow(new IllegalStateException("model failed"));
    ChainSemanticRevision revision = DesignPlanTestFixtures.revision();
    DefaultDesignPlanSkillRunner runner =
        new DefaultDesignPlanSkillRunner(documents, addons, agent);

    assertThrows(
        IllegalStateException.class,
        () ->
            runner.runOnce(
                "conversation",
                "input",
                Optional.empty(),
                Optional.empty(),
                sha256(document.markdown()),
                "2026.1",
                revision,
                DesignPlanTestFixtures.brief(),
                DesignPlanTestFixtures.pin(revision)));

    assertTrue(DesignPlanCaptureSession.binding("conversation").isEmpty());
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
