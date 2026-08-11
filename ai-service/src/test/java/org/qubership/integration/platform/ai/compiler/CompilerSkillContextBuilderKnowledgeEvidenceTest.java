package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceEmitter;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceSnapshot;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpec;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextRequest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

class CompilerSkillContextBuilderKnowledgeEvidenceTest {

  private ConversationEvidenceStore evidenceStore;
  private CompilerSkillContextBuilder builder;
  private FakeKnowledgeClient knowledgeClient;

  @BeforeEach
  void setUp() {
    evidenceStore = new ConversationEvidenceStore();
    EvidenceEmitter evidenceEmitter = new EvidenceEmitter(evidenceStore);
    knowledgeClient = FakeKnowledgeClient.defaultFixture();

    QipKnowledgePackRepository repository = Mockito.mock(QipKnowledgePackRepository.class);
    CompilerSkillAddonRepository addonRepository = Mockito.mock(CompilerSkillAddonRepository.class);
    when(addonRepository.loadForSkill(Mockito.anyString()))
        .thenReturn(CompilerSkillAddonContext.empty());
    when(repository.loadCompilerGeneratorSpecIndex())
        .thenReturn(
            new CompilerGeneratorSpecIndex(
                List.of(
                    new CompilerGeneratorSpec(
                        "cip-error-handling-generator",
                        "GEN-04",
                        "generator",
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()))));
    when(repository.loadCompilerSkillCatalog()).thenReturn(new CompilerSkillCatalog(List.of()));

    builder =
        new CompilerSkillContextBuilder(
            new ObjectMapper(),
            repository,
            addonRepository,
            Mockito.mock(CompilerSkillRuntimeEligibility.class),
            knowledgeClient,
            knowledgeClient,
            evidenceEmitter);
  }

  @Test
  void buildUserMessage_appendsOneRuntimeContextPackageAndRecordsEvidence() {
    CompilerSkillDocument document =
        documentWithMarkdown("generator-id: GEN-04\n");
    CompilerSkillInputSnapshot snapshot =
        snapshot(
            "Add structured error handling",
            graphWithTypes("try-catch-finally-2", "catch-2"));

    String message =
        builder.buildUserMessage(
            "conversation-1",
            document,
            snapshot,
            null,
            CaptureTool.CAPTURE_GRAPH_PATCH);

    EvidenceSnapshot.Knowledge evidence =
        evidenceStore.getOrCreate("conversation-1").toSnapshot("conversation-1").knowledge();

    assertEquals(1, knowledgeClient.contextCalls());
    assertFalse(message.contains("Knowledge Map"));
    assertFalse(message.contains("getGeneratorContract"));
    assertTrue(message.contains("Runtime Context Package"));
    assertTrue(message.contains("CIP:GEN-000049"));
    assertTrue(message.contains("R-502"));
    assertTrue(message.contains("VR-E-010"));
    assertEquals(knowledgeClient.identity().packageRef(), evidence.packageRef());
  }

  @Test
  void skillLocalChecklistIdDoesNotBecomeAnExactPin() {
    CompilerSkillDocument document =
        documentWithMarkdown("Validate this behavior as VR-EH-001.");
    CompilerSkillInputSnapshot snapshot =
        snapshot(
            "Add structured error handling",
            graphWithTypes("try-catch-finally-2", "catch-2"));

    String message =
        builder.buildUserMessage(
            "conversation-1",
            document,
            snapshot,
            null,
            CaptureTool.CAPTURE_GRAPH_PATCH);

    KnowledgeContextRequest request = knowledgeClient.lastContextRequest();
    assertEquals("cip-error-handling-generator", request.capabilityId());
    assertEquals("GENERATOR", request.phase());
    assertEquals("Add structured error handling", request.requestText());
    assertEquals(List.of("catch-2", "try-catch-finally-2"), request.elementTypes());
    assertFalse(request.requestText().contains("VR-EH-001"));
    assertFalse(message.contains("not found: VR-EH-001"));
  }

  private static CompilerSkillDocument documentWithMarkdown(String markdown) {
    return new CompilerSkillDocument(
        "cip-error-handling-generator",
        "cip-error-handling-generator",
        "skills/cip-error-handling-generator.md",
        "Error handling generator",
        QipKnowledgeCapabilityPhase.GENERATOR,
        true,
        new QipKnowledgePackVersion("test", "test-pack"),
        markdown);
  }

  private static CompilerSkillInputSnapshot snapshot(String request, ChainPlanGraph graph) {
    return new CompilerSkillInputSnapshot(request, "", "", graph, "");
  }

  private static ChainPlanGraph graphWithTypes(String... types) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    for (int i = 0; i < types.length; i++) {
      nodes.add(new ChainPlanNode("n" + i, types[i], types[i], null, null, List.of()));
    }
    return new ChainPlanGraph("1.0", new ChainSection("c", "C"), nodes, List.of());
  }
}
