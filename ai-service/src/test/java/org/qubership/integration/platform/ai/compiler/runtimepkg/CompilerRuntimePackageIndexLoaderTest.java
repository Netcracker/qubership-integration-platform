package org.qubership.integration.platform.ai.compiler.runtimepkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanner;

class CompilerRuntimePackageIndexLoaderTest {

  private static final List<String> REQUIRED_FILES =
      List.of(
          "language-model.yaml",
          "grammar-model.yaml",
          "semantic-model.yaml",
          "rule-engine.yaml",
          "decision-tree.yaml",
          "generator-packages.yaml",
          "validation-rules.yaml",
          "runtime-capabilities.yaml");

  private final QipKnowledgePackScanner scanner = new QipKnowledgePackScanner();
  private final CompilerRuntimePackageIndexLoader loader = new CompilerRuntimePackageIndexLoader();

  @Test
  void indexContainsAllRequiredArtifacts(@TempDir Path tempDir) throws Exception {
    writeRuntimePackage(tempDir, "version: test\nitems: []\n");

    CompilerRuntimePackageIndex index = load(tempDir);

    assertEquals(REQUIRED_FILES.size(), index.artifacts().size());
    assertTrue(index.findByType("language-model").isPresent());
    assertTrue(index.findByType("runtime-capabilities").isPresent());
  }

  @Test
  void checksumChangesWhenArtifactContentChanges(@TempDir Path tempDir) throws Exception {
    writeRuntimePackage(tempDir, "version: one\n");
    String firstChecksum =
        load(tempDir).findByType("language-model").orElseThrow().checksum();

    Files.writeString(
        tempDir.resolve("compiler-runtime-package/language-model.yaml"), "version: two\n");
    String secondChecksum =
        load(tempDir).findByType("language-model").orElseThrow().checksum();

    assertNotEquals(firstChecksum, secondChecksum);
  }

  @Test
  void indexStoresTopLevelYamlKeys(@TempDir Path tempDir) throws Exception {
    writeRuntimePackage(tempDir, "zeta: 1\nalpha: 2\n");

    CompilerRuntimePackageArtifact artifact =
        load(tempDir).findByType("language-model").orElseThrow();

    assertEquals(List.of("alpha", "zeta"), artifact.topLevelKeys());
  }

  private CompilerRuntimePackageIndex load(Path packRoot) {
    QipKnowledgePackScanResult scanResult = scanner.scan(packRoot);
    return loader.load(scanResult);
  }

  private static void writeRuntimePackage(Path root, String content) throws Exception {
    Path runtimePackage = root.resolve("compiler-runtime-package");
    Files.createDirectories(runtimePackage);
    for (String file : REQUIRED_FILES) {
      Files.writeString(runtimePackage.resolve(file), content);
    }
  }
}
