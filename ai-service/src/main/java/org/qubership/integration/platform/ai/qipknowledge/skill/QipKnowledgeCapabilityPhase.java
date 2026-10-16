package org.qubership.integration.platform.ai.qipknowledge.skill;

/** Stable backend phase for a QIP knowledge skill capability. */
public enum QipKnowledgeCapabilityPhase {
  DISCOVERY,
  DECISION,
  PATTERN_SELECTION,
  GRAPH_CONSTRUCTION,
  GENERATOR,
  VALIDATOR,
  MATERIALIZER,
  REVERSE,
  PUBLISHING,
  UNSUPPORTED
}
