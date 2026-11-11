package org.qubership.integration.platform.ai.compiler.addon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** Build-time index of compiler skill addon documents under the addons directory. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompilerSkillAddonIndex(
    List<String> globalDocuments,
    List<String> globalDataDocuments,
    Map<String, CompilerSkillAddonSkillIndex> skills) {

  public CompilerSkillAddonIndex {
    globalDocuments = globalDocuments != null ? List.copyOf(globalDocuments) : List.of();
    globalDataDocuments = globalDataDocuments != null ? List.copyOf(globalDataDocuments) : List.of();
    skills = skills != null ? Map.copyOf(skills) : Map.of();
  }

  public static CompilerSkillAddonIndex empty() {
    return new CompilerSkillAddonIndex(List.of(), List.of(), Map.of());
  }

  public record CompilerSkillAddonSkillIndex(
      String addonDocument, List<String> examples, AddonRuntimeMetadata runtimeMetadata) {

    public CompilerSkillAddonSkillIndex {
      examples = examples != null ? List.copyOf(examples) : List.of();
    }

    public CompilerSkillAddonSkillIndex(String addonDocument, List<String> examples) {
      this(addonDocument, examples, null);
    }
  }
}
