package org.qubership.integration.platform.ai.compiler.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class CompilerRuntimeGeneratorRegistryTest {

  private final CompilerRuntimeGeneratorRegistry registry = new CompilerRuntimeGeneratorRegistry();

  @Test
  void readsGeneratorIdsFromAddonManifest() {
    Map<String, String> mappings = registry.skillToGeneratorId(QipKnowledgePackFixturePaths.addonRoot());

    assertEquals("GEN-06", mappings.get("cip-service-call-generator"));
    assertEquals("GEN-02", mappings.get("cip-trigger-generator"));
    assertTrue(mappings.size() >= 27);
  }

  @Test
  void parsesManifestContent(@TempDir Path tempDir) throws Exception {
    Path addons = tempDir.resolve("addons");
    Files.createDirectories(addons);
    Files.writeString(
        addons.resolve("manifest.yaml"),
        """
        version: 2
        skills:
          cip-trigger-generator:
            generatorId: GEN-02
            wired: true
          cip-chain-generator:
            wired: true
        """);

    List<CompilerRuntimeGeneratorRegistry.ManifestSkillBinding> bindings =
        registry.loadBindings(addons);
    assertEquals(2, bindings.size());
    assertEquals(
        Map.of("cip-trigger-generator", "GEN-02"),
        registry.skillToGeneratorId(addons));
  }
}
