package org.qubership.integration.platform.ai.compiler.capture;

import org.qubership.integration.platform.ai.chain.edit.ChainEditStructureBase;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

/** Typed capture slot aligned with {@code compiler.addon.CaptureTool} adapters. */
public enum CaptureSlot {
  REQUIREMENT_BRIEF(RequirementBrief.class, Scope.CONVERSATION),
  SELECTED_PATTERN(SelectedPattern.class, Scope.CONVERSATION),
  ELEMENT_SKELETON(ElementSkeleton.class, Scope.CONVERSATION),
  NAMING_MANIFEST(NamingManifest.class, Scope.CONVERSATION),
  CONFIGURED_TRIGGER_SET(ConfiguredTriggerSet.class, Scope.CONVERSATION),
  CHAIN_STRUCTURE(ChainStructure.class, Scope.CONVERSATION),
  CHAIN_PLAN(ChainPlanGraph.class, Scope.CONVERSATION),
  CHAIN_EDIT_STRUCTURE_BASE(ChainEditStructureBase.class, Scope.CONVERSATION),
  GRAPH_PATCH(GraphPatch.class, Scope.CAPABILITY),
  SCRIPT_BODY_REPAIR(GraphPatch.class, Scope.CAPABILITY),
  VALIDATION_RESULT(ValidationResult.class, Scope.CONVERSATION);

  public enum Scope {
    CONVERSATION,
    CAPABILITY
  }

  private final Class<?> valueType;
  private final Scope scope;

  CaptureSlot(Class<?> valueType, Scope scope) {
    this.valueType = valueType;
    this.scope = scope;
  }

  public Class<?> valueType() {
    return valueType;
  }

  public Scope scope() {
    return scope;
  }
}
