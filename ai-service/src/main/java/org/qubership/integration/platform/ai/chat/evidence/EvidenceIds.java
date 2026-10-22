package org.qubership.integration.platform.ai.chat.evidence;

/** Wire vs bare id normalization for pipeline and skill evidence steps. */
public final class EvidenceIds {

  private EvidenceIds() {}

  public static String strip(String id) {
    if (id == null) {
      return null;
    }
    if (id.startsWith("pipeline:")) {
      return id.substring("pipeline:".length());
    }
    if (id.startsWith("skill:")) {
      return id.substring("skill:".length());
    }
    return id;
  }

  public static String wirePipeline(String bare) {
    return "pipeline:" + bare;
  }

  public static String wireSkill(String bare) {
    return "skill:" + bare;
  }
}
