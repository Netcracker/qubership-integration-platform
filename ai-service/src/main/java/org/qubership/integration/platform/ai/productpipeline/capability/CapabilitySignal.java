package org.qubership.integration.platform.ai.productpipeline.capability;

/** Streaming signals emitted by a stage capability. Exactly one {@link Completed} is required. */
public sealed interface CapabilitySignal {

  record Message(String text) implements CapabilitySignal {}

  record Progress(String label, String status) implements CapabilitySignal {}

  /** Per-skill activity for chat step events ({@code kind=skill}). */
  record SkillProgress(String skillId, String status) implements CapabilitySignal {}

  record Completed(StageOutcome outcome) implements CapabilitySignal {}
}
