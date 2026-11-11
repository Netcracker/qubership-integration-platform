package org.qubership.integration.platform.ai.plan.workdocument;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

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
  INBOUND_PAYLOAD("payload"),
  OUTBOUND_REQUEST("request"),
  SUCCESS_RESPONSE("success"),
  FAILURE_OUTCOME("failure"),
  RETAINED_CONTEXT("context");

  private final String schemaName;

  PortRole(String schemaName) {
    this.schemaName = schemaName;
  }

  /** Contract port stored on a published field reference. */
  @JsonValue
  public String schemaName() {
    return schemaName;
  }

  @JsonCreator
  static PortRole fromWire(String raw) {
    if (raw == null) {
      return null;
    }
    for (PortRole role : values()) {
      if (role.name().equals(raw) || role.schemaName.equals(raw)) {
        return role;
      }
    }
    throw new IllegalArgumentException("Unknown port " + raw);
  }
}

enum FieldReferenceKind {
  STEP_PORT,
  RETAINED
}

enum MappingDecision {
  UNSPECIFIED,
  NO_MAPPING
}
