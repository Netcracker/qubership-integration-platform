package org.qubership.integration.platform.ai.qipknowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackVersion;

/** Resolves compiler skill-pack fixture paths for unit tests. */
public final class CipKnowledgePackFixturePaths {

  public static final String PACK_DIR = "integration-platform-skills";
  public static final String ADDON_PACK_DIR = "addons";

  private CipKnowledgePackFixturePaths() {}

  public static Path packRoot() {
    return resolveRepoPath(System.getProperty("qip.test.pack-root", "target/public-pack-fixture/" + PACK_DIR));
  }

  public static Path repositoryRoot() {
    Path parent = packRoot().getParent();
    return ".private".equals(parent.getFileName().toString()) ? parent.getParent() : parent;
  }

  public static Path addonRoot() {
    Path runtime = packRoot().resolve("runtime");
    return Files.isDirectory(runtime) ? runtime : packRoot().resolve(ADDON_PACK_DIR);
  }

  public static Path knowledgeRoot() {
    return Files.isRegularFile(packRoot().resolve("runtime/generator-contracts.json"))
        ? packRoot().resolveSibling("rocky-skills-maintenance").resolve("tests/knowledge")
        : packRoot().resolve("knowledge");
  }

  public static CipKnowledgePackVersion packVersion() {
    return new CipKnowledgePackVersion(PACK_DIR, PACK_DIR);
  }

  static Path resolveRepoPath(String name) {
    List<Path> candidates = List.of(Path.of("..", name), Path.of(name));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize().toAbsolutePath();
      if (Files.isDirectory(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException(
        "Compiler pack fixture not found: " + name + " tried " + candidates);
  }
}
