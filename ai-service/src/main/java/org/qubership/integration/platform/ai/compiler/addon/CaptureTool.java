package org.qubership.integration.platform.ai.compiler.addon;

import java.util.Arrays;

/** Capture tool names supported by the compiler runtime. */
public enum CaptureTool {
  CAPTURE_REQUIREMENT_BRIEF("captureRequirementBrief"),
  CAPTURE_SELECTED_PATTERN("captureSelectedPattern"),
  CAPTURE_NAMING_MANIFEST("captureNamingManifest"),
  CAPTURE_CONFIGURED_TRIGGER_SET("captureConfiguredTriggerSet"),
  CAPTURE_CHAIN_STRUCTURE("captureChainStructure"),
  CAPTURE_CHAIN_PLAN("captureChainPlan"),
  CAPTURE_GRAPH_PATCH("captureGraphPatch"),
  REPAIR_SCRIPT_BODIES("repairScriptBodies"),
  CAPTURE_VALIDATION_RESULT("captureValidationResult");

  private final String toolName;

  CaptureTool(String toolName) {
    this.toolName = toolName;
  }

  public String toolName() {
    return toolName;
  }

  public static CaptureTool fromToolName(String toolName) {
    return Arrays.stream(values())
        .filter(tool -> tool.toolName.equals(toolName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported capture tool: " + toolName));
  }
}
