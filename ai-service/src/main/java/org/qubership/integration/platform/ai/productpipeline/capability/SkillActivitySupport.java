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
    bindWorker(skillId, turnEmit, null);
  }

  /**
   * Looks up the chat-turn consumer by conversation id, then binds it on this worker.
   */
  public static void bindWorker(String skillId, String conversationId) {
    bindWorker(skillId, captureTurnEmit(conversationId), conversationId);
  }

  /**
   * When {@code turnEmit} is empty, looks up the chat-turn consumer by conversation id and binds
   * it on this worker.
   */
  public static void bindWorker(
      String skillId, Optional<Consumer<ChatEvent>> turnEmit, String conversationId) {
    Objects.requireNonNull(turnEmit, "turnEmit");
    Optional<Consumer<ChatEvent>> emit =
        turnEmit.isPresent() ? turnEmit : captureTurnEmit(conversationId);
    emit.ifPresent(
        consumer ->
            ToolInvocationSink.bind(consumer, EvidenceIds.wireSkill(skillId), conversationId));
    bindParents(skillId);
  }

  /** Clears parent skill ids and undoes {@link #bindWorker} if that call bound the turn sink. */
  public static void unbindWorker() {
    clearParents();
    ToolInvocationSink.unbindIfBound();
  }

  /** Delegates to {@link #unbindWorker()}. Existing stages pass the optional they captured for bind. */
  public static void unbindWorker(Optional<Consumer<ChatEvent>> turnEmit) {
    Objects.requireNonNull(turnEmit, "turnEmit");
    unbindWorker();
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
