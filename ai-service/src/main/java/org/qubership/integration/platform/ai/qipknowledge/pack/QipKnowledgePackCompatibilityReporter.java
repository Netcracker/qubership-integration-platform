package org.qubership.integration.platform.ai.qipknowledge.pack;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDisposition;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;

/** Builds a one-pack compatibility report from ingestion artifacts. */
public class QipKnowledgePackCompatibilityReporter {

  public String buildReport(
      QipKnowledgePackManifest manifest,
      CapabilityRegistry registry,
      List<UnsupportedQipKnowledgeItem> unsupportedItems,
      CompilerSkillCatalog skillCatalog) {
    StringBuilder report = new StringBuilder();
    report.append("# QIP Knowledge Pack Compatibility Report\n\n");
    report.append("## Pack\n\n");
    report.append("- Version: `").append(manifest.version().normalized()).append("`\n");
    report.append("- Source path: `").append(manifest.sourcePath()).append("`\n");
    report.append("- Files scanned: ").append(manifest.fileChecksums().size()).append("\n");
    report.append("- Skills: ").append(manifest.skillIds().size()).append("\n\n");

    appendCompilerSkillCatalogSection(report, skillCatalog);

    report.append("## Capabilities\n\n");
    report.append("- Supported: ").append(manifest.supportedCapabilityIds().size()).append("\n");
    report.append("- Unsupported: ").append(manifest.unsupportedCapabilityIds().size()).append("\n\n");

    if (!manifest.supportedCapabilityIds().isEmpty()) {
      report.append("### Supported capability IDs\n\n");
      for (String id : manifest.supportedCapabilityIds()) {
        report.append("- `").append(id).append("`\n");
      }
      report.append("\n");
    }

    if (!unsupportedItems.isEmpty()) {
      report.append("### Unsupported items\n\n");
      for (UnsupportedQipKnowledgeItem item : unsupportedItems) {
        report.append("- `").append(item.id()).append("` (").append(item.sourcePath()).append("): ");
        report.append(item.reason()).append("\n");
      }
      report.append("\n");
    }

    report.append("## Registry summary\n\n");
    report.append("- Total capabilities: ").append(registry.capabilities().size()).append("\n");

    return report.toString();
  }

  private static void appendCompilerSkillCatalogSection(
      StringBuilder report, CompilerSkillCatalog skillCatalog) {
    report.append("## Compiler skill catalog\n\n");
    report.append("- Total catalog entries: ").append(skillCatalog.skills().size()).append("\n");
    report.append("- Runnable: ").append(skillCatalog.runnableSkills().size()).append("\n");

    Map<CompilerSkillDisposition, Integer> counts = skillCatalog.dispositionCounts();
    for (CompilerSkillDisposition disposition : CompilerSkillDisposition.values()) {
      report
          .append("- ")
          .append(disposition.name())
          .append(": ")
          .append(counts.getOrDefault(disposition, 0))
          .append("\n");
    }
    report.append("\n");
  }
}
