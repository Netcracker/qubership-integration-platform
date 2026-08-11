package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicyTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

/** Shared compiler pack install helpers for unit tests. */
public final class QipKnowledgePackTestSupport {

  private QipKnowledgePackTestSupport() {}

  public record InstalledPack(
      Path outputDir,
      QipKnowledgePackVersion version,
      FilesystemQipKnowledgePackRepository repository) {}

  public static void configureAddonPackRoot() {
    System.setProperty(
        "qip.ai.qipknowledge.addon-pack-root", QipKnowledgePackFixturePaths.addonRoot().toString());
  }

  public static InstalledPack installPack() throws Exception {
    configureAddonPackRoot();
    Path outputDir = Files.createTempDirectory("qip-pack-test");
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    QipKnowledgePackBuildGenerator.generate(
        packRoot,
        outputDir,
        QipKnowledgePackFixturePaths.addonRoot());
    QipKnowledgePackVersion version = QipKnowledgePackFixturePaths.packVersion();
    return new InstalledPack(
        outputDir,
        version,
        new FilesystemQipKnowledgePackRepository(outputDir, version));
  }

  public static CompilerGeneratorPolicy buildPolicyFromFixture() throws Exception {
    configureAddonPackRoot();
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    Path addonRoot = QipKnowledgePackFixturePaths.addonRoot();
    QipKnowledgePackIngestionResult result =
        new QipKnowledgePackIngestionService().ingest(packRoot);
    return CompilerGeneratorPolicyTestSupport.buildPolicy(
        packRoot,
        result,
        addonRoot);
  }
}
