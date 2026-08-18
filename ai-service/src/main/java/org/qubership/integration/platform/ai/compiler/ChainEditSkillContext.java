package org.qubership.integration.platform.ai.compiler;

import java.util.List;
import org.qubership.integration.platform.ai.chain.edit.ChainEditIntent;
import org.qubership.integration.platform.ai.chain.edit.ResolvedServiceCallBinding;
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
    }
    body.append(
        "Change only the target element ids. Every other element and connection stays as it is.\n");
    body.append(
        "When wrapping, UPDATE parentNodeId only for those target element ids. Never reparent a"
            + " trigger.\n");

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
