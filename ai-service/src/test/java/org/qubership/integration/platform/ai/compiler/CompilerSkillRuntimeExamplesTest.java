package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.harness.SkillHarnessRequest;
import org.qubership.integration.platform.ai.harness.SkillHarnessService;
import org.qubership.integration.platform.ai.harness.SkillHarnessStatus;
import org.qubership.integration.platform.ai.llm.agent.HarnessSkillAgent;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonBuildSupport;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonIndex;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackBuildGenerator;
import org.qubership.integration.platform.ai.qipknowledge.pack.FilesystemCipKnowledgePackRepository;

class CompilerSkillRuntimeExamplesTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void standaloneExamplesReachTheSkillPromptWithoutAnAddonDocument(@TempDir Path directory) throws Exception {
    var fixture = install(directory, "cip-security-generator", "CAPTURE_GRAPH_PATCH", true);
    var index = JSON.readValue(Files.readString(fixture.versionRoot().resolve("runtime/skill-index.json")),
        CompilerSkillAddonIndex.class);
    assertEquals(List.of("examples/cip-security-generator/invalid-example.json",
        "examples/cip-security-generator/valid-example.json"), index.skills().get("cip-security-generator").examples());
    var context = fixture.addons().loadForSkill("cip-security-generator");
    assertNull(context.skillAddon());
    assertEquals(2, context.examples().size());
    assertFalse(Files.exists(fixture.versionRoot().resolve("addons")), fixture.versionRoot()::toString);

    String prompt = fixture.builder().buildUserMessage(fixture.documents().loadByCapabilityId("cip-security-generator"),
        new CompilerSkillInputSnapshot("Secure the endpoint", "Use RBAC", null, null, null));

