package org.qubership.integration.platform.ai.compiler;

import java.util.Optional;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/** Prompt-only redaction markers for script bodies and validation against accidental capture. */
public final class ScriptBodyPromptRedaction {

  static final String SCRIPT_GENERATOR_CAPABILITY = "cip-script-generator";

  private static final Pattern OMITTED_PLACEHOLDER =
      Pattern.compile("^<script body omitted, \\d+ chars>$");

  private ScriptBodyPromptRedaction() {}

  static boolean isOmittedPlaceholder(String value) {
    return value != null && OMITTED_PLACEHOLDER.matcher(value.trim()).matches();
  }

  public static boolean isPresentScriptBody(String value) {
    return value != null && !value.isBlank() && !isOmittedPlaceholder(value);
  }

  static Optional<String> validatePatch(String ownerCapabilityId, GraphPatch patch) {
    if (patch == null) {
      return Optional.empty();
    }
    if (patch.propertyPatches() != null) {
      for (PropertyPatch propertyPatch : patch.propertyPatches()) {
        if (propertyPatch == null || propertyPatch.property() == null) {
          continue;
        }
        Optional<String> error =
            validateScriptPropertyChange(
                ownerCapabilityId,
                propertyPatch.property().key(),
                propertyPatch.property().value());
        if (error.isPresent()) {
          return error;
        }
      }
    }
    if (patch.nodePatches() != null) {
      for (NodePatch nodePatch : patch.nodePatches()) {
        if (nodePatch == null || nodePatch.node() == null || nodePatch.node().properties() == null) {
          continue;
        }
        for (PlanProperty property : nodePatch.node().properties()) {
          Optional<String> error =
              validateScriptPropertyChange(ownerCapabilityId, property.key(), property.value());
          if (error.isPresent()) {
            return error;
          }
        }
      }
    }
    return Optional.empty();
  }

  static Optional<String> validateScriptPropertyChange(
      String ownerCapabilityId, String key, String value) {
    if (!"script".equals(key)) {
      return Optional.empty();
    }
    if (!SCRIPT_GENERATOR_CAPABILITY.equals(ownerCapabilityId)) {
      return Optional.of(
          "Property patch for key 'script' is only allowed for "
              + SCRIPT_GENERATOR_CAPABILITY
              + ". Omit key 'script' from this patch. For catch-2 set only exception and priority."
              + " Leave script bodies to "
              + SCRIPT_GENERATOR_CAPABILITY
              + ".");
    }
    if (isOmittedPlaceholder(value)) {
      return Optional.of(
          "Script body must not use the prompt redaction placeholder. Submit the actual Groovy"
              + " script.");
    }
    return Optional.empty();
  }

  static ChainPlanNode stripScriptBodyProperty(ChainPlanNode node) {
    if (!"script".equals(node.type()) || node.properties() == null) {
      return node;
    }
    java.util.List<PlanProperty> filtered =
        node.properties().stream().filter(property -> !"script".equals(property.key())).toList();
    if (filtered.size() == node.properties().size()) {
      return node;
    }
    return new ChainPlanNode(
        node.nodeId(), node.type(), node.label(), node.parentNodeId(), node.order(), filtered);
  }
}
