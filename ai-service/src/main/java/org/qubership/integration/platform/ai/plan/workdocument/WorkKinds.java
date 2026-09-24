package org.qubership.integration.platform.ai.plan.workdocument;

/** Server-owned task lifecycle. The model does not set these states. */
enum WorkTaskState {
  PENDING,
  RUNNING,
  ACCEPTED,
  NEEDS_INPUT,
  NEEDS_RECHECK,
  HALTED
}

enum StepKind {
  TRIGGER,
  SERVICE_CALL,
  REPLY,
  LOCAL
}

enum PortRole {
  INBOUND_PAYLOAD,
  OUTBOUND_REQUEST,
  SUCCESS_RESPONSE,
  FAILURE_OUTCOME,
  RETAINED_CONTEXT
}

enum FieldReferenceKind {
  STEP_PORT,
  RETAINED
}

enum MappingDecision {
  UNSPECIFIED,
  NO_MAPPING
}
