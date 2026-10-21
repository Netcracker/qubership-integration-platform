package org.qubership.integration.platform.ai.compiler.addon;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/** Runtime metadata declared in a compiler skill addon document. */
public record AddonRuntimeMetadata(
    boolean promoted,
    String category,
    boolean runtimeSkill,
    CaptureTool captureTool,
    GraphPatchOwnershipPolicy ownership,
    List<String> inputArtifacts,
    List<String> outputArtifacts) {

  public AddonRuntimeMetadata {
    category = category != null ? category.trim() : "";
    inputArtifacts = inputArtifacts == null ? List.of() : List.copyOf(inputArtifacts);
    outputArtifacts = outputArtifacts == null ? List.of() : List.copyOf(outputArtifacts);
  }
}
