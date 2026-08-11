package org.qubership.integration.platform.ai.compiler.addon;

import java.util.List;

/** Runtime addon material appended to a compiler skill agent prompt. */
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
