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
      body.append("The listed intent targets are the elements this edit wraps: ")
          .append(String.join(", ", intent.targetNodeIds()))
          .append(".\n");
      body.append(
          "Capture subgraph, not graph. Name the container type, then one branch per branch the"
              + " container has: its child type, the elements that branch creates, and in"
              + " moveExisting the ids of the wrapped elements that move into it. Every listed"
              + " target moves into exactly one branch, and no other id appears anywhere.\n");
      body.append(
          "A wrapped element is only an id: it keeps the type, label, properties, and connections"
              + " the chain already gives it. New elements carry nodeId, type, and label, and no"
              + " parent -- the branch they are declared in is where they nest. Connect new"
              + " elements only to new elements of the same branch; the container's own"
              + " connections to the chain around it are derived, so leave them out.\n");
      body.append(
          "Branch properties carry only what tells a branch from a sibling of the same type, such"
              + " as the exception a catch handles. Every other property belongs to the"
              + " configuration generator that owns it, and a configuration generator may change"
              + " only the node ids in its Active generator plan slice.\n");
      body.append(
          "Example -- wrapping element 'svc-1' in try-catch-finally-2, with a new script in the"
              + " catch: {\"containerType\":\"try-catch-finally-2\",\"branches\":["
              + "{\"childType\":\"try-2\",\"moveExisting\":[\"svc-1\"]},"
              + "{\"childType\":\"catch-2\",\"properties\":[{\"key\":\"exception\","
              + "\"value\":\"java.lang.Exception\"}],\"body\":{\"elements\":[{\"nodeId\":"
              + "\"catch-script-1\",\"type\":\"script\",\"label\":\"Return error\"}]}}]}."
              + " 'svc-1' is an id the chain already has, so it is only ever named in moveExisting."
              + " 'catch-script-1' is an id this capture invents, so it is only ever declared in a"
              + " body. The same id is never both.\n");
    } else if (intent != null
        && intent.action() == ChainEditAction.ADD_ELEMENTS
        && intent.disposition() == ChainEditDisposition.ATTACH) {
      body.append("The listed target id is the container this edit adds a branch to: ")
          .append(String.join(", ", intent.targetNodeIds()))
          .append(". The container is not new -- do not name a containerType.\n");
      body.append(
          "Call captureChainEditSubgraph, never captureChainStructure: this run changes a chain"
              + " that already exists, so there is no whole graph to capture.\n");
      body.append(
          "Capture subgraph, not graph. Name no containerType and exactly one branch: its child"
              + " type, the elements it creates in its own body, and, only when the request"
              + " distinguishes this branch from a sibling of the same type, the property that does"
              + " so -- the condition of an if, the exception of a catch. Never name an existing"
              + " element in this branch: nothing moves, so moveExisting stays empty.\n");
      body.append(
          "Set order to the priority this branch evaluates at whenever the request gives one, or"
              + " implies one by saying this branch comes before or after another. Java does not"
              + " infer an order for an attach the way it numbers a brand-new container's branches,"
              + " because the siblings this one joins were never in this capture to count a"
              + " position from.\n");
      body.append(
          "New elements carry nodeId, type, and label, and no parent -- the branch is where they"
              + " nest. Connect new elements only to new elements of this same branch; the"
              + " container's connections to the rest of the chain do not change. A configuration"
              + " generator may change only the node ids in its Active generator plan slice.\n");
      body.append(
          "Example -- adding a branch to condition 'available-condition' for stock at or above ten,"
              + " ahead of the existing branches: {\"branches\":[{\"childType\":\"if\","
              + "\"properties\":[{\"key\":\"condition\",\"value\":\"${exchangeProperty.available}"
              + " >= 10\"}],\"order\":0,\"body\":{\"elements\":[{\"nodeId\":\"healthy-log-1\","
              + "\"type\":\"log-record\",\"label\":\"Log healthy inventory\"}]}}]}."
              + " 'available-condition' is named once in targetNodeIds, never repeated in the"
              + " capture. 'healthy-log-1' is an id this capture invents, so it nests in the"
              + " branch's own body rather than being connected to the container.\n");
    } else if (intent != null
        && intent.action() == ChainEditAction.ADD_ELEMENTS
        && intent.replacesAddressElement()) {
      body.append("The listed target ids are the elements to replace.\n");
      body.append(
          "Capture subgraph, not graph. Name no container and no branches; put every new element"
              + " the request describes in body, wired to each other in the order the request"
              + " gives. Do not name the replaced elements anywhere in the capture -- Java removes"
              + " them and reconnects their neighbours to the new body's entry and exit"
              + " automatically. A replaced element that sat inside a container keeps the new body"
              + " inside that container.\n");
      body.append(
          "New elements carry nodeId, type, and label, and no parent. Do not change any other"
              + " existing element. A configuration generator may change only the node ids in its"
              + " Active generator plan slice.\n");
    } else if (intent != null
        && intent.action() == ChainEditAction.ADD_ELEMENTS
        && intent.disposition() == ChainEditDisposition.KEEP) {
      body.append("The listed target ids are the insertion address: the element the new elements")
          .append(" follow, and, when a second id is listed, the element they precede.\n");
      body.append(
          "Capture subgraph, not graph. Name no container and no branches; put every new element"
              + " the request describes in body, wired to each other in the order the request"
              + " gives. The connections into and out of the address are derived, so leave them"
              + " out -- Java attaches the first new element to the preceding address element and"
              + " the last one to the following element.\n");
      body.append(
          "New elements carry nodeId, type, and label, and no parent. Neither address element"
              + " moves, is reparented, or is otherwise changed. A configuration generator may"
              + " change only the node ids in its Active generator plan slice.\n");
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
