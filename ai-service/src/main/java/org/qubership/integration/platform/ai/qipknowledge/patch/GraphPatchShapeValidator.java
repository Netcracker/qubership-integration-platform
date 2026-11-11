package org.qubership.integration.platform.ai.qipknowledge.patch;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Validates required GraphPatch operation fields before apply. */
public final class GraphPatchShapeValidator {

  private GraphPatchShapeValidator() {}

  public static List<String> validate(GraphPatch patch) {
    List<String> errors = new ArrayList<>();
    if (patch.nodePatches() != null) {
      for (int index = 0; index < patch.nodePatches().size(); index++) {
        validateNodePatch(errors, index, patch.nodePatches().get(index));
      }
    }
    if (patch.edgePatches() != null) {
      for (int index = 0; index < patch.edgePatches().size(); index++) {
        validateEdgePatch(errors, index, patch.edgePatches().get(index));
      }
    }
    if (patch.propertyPatches() != null) {
      for (int index = 0; index < patch.propertyPatches().size(); index++) {
        validatePropertyPatch(errors, index, patch.propertyPatches().get(index));
      }
    }
    if (patch.chainPatches() != null) {
      for (int index = 0; index < patch.chainPatches().size(); index++) {
        validateChainPatch(errors, index, patch.chainPatches().get(index));
      }
    }
    return List.copyOf(errors);
  }

  public static String summarize(List<String> errors) {
    if (errors.isEmpty()) {
      return "";
    }
    StringBuilder summary = new StringBuilder(errors.get(0));
    for (int index = 1; index < errors.size(); index++) {
      summary.append("; ").append(errors.get(index));
    }
    summary.append(
        ". Use ADD or UPDATE propertyPatches; both upsert by property key on the target node.");
    return summary.toString();
  }

  private static void validateNodePatch(List<String> errors, int index, NodePatch nodePatch) {
    if (nodePatch == null || nodePatch.operation() == null) {
      errors.add("nodePatches[" + index + "].operation is required");
      return;
    }
    if (nodePatch.operation() == GraphPatchOperation.ADD
        || nodePatch.operation() == GraphPatchOperation.UPDATE) {
      if (nodePatch.node() == null || isBlank(nodePatch.node().nodeId())) {
        errors.add(
            "nodePatches["
                + index
                + "].node.nodeId is required for "
                + nodePatch.operation());
      }
    }
  }

  private static void validateEdgePatch(List<String> errors, int index, EdgePatch edgePatch) {
    if (edgePatch == null || edgePatch.operation() == null) {
      errors.add("edgePatches[" + index + "].operation is required");
    }
  }

  private static void validatePropertyPatch(
      List<String> errors, int index, PropertyPatch propertyPatch) {
    if (propertyPatch == null || propertyPatch.operation() == null) {
      errors.add("propertyPatches[" + index + "].operation is required");
      return;
    }
    if (isBlank(propertyPatch.targetNodeId())) {
      errors.add("propertyPatches[" + index + "].targetNodeId is required");
    }
    PlanProperty property = propertyPatch.property();
    if (property == null || isBlank(property.key())) {
      errors.add("propertyPatches[" + index + "].property.key is required");
    }
  }

  private static void validateChainPatch(List<String> errors, int index, ChainPatch chainPatch) {
    if (chainPatch == null || chainPatch.operation() == null) {
      errors.add("chainPatches[" + index + "].operation is required");
      return;
    }
    PlanProperty property = chainPatch.property();
    if (property == null || isBlank(property.key())) {
      errors.add("chainPatches[" + index + "].property.key is required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
