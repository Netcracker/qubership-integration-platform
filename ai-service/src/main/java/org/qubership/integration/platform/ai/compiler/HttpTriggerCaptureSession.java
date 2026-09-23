package org.qubership.integration.platform.ai.compiler;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Active approved inputs for the HTTP-only trigger capture. */
final class HttpTriggerCaptureSession {

  record Binding(
      ChainSemanticRevision revision, RequirementBrief brief, ElementSkeleton skeleton) {}

  private static final Map<String, Binding> ACTIVE = new ConcurrentHashMap<>();

  private HttpTriggerCaptureSession() {}

  static boolean bind(
      String conversationId, ChainSemanticRevision revision, RequirementBrief brief,
      ElementSkeleton skeleton) {
    if (conversationId == null || revision == null || brief == null || skeleton == null
        || revision.entryPoints().isEmpty() || skeleton.entryPointRoleIds().isEmpty()) {
      return false;
    }
    boolean httpOnly = revision.entryPoints().stream().allMatch(entry ->
        revision.nodes().stream()
            .filter(node -> node.nodeId().equals(entry.triggerNodeId()))
            .anyMatch(node -> node instanceof SemanticNode.Trigger trigger
                && "http-trigger".equals(trigger.capabilityKey())));
    httpOnly &= skeleton.entryPointRoleIds().stream().allMatch(roleId ->
        skeleton.elementRoles().stream().anyMatch(role -> role.roleId().equals(roleId)
            && "http-trigger".equals(role.elementType())));
    if (!httpOnly || revision.entryPoints().size() != skeleton.entryPointRoleIds().size()) {
      return false;
    }
    ACTIVE.put(conversationId, new Binding(revision, brief, skeleton));
    return true;
  }

  static Optional<Binding> get(String conversationId) {
    return conversationId == null ? Optional.empty() : Optional.ofNullable(ACTIVE.get(conversationId));
  }

  static void unbind(String conversationId) {
    if (conversationId != null) {
      ACTIVE.remove(conversationId);
    }
  }
}
