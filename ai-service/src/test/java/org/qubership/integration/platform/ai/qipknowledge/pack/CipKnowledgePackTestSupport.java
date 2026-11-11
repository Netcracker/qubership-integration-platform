package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicyTestSupport;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

/** Shared compiler pack install helpers for unit tests. */
public final class CipKnowledgePackTestSupport {

  private CipKnowledgePackTestSupport() {}

  public record InstalledPack(
      Path outputDir,
      CipKnowledgePackVersion version,
      FilesystemCipKnowledgePackRepository repository) {}

  public static void configureAddonPackRoot() {
    System.setProperty(
        "qip.ai.qipknowledge.addon-pack-root", CipKnowledgePackFixturePaths.addonRoot().toString());
  }

  public static InstalledPack installPack() throws Exception {
    configureAddonPackRoot();
    Path outputDir = Files.createTempDirectory("qip-pack-test");
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();
    CipKnowledgePackBuildGenerator.generate(
        packRoot,
        outputDir,
        CipKnowledgePackFixturePaths.addonRoot());
    CipKnowledgePackVersion version = CipKnowledgePackFixturePaths.packVersion();
    return new InstalledPack(
        outputDir,
        version,
        new FilesystemCipKnowledgePackRepository(outputDir, version));
  }

  public static CompilerGeneratorPolicy buildPolicyFromFixture() throws Exception {
    configureAddonPackRoot();
    Path packRoot = CipKnowledgePackFixturePaths.packRoot();
    Path addonRoot = CipKnowledgePackFixturePaths.addonRoot();
    CipKnowledgePackIngestionResult result =
        new CipKnowledgePackIngestionService().ingest(packRoot);
    return CompilerGeneratorPolicyTestSupport.buildPolicy(
        packRoot,
        result,
        addonRoot);
  }
}
