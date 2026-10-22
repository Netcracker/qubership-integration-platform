package org.qubership.integration.platform.ai.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.a2a.protocol.A2aAgentCardFactory;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCompiler;
import org.qubership.integration.platform.ai.chain.edit.ChainEditRequest;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.service.ChatDecisionService;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeSidecarApi;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeSidecarReadinessCheck;

class RockySkillsTest {
  @TempDir Path output;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void generatedPublicTestFixtureUsesTheRealResourceContract() {
    assertTrue(new RockySkills().available());
  }

  @Test
  void absentPartialCorruptAndLegacyPackagesFailClosed() throws Exception {
    assertFalse(load().available());
    fixture();
    assertTrue(load().available());
    Path skill = output.resolve("META-INF/rocky-skills/skills/brainstorming/SKILL.md");
    Files.writeString(skill, "changed");
    assertFalse(load().available());
    fixture();
    Files.delete(skill);
    assertFalse(load().available());
    fixture();
    Files.writeString(output.resolve("META-INF/rocky-skills/manifest.json"), "{}");
    assertFalse(load().available());
    fixture();
    Files.delete(output.resolve("qipknowledge/integration-platform-skills/compiler-pipeline-index.json"));
    assertFalse(load().available());
  }

  @Test
  void instructionsExcludeMetadataAndPublicCardExcludesCreate() throws Exception {
    fixture();
    assertEquals("# Synthetic capability\n", load().instructions("brainstorming"));
    assertEquals(1, A2aAgentCardFactory.createChainMvpCard("http://example.test", false).skills().size());
    assertEquals(2, A2aAgentCardFactory.createChainMvpCard("http://example.test", true).skills().size());
  }

  @Test
  void createCommandsAndEditExecutionCannotBypassTheGate() throws Exception {
    var facade = mock(CreateChainApplicationFacade.class, CALLS_REAL_METHODS);
    deny(facade);
    assertDenied(() -> facade.start(null));
    assertDenied(() -> facade.continueWithInput(null));
    assertDenied(() -> facade.restart(null));
    assertDenied(() -> facade.approve(null));
    assertDenied(() -> facade.validateApprove(null));
    assertDenied(() -> facade.streamApprove(null));
    assertDenied(() -> facade.streamApproveOnly(null));
    assertDenied(() -> facade.streamCreateChain("old-run", "old-hash", 1));
    var flow = new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowTasks(
        mock(org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport.class));
    deny(flow);
    assertDenied(() -> flow.executeCurrentStage(
        new org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlow.RunContext(
            "saved-run", null, null, null, null)));
    var compiler = mock(ChainEditCompiler.class, CALLS_REAL_METHODS);
    deny(compiler);
    assertDenied(() -> compiler.compile((ChainEditRequest) null));
    var writer = mock(ChainPatchWriter.class, CALLS_REAL_METHODS);
    deny(writer);
    assertDenied(() -> writer.write(null, null));
  }

  @Test
  void publicReadinessDoesNotCallThePrivateSidecar() throws Exception {
    var api = mock(KnowledgeSidecarApi.class);
    var health = new KnowledgeSidecarReadinessCheck(api);
    deny(health);
    assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.UP, health.call().getStatus());
    verifyNoInteractions(api);
  }

  private void assertDenied(org.junit.jupiter.api.function.Executable action) {
    assertEquals(RockySkills.UNAVAILABLE, assertThrows(IllegalStateException.class, action).getMessage());
  }

  private void deny(Object target) throws Exception {
    var field = target.getClass().getDeclaredField("rockySkills");
    field.setAccessible(true);
    field.set(target, load());
  }

  private RockySkills load() throws Exception {
    return new RockySkills(new URLClassLoader(new java.net.URL[] {output.toUri().toURL()}, null));
  }

  private void fixture() throws Exception {
    Path resources = output.resolve("META-INF/rocky-skills");
    var manifest = JSON.createObjectNode();
    manifest.put("schemaVersion", 1).put("package", "rocky-skills")
        .put("runtimeCompatibilityId", "integration-platform-skills");
    var ids = manifest.putArray("activePromptIds");
    var files = manifest.putObject("files");
    for (String id : List.of("brainstorming", "cip-chain-edit-planner",
        "cip-conf-requirement-analysis", "cip-conf-semantic-design")) {
      ids.add(id);
      String relative = "skills/" + id + "/SKILL.md";
      Path path = resources.resolve(relative);
      Files.createDirectories(path.getParent());
      Files.writeString(path, "---\nname: " + id + "\n---\n# Synthetic capability\n");
      files.putObject(relative).put("sha256", HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }
    JSON.writeValue(resources.resolve("manifest.json").toFile(), manifest);
    for (String index : List.of("qip-knowledge-pack-manifest.json", "capability-registry.json", "compiler-pipeline-index.json")) {
      Path path = output.resolve("qipknowledge/integration-platform-skills/" + index);
      Files.createDirectories(path.getParent());
      Files.writeString(path, "{}");
    }
  }
}
