package org.qubership.integration.platform.ai.qipknowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

/** Resolves compiler skill-pack fixture paths for unit tests. */
public final class QipKnowledgePackFixturePaths {

  public static final String PACK_DIR = "integration-platform-skills";
  public static final String ADDON_PACK_DIR = "addons";

  private QipKnowledgePackFixturePaths() {}

  public static Path packRoot() {
    return resolveRepoPath(PACK_DIR);
  }

  public static Path addonRoot() {
    return packRoot().resolve(ADDON_PACK_DIR);
  }

  public static QipKnowledgePackVersion packVersion() {
    return new QipKnowledgePackVersion(PACK_DIR, PACK_DIR);
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
