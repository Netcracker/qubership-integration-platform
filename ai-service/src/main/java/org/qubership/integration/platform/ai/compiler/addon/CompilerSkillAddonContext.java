package org.qubership.integration.platform.ai.compiler.addon;

import java.util.List;

/** Supplemental skill examples and legacy addon documents appended to a compiler skill prompt. */
public record CompilerSkillAddonContext(
    List<CompilerSkillAddonDocument> globalDocuments,
    CompilerSkillAddonDocument skillAddon,
    List<CompilerSkillAddonDocument> examples) {

  public static CompilerSkillAddonContext empty() {
    return new CompilerSkillAddonContext(List.of(), null, List.of());
  }

  public boolean hasContent() {
    return !globalDocuments.isEmpty() || skillAddon != null || !examples.isEmpty();
  }
}
