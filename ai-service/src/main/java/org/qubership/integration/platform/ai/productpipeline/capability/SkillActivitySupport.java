package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.activity.LlmRateLimitBackoffSink;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceIds;

/**
 * Shared helpers so CREATE stages emit the same {@code kind=skill} activity path as the planning
 * spine ({@link CapabilitySignal.SkillProgress}), and nest tool/API steps under that skill.
 */
public final class SkillActivitySupport {

  private SkillActivitySupport() {}

  public static CapabilitySignal.SkillProgress running(String skillId) {
    return new CapabilitySignal.SkillProgress(requireSkillId(skillId), "running");
  }

  public static CapabilitySignal.SkillProgress completed(String skillId) {
    return new CapabilitySignal.SkillProgress(requireSkillId(skillId), "completed");
  }

  public static CapabilitySignal.SkillProgress error(String skillId) {
    return new CapabilitySignal.SkillProgress(requireSkillId(skillId), "error");
  }

  /** Binds tool/LLM activity parents to {@code skill:&lt;skillId&gt;} when sinks are active. */
  public static void bindParents(String skillId) {
    String id = requireSkillId(skillId);
    String wire = EvidenceIds.wireSkill(id);
    ToolInvocationSink.setParentSkillId(wire);
    LlmRateLimitBackoffSink.setParentSkillId(wire);
    ToolInvocationSink.currentEmit()
        .ifPresent(emit -> emit.accept(ChatEvent.skillStep(id, "running")));
  }

  public static void clearParents() {
    ToolInvocationSink.clearParentSkillId();
    LlmRateLimitBackoffSink.clearParentSkillId();
  }

  /**
   * Emit consumer from the chat-turn sink, including a Flow worker that only has the conversation
   * id. Empty when no turn is bound.
   */
  public static Optional<Consumer<ChatEvent>> captureTurnEmit(String conversationId) {
    Optional<Consumer<ChatEvent>> emit = ToolInvocationSink.currentEmit();
    if (emit.isPresent() || conversationId == null || conversationId.isBlank()) {
      return emit;
    }
    ToolSession.bind(conversationId);
    try {
      return ToolInvocationSink.currentEmit();
    } finally {
      ToolSession.clear();
    }
  }

  /** Re-binds the turn sink on a worker thread and nests later tool steps under {@code skillId}. */
  public static void bindWorker(String skillId, Optional<Consumer<ChatEvent>> turnEmit) {
    turnEmit.ifPresent(emit -> ToolInvocationSink.bind(emit, EvidenceIds.wireSkill(skillId)));
    bindParents(skillId);
  }

  public static void unbindWorker(Optional<Consumer<ChatEvent>> turnEmit) {
    clearParents();
    if (turnEmit.isPresent()) {
      ToolInvocationSink.unbind();
    }
  }

  /**
   * Prepends skill running and appends skill completed/error around terminal capability signals.
   * Failure is inferred from {@link StageOutcomeClass} on the first {@link
   * CapabilitySignal.Completed}.
   */
  public static List<CapabilitySignal> wrapTerminal(
      String skillId, List<CapabilitySignal> terminalSignals) {
    Objects.requireNonNull(terminalSignals, "terminalSignals");
    List<CapabilitySignal> out = new ArrayList<>(terminalSignals.size() + 1);
    out.add(skillStatusFor(skillId, terminalSignals));
    out.addAll(terminalSignals);
    return List.copyOf(out);
  }

  public static CapabilitySignal skillStatusFor(
      String skillId, List<CapabilitySignal> terminalSignals) {
    return isFailure(terminalSignals) ? error(skillId) : completed(skillId);
  }

  private static boolean isFailure(List<CapabilitySignal> signals) {
    for (CapabilitySignal signal : signals) {
      if (signal instanceof CapabilitySignal.Completed completed) {
        StageOutcomeClass outcomeClass = completed.outcome().outcomeClass();
        return outcomeClass != StageOutcomeClass.SUCCEEDED
            && outcomeClass != StageOutcomeClass.CANDIDATE
            && outcomeClass != StageOutcomeClass.NEEDS_INPUT;
      }
    }
    return false;
  }

  private static String requireSkillId(String skillId) {
    Objects.requireNonNull(skillId, "skillId");
    if (skillId.isBlank()) {
      throw new IllegalArgumentException("skillId must not be blank");
    }
    return skillId;
  }
}
