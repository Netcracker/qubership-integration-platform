package org.qubership.integration.platform.ai.compiler;

import java.util.List;
import org.qubership.integration.platform.ai.chain.edit.ChainEditAction;
import org.qubership.integration.platform.ai.chain.edit.ChainEditDisposition;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/**
 * Renders an edit run's typed artifacts as generator prompt context.
 *
 * <p>The binding is spelled out field by field rather than summarized. A generator that reads
 * "point it at the newer orders operation" has to recall an id, a method and a path that must
 * describe one real operation, and the catalog refuses the element when they disagree.
 */
public final class ChainEditSkillContext {

  private ChainEditSkillContext() {}

  public static List<String> targetNodeIds(SkillWorkspace workspace) {
    if (workspace == null) {
      return List.of();
    }
    return workspace
        .get(SkillArtifactType.CHAIN_EDIT_INTENT)
        .map(a -> ((SkillArtifactPayload.ChainEditIntentPayload) a.payload()).intent())
        .map(intent -> intent.targetNodeIds() == null ? List.<String>of() : intent.targetNodeIds())
        .orElse(List.of());
  }

  /** Returns this generator's scoped edit targets, falling back to the intent for older seeds. */
  public static List<String> targetNodeIds(SkillWorkspace workspace, String skillId) {
    if (workspace == null || skillId == null) {
      return targetNodeIds(workspace);
    }
    List<String> planned =
        workspace
            .get(SkillArtifactType.GENERATOR_PLAN_MANIFEST)
            .map(
                artifact ->
                    ((SkillArtifactPayload.GeneratorPlanManifestPayload) artifact.payload())
                        .manifest())
            .map(GeneratorPlanManifest::plans)
            .orElse(List.of())
            .stream()
            .filter(plan -> skillId.equals(plan.skillId()))
            .findFirst()
            .map(plan -> plan.targetNodeIds() == null ? List.<String>of() : plan.targetNodeIds())
            .orElse(List.of());
    return planned.isEmpty() ? targetNodeIds(workspace) : planned;
  }

  public static String render(SkillWorkspace workspace) {
    ChainEditIntent intent =
        workspace
            .get(SkillArtifactType.CHAIN_EDIT_INTENT)
            .map(a -> ((SkillArtifactPayload.ChainEditIntentPayload) a.payload()).intent())
            .orElse(null);
    List<ResolvedServiceCallBinding> bindings =
        workspace
            .get(SkillArtifactType.SERVICE_CALL_BINDINGS)
            .map(a -> ((SkillArtifactPayload.ServiceCallBindingsPayload) a.payload()).bindings())
            .orElse(List.of());
    if (intent == null && bindings.isEmpty()) {
      return null;
    }

    StringBuilder body = new StringBuilder();
    body.append("Edit intent (this run changes an existing chain, not a new one):\n");
    if (intent != null) {
      body.append("- action: ").append(intent.action()).append('\n');
      body.append("- target element ids: ")
          .append(String.join(", ", intent.targetNodeIds()))
          .append('\n');
      body.append("- requested change: ").append(intent.requestedChange()).append('\n');
      if (intent.action() == ChainEditAction.ADD_ELEMENTS && intent.requestedElementType() != null) {
        body.append("- new element type: ").append(intent.requestedElementType()).append('\n');
      }
      if (intent.cronExpression() != null) {
        body.append("- cron: ").append(intent.cronExpression()).append('\n');
      }
      if (intent.action() == ChainEditAction.CONFIGURE && !intent.propertyKeys().isEmpty()) {
        body.append("- property keys: ").append(String.join(", ", intent.propertyKeys())).append('\n');
      }
    }
    if (intent != null
        && intent.action() == ChainEditAction.ADD_ELEMENTS
        && intent.disposition() == ChainEditDisposition.NEST) {
      body.append("The listed intent targets are the approved structural boundary in the imported graph: ")
          .append(String.join(", ", intent.targetNodeIds()))
          .append(".\n");
      body.append(
          "When wrapping, change parentNodeId only for those ids. Nest them under the new try-2"
              + " or equivalent container. Do not reparent any other existing node.\n");
      body.append(
          "The structure stage owns new nodes, containment, and edge rewrites. A configuration"
              + " generator may change only the node ids in its Active generator plan slice.\n");
    } else if (intent != null
        && intent.action() == ChainEditAction.ADD_ELEMENTS
        && intent.replacesAddressElement()) {
      body.append(
          "The listed target ids are the elements to replace. Add every new element the request"
              + " describes as a linked subgraph in their place, then omit the replaced elements."
              + " Incoming connections of a replaced element attach to the subgraph entry; outgoing"
              + " connections leave from the subgraph exit. A replaced element that sat inside a"
              + " container keeps the new subgraph inside that container. Reuse the id of any edge"
              + " you retarget instead of dropping and re-adding it. Do not change any other"
              + " existing element. A configuration generator may change only the node ids in its"
              + " Active generator plan slice.\n");
    } else if (intent != null
        && intent.action() == ChainEditAction.ADD_ELEMENTS
        && intent.disposition() == ChainEditDisposition.KEEP) {
      body.append(
          "The listed target ids are the insertion address: the element the new elements follow,"
              + " and, when a second id is listed, the element they precede. Splice every new"
              + " element the request describes between those two -- wired to each other in the"
              + " order the request gives, the first connected from the preceding address element"
              + " and the last connected to the following one. Reuse the id of any edge you"
              + " retarget instead of dropping and re-adding it. Neither address element moves,"
              + " is reparented, or is otherwise changed. A configuration generator may change"
              + " only the node ids in its Active generator plan slice.\n");
    } else if (intent != null && intent.action() == ChainEditAction.ADD_ELEMENTS) {
      body.append(
          "Configure the listed target ids. They name the newly placed element. Every other"
              + " element and connection stays as it is.\n");
    } else if (intent != null && intent.action() == ChainEditAction.CONFIGURE) {
      body.append(
          "Change only the listed property keys, on the listed target ids. When more than one"
              + " generator shares this edit, its Active generator plan slice narrows the keys"
              + " further -- touch only the keys named there. Every other property, element, and"
              + " connection stays as it is.\n");
    } else {
      body.append(
          "Change only the target element ids. Every other element and connection stays as it is.\n");
    }

    for (ResolvedServiceCallBinding binding : bindings) {
      body.append("\nResolved catalog operation for element '")
          .append(binding.targetNodeId())
          .append("'. Write every field exactly as given; they describe one operation and the")
          .append(" catalog refuses the element when they disagree.\n");
      body.append("- systemType: ").append(binding.systemType()).append('\n');
      body.append("- integrationSystemId: ").append(binding.systemId()).append('\n');
      body.append("- integrationSpecificationGroupId: ")
          .append(binding.specificationGroupId())
          .append('\n');
      body.append("- integrationSpecificationId: ").append(binding.specificationId()).append('\n');
      body.append("- integrationOperationId: ").append(binding.operationId()).append('\n');
      body.append("- integrationOperationProtocolType: ")
          .append(binding.protocolType())
          .append('\n');
      body.append("- integrationOperationMethod: ").append(binding.method()).append('\n');
      body.append("- integrationOperationPath: ").append(binding.path()).append('\n');
      body.append("- operation name: ").append(binding.displayName()).append('\n');
      body.append("- source: ").append(binding.source()).append('\n');
      body.append("- evidence: ").append(binding.evidenceRef()).append('\n');
    }
    return body.toString();
  }
}
