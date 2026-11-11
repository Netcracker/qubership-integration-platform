package org.qubership.integration.platform.ai.plan;

import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Outcome of applying one typed mapping-turn result to a requirement brief. */
public record MappingTurnApplication(
    RequirementBrief brief,
    boolean applied,
    MappingQueryAnswer answer,
    MappingTurnResult result) {

  public MappingTurnApplication(RequirementBrief brief, boolean applied) {
    this(brief, applied, null, null);
  }

  public static MappingTurnApplication applied(RequirementBrief brief) {
    return new MappingTurnApplication(brief, true, null, null);
  }

  public static MappingTurnApplication applied(RequirementBrief brief, MappingTurnResult result) {
    return new MappingTurnApplication(brief, true, null, result);
  }

  public static MappingTurnApplication rejected(RequirementBrief brief) {
    return new MappingTurnApplication(brief, false, null, null);
  }

  public static MappingTurnApplication rejected(RequirementBrief brief, MappingTurnResult result) {
    return new MappingTurnApplication(brief, false, null, result);
  }

  public static MappingTurnApplication answered(
      RequirementBrief brief, MappingQueryAnswer answer) {
    return new MappingTurnApplication(brief, false, answer, null);
  }
}
