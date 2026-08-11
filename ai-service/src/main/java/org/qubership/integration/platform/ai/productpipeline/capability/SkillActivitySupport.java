package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    String wire = EvidenceIds.wireSkill(requireSkillId(skillId));
    ToolInvocationSink.setParentSkillId(wire);
    LlmRateLimitBackoffSink.setParentSkillId(wire);
  }

  public static void clearParents() {
    ToolInvocationSink.clearParentSkillId();
    LlmRateLimitBackoffSink.clearParentSkillId();
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
