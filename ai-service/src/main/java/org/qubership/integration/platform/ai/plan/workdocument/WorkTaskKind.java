package org.qubership.integration.platform.ai.plan.workdocument;

/** Stable filling task kind. The key does not include a label or provider model. */
public enum WorkTaskKind {
  UNSPECIFIED,
  LOGICAL_DESIGN,
  SELECT_OPERATION,
  DEFINE_TRANSFERS,
  DESCRIBE_CONTEXT,
  MAP_TRANSFER,
  REPAIR_RULE
}