    assertTrue(prompt.contains("Runtime example (examples/cip-security-generator/valid-example.json):"), () -> prompt);
    assertTrue(prompt.contains("Counterexample (must be rejected; examples/cip-security-generator/invalid-example.json):"),
        () -> prompt);
    assertTrue(prompt.contains("synthetic-valid-example"), () -> prompt);
    assertTrue(prompt.contains("synthetic-invalid-example"), () -> prompt);
    assertFalse(prompt.contains("Compiler skill addon ("), () -> prompt);
    assertFalse(prompt.contains("ai-service runtime addon ("), () -> prompt);
  }

  @Test
  void aStandaloneSkillWithoutExamplesDoesNotBorrowAnotherSkillsExamples(@TempDir Path directory) throws Exception {
    var fixture = install(directory, "cip-security-generator", "CAPTURE_GRAPH_PATCH", false);
    assertEquals(List.of(), fixture.addons().loadForSkill("cip-security-generator").examples());
    assertEquals(1, fixture.addons().loadForSkill("cip-script-generator").examples().size());
    String prompt = fixture.builder().buildUserMessage(fixture.documents().loadByCapabilityId("cip-security-generator"),
        new CompilerSkillInputSnapshot("Secure the endpoint", "Use RBAC", null, null, null));
    assertFalse(prompt.contains("Runtime example ("), () -> prompt);
    assertFalse(prompt.contains("Counterexample ("), () -> prompt);
    assertFalse(prompt.contains("foreign-script-example"), () -> prompt);
    String foreignPrompt = fixture.builder().buildUserMessage(
        fixture.documents().loadByCapabilityId("cip-script-generator"),
        new CompilerSkillInputSnapshot("Return a greeting", "Keep the greeting", null, null, null));
    assertTrue(foreignPrompt.contains("foreign-script-example"), () -> foreignPrompt);
  }

  @Test
  void standaloneScriptRepairReceivesItsExamples(@TempDir Path directory) throws Exception {
    var fixture = install(directory, "cip-script-generator", "REPAIR_SCRIPT_BODIES", true);
    String prompt = fixture.builder().buildScriptRepairMessage(null,
        fixture.documents().loadByCapabilityId("cip-script-generator"),
        new CompilerSkillInputSnapshot("Return a greeting", "Keep the exact greeting", null, null, null),
        List.of("script-1"), false);

    assertTrue(prompt.contains("Runtime example (examples/cip-script-generator/valid-example.json):"), () -> prompt);
    assertTrue(prompt.contains("synthetic-valid-example"), () -> prompt);
    assertTrue(prompt.contains("Counterexample (must be rejected; examples/cip-script-generator/invalid-example.json):"),
        () -> prompt);
  }

  @Test
  void standaloneHarnessMarksCounterexamplesWithoutApplyingThem(@TempDir Path directory) throws Exception {
    var fixture = install(directory, "cip-security-generator", "CAPTURE_GRAPH_PATCH", true);
    HarnessSkillAgent agent = mock(HarnessSkillAgent.class);
    when(agent.chat(anyString(), anyString())).thenReturn(Multi.createFrom().item("ok"));
    var response = new SkillHarnessService(fixture.documents(), fixture.addons(), agent).run(
        new SkillHarnessRequest("example-conversation", "example-chain", "cip-security-generator", "Use RBAC"));
    assertEquals(SkillHarnessStatus.COMPLETED, response.status());
    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(agent).chat(anyString(), message.capture());
    var renderer = io.quarkus.qute.Engine.builder().addDefaults()
        .addParserHook(new io.quarkiverse.langchain4j.QuarkusPromptTemplateFactory
            .MustacheTemplateVariableStyleParserHook()).build();
    String prompt = renderer.parse(message.getValue()).render();
    assertTrue(prompt.contains("Runtime example (examples/cip-security-generator/valid-example.json)"), () -> prompt);
    assertTrue(prompt.contains("Counterexample (must be rejected; examples/cip-security-generator/invalid-example.json)"),
        () -> prompt);
    assertTrue(prompt.contains("synthetic-valid-example"), () -> prompt);
    assertFalse(prompt.contains("Addon example"), () -> prompt);
    assertFalse(prompt.contains("Apply with catalog element tools"), () -> prompt);
  }

  private static Fixture install(Path directory, String skill, String captureTool, boolean examples) throws Exception {
    Path output = directory.resolve("output");
    CipKnowledgePackBuildGenerator.generate(CipKnowledgePackFixturePaths.packRoot(), output,
        CipKnowledgePackFixturePaths.addonRoot());
    Path runtime = directory.resolve("standalone-runtime");
    Files.createDirectories(runtime.resolve("skills"));
    Files.writeString(runtime.resolve("registry.json"), "{}");
    Files.writeString(runtime.resolve("skills/" + skill + ".json"), JSON.writeValueAsString(java.util.Map.of(
        "runtime", java.util.Map.of("promoted", true, "category", "runtime", "runtimeSkill", true,
            "captureTool", captureTool))));
    if (examples) {
      Path exampleDirectory = runtime.resolve("examples/" + skill);
      Files.createDirectories(exampleDirectory);
      String valid = captureTool.equals("REPAIR_SCRIPT_BODIES")
          ? "{\"patchId\":\"synthetic-valid-example\",\"scripts\":[{\"targetNodeId\":\"script-1\",\"script\":\"return 'ok'\"}]}"
          : "{\"patchId\":\"synthetic-valid-example\",\"ownerCapabilityId\":\"" + skill
              + "\",\"nodePatches\":[],\"edgePatches\":[],\"propertyPatches\":[],\"chainPatches\":[],\"usedKnowledgeRefs\":[]}";
      String invalid = captureTool.equals("REPAIR_SCRIPT_BODIES")
          ? "{\"patchId\":\"synthetic-invalid-example\",\"scripts\":[{\"targetNodeId\":\"script-1\",\"script\":\"\"}]}"
          : "{\"patchId\":\"synthetic-invalid-example\",\"ownerCapabilityId\":\"" + skill
              + "\",\"nodePatches\":[{\"operation\":null}],\"edgePatches\":[],\"propertyPatches\":[],\"chainPatches\":[],\"usedKnowledgeRefs\":[]}";
      Files.writeString(exampleDirectory.resolve("valid-example.json"), valid);
      Files.writeString(exampleDirectory.resolve("invalid-example.json"), invalid);
    } else {
      Files.writeString(runtime.resolve("skills/cip-script-generator.json"), JSON.writeValueAsString(java.util.Map.of(
          "runtime", java.util.Map.of("promoted", true, "category", "runtime", "runtimeSkill", true,
              "captureTool", "REPAIR_SCRIPT_BODIES"))));
      Path foreign = runtime.resolve("examples/cip-script-generator");
      Files.createDirectories(foreign);
      Files.writeString(foreign.resolve("valid-script.json"),
          "{\"patchId\":\"foreign-script-example\",\"scripts\":[{\"targetNodeId\":\"script-1\",\"script\":\"return 'ok'\"}]}");
    }
    Path version = output.resolve(CipKnowledgePackFixturePaths.PACK_DIR);
    CompilerSkillAddonBuildSupport.materialize(runtime, version);
    var repository = new FilesystemCipKnowledgePackRepository(output, CipKnowledgePackFixturePaths.packVersion());
    var addons = CompilerSkillAddonRepository.forFilesystem(output, CipKnowledgePackFixturePaths.packVersion(),
        CompilerSkillRuntimeExamplesTest.class.getClassLoader());
    var knowledge = FakeKnowledgeClient.defaultFixture();
    return new Fixture(version, addons, new CompilerSkillDocumentService(repository),
        new CompilerSkillContextBuilder(JSON, repository, addons, new CompilerSkillRuntimeEligibility(repository),
            knowledge, knowledge));
  }

  private record Fixture(Path versionRoot, CompilerSkillAddonRepository addons, CompilerSkillDocumentService documents,
      CompilerSkillContextBuilder builder) {}
}
