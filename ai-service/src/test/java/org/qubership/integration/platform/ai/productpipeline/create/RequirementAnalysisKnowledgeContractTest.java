package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.Multi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceEmitter;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceSnapshot;
import org.qubership.integration.platform.ai.plan.RequirementBriefCoverageValidator;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;

class RequirementAnalysisKnowledgeContractTest {

  @Test
  void skillDeclaresPinnedKnowledgeClientContractInsteadOfMarkdownPaths() throws Exception {
    Path skill = resolveSkillMarkdown();
    String markdown = Files.readString(skill);
    assertFalse(
        markdown.contains("knowledge/corporate/CORPORATE_CIP_STANDARDS.md"),
        "skill must not declare direct Markdown knowledge paths");
    assertFalse(markdown.contains("knowledge/corporate/pattern-standards.md"));
    assertFalse(markdown.contains("knowledge/corporate/element-standards.md"));
    assertTrue(
        markdown.contains("KnowledgeContextPackage"),
        "skill must pin the Runtime Context Package contract");
    assertTrue(markdown.contains("Runtime Context Package"));
    assertTrue(
        markdown.contains("Do not read `knowledge/**`"),
        "skill must forbid reading knowledge files from the git tree");
    assertTrue(markdown.contains("cip-standards"));
    assertTrue(markdown.contains("element-standards"));
  }

  @Test
  void analysisRequestsOneContextPackageAndRecordsEvidence() {
    FakeKnowledgeClient client = FakeKnowledgeClient.defaultFixture();
    ConversationEvidenceStore store = new ConversationEvidenceStore();
    EvidenceEmitter emitter = new EvidenceEmitter(store);
    RequirementDraft approved = RequirementFactFixtures.greetingsApprovedDraft();
    AtomicReference<String> lastMessage = new AtomicReference<>();

    RequirementAnalysisCapability capability =
        new RequirementAnalysisCapability(
            client,
            client,
            new RequirementBriefCoverageValidator(),
            null,
            null,
            null,
            null,
            (conversationId, userMessage) -> {
              lastMessage.set(userMessage);
              return Multi.createFrom().item(ChatEvent.token("ok"));
            },
            emitter);

    StageExecutionContext context =
        new StageExecutionContext(
            "run-1",
            "conv-context",
            "requirement-analysis",
            "exec-1",
            "attempt-1",
            null,
            null,
            List.of(),
            Map.of("approvedDraft", approved));

    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();

    assertEquals(1, client.contextCalls());
    assertTrue(lastMessage.get().contains("Runtime Context Package"));
    assertTrue(lastMessage.get().contains("CIP:GEN-000049"));

    EvidenceSnapshot.Knowledge knowledge =
        store.getOrCreate("conv-context").toSnapshot("conv-context").knowledge();
    assertEquals(client.identity().packageRef(), knowledge.packageRef());
    assertEquals(List.of("CIP:GEN-000049"), knowledge.objectIds());
    assertEquals(
        client.exact(client.context(), "CIP:GEN-000049").object().content().body().length(),
        knowledge.contentChars());
    assertFalse(signals.isEmpty());
  }

  private static Path resolveSkillMarkdown() {
    Path cwd = Path.of(".").toAbsolutePath().normalize();
    Path module =
        Files.isRegularFile(cwd.resolve("pom.xml")) && Files.isDirectory(cwd.resolve("src/main/java"))
            ? cwd
            : cwd.resolve("ai-service");
    Path skill =
        module
            .getParent()
            .resolve("integration-platform-skills/.apm/skills/cip-requirement-analyzer/SKILL.md");
    if (!Files.isRegularFile(skill)) {
      throw new IllegalStateException("cip-requirement-analyzer SKILL.md not found at " + skill);
    }
    return skill;
  }
}
