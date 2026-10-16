package org.qubership.integration.platform.ai.compiler.policy;

import java.nio.file.Path;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalogLoader;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;

/** Shared helpers for building compiler generator policy in tests. */
public final class CompilerGeneratorPolicyTestSupport {

  private CompilerGeneratorPolicyTestSupport() {}

  public static CompilerGeneratorPolicy buildPolicy(
      Path packRoot, QipKnowledgePackIngestionResult result, Path addonRoot) {
    QipKnowledgePackScanResult scanResult =
        new QipKnowledgePackScanResult(packRoot, result.manifest().version(), result.files());
    CompilerSkillCatalog catalog = new CompilerSkillCatalogLoader().load(scanResult);
    CompilerGeneratorSpecIndex specIndex = new CompilerGeneratorSpecIndexBuilder().build(scanResult);
    return new CompilerGeneratorPolicyBuilder()
        .build(scanResult, result.registry(), catalog, specIndex, addonRoot)
        .policy();
  }
}
