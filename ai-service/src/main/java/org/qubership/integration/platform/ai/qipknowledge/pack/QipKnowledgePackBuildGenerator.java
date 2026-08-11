package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonBuildSupport;

/** Build-time entrypoint that ingests a QIP skill pack and writes classpath indexes. */
public final class QipKnowledgePackBuildGenerator {

  private QipKnowledgePackBuildGenerator() {}

  public static void main(String[] args) throws Exception {
    generate(resolvePackRoot(args), resolveOutputRoot(args), resolveAddonRoot());
  }

  public static void generate(Path packRoot, Path outputRoot) throws Exception {
    generate(packRoot, outputRoot, resolveAddonRoot());
  }

  public static void generate(Path packRoot, Path outputRoot, Path addonPackRoot) throws Exception {
    if (!Files.isDirectory(packRoot)) {
      throw new IllegalArgumentException(
          "QIP skill pack root must be a directory: " + packRoot);
    }
    QipKnowledgeExportValidator.validatePack(packRoot);
    QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
    QipKnowledgePackIngestionResult result = ingestionService.ingest(packRoot);
    ingestionService.writeArtifacts(result, outputRoot, addonPackRoot);
    Path versionDir =
        QipKnowledgePackIndexLoader.resolveVersionDir(
            outputRoot, result.manifest().version());
    if (addonPackRoot != null) {
      CompilerSkillAddonBuildSupport.materialize(addonPackRoot, versionDir);
    }
  }

  private static Path resolvePackRoot(String[] args) {
    String fromArg = args.length > 0 ? args[0] : null;
    String fromProperty = System.getProperty("qip.ai.qipknowledge.pack-root");
    String raw = firstNonBlank(fromArg, fromProperty);
    if (raw == null) {
      throw new IllegalArgumentException(
          "packRoot is required. Pass it as the first argument or set -Dqip.ai.qipknowledge.pack-root");
    }
    return Path.of(raw).normalize().toAbsolutePath();
  }

  private static Path resolveOutputRoot(String[] args) {
    String fromArg = args.length > 1 ? args[1] : null;
    String fromProperty = System.getProperty("qip.ai.qipknowledge.output-root");
    String raw = firstNonBlank(fromArg, fromProperty);
    if (raw == null) {
      throw new IllegalArgumentException(
          "outputRoot is required. Pass it as the second argument or set -Dqip.ai.qipknowledge.output-root");
    }
    return Path.of(raw).normalize().toAbsolutePath();
  }

  private static Path resolveAddonRoot() {
    String fromProperty = System.getProperty("qip.ai.qipknowledge.addon-pack-root");
    if (fromProperty == null || fromProperty.isBlank()) {
      return null;
    }
    Path path = Path.of(fromProperty).normalize().toAbsolutePath();
    return Files.isDirectory(path) ? path : null;
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (second != null && !second.isBlank()) {
      return second;
    }
    return null;
  }
}
