package org.qubership.integration.platform.ai.plan.workdocument;

/** Record kind a scope may create under one parent. */
public enum WorkRecordKind {
  REQUIREMENT,
  STEP,
  CONNECTION,
  SEQUENCE_GROUP,
  CONDITION_GROUP,
  SPLIT_GROUP,
  LOOP_GROUP,
  RETRY_GROUP,
  ERROR_SCOPE,
  TRANSFER,
  RULE,
  RETAINED_VALUE,
  OUTLINE
}
