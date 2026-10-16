package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Conversation-scoped state shared by the planner runner and its capture tool. */
public final class DesignPlanCaptureSession {

  private static final Map<String, Binding> BY_CONVERSATION = new ConcurrentHashMap<>();

  private DesignPlanCaptureSession() {}

  public static Binding bind(
      String conversationId,
      ChainSemanticRevision revision,
      String revisionHash,
      String apiRelease,
      RequirementBrief brief,
      CompilerRunPin pin) {
    String id = Objects.requireNonNull(conversationId, "conversationId").trim();
    if (id.isEmpty()) {
      throw new IllegalArgumentException("conversationId is required");
    }
    Binding binding =
        new Binding(
            id,
            revision,
            revisionHash,
            apiRelease,
            brief,
            pin,
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(List.of()),
            new AtomicBoolean());
    if (BY_CONVERSATION.putIfAbsent(id, binding) != null) {
      throw new IllegalStateException("Design plan capture is already active for " + id);
    }
    return binding;
  }

  public static Optional<Binding> binding(String conversationId) {
    String id = conversationId == null ? "" : conversationId.trim();
    if (!id.isEmpty()) {
      return Optional.ofNullable(BY_CONVERSATION.get(id));
    }
    return BY_CONVERSATION.size() == 1
        ? Optional.of(BY_CONVERSATION.values().iterator().next())
        : Optional.empty();
  }

  public static void unbind(String conversationId) {
    if (conversationId != null) {
      BY_CONVERSATION.remove(conversationId.trim());
    }
  }

  public record Binding(
      String conversationId,
      ChainSemanticRevision revision,
      String revisionHash,
      String apiRelease,
      RequirementBrief brief,
      CompilerRunPin pin,
      AtomicReference<DesignPlanContract> candidate,
      AtomicReference<String> rejection,
      AtomicReference<List<DesignPlanContractFinding>> rejectionFindings,
      AtomicBoolean terminal) {}
}
