package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;

/** Validates semantic completeness of a normalized design flow before planning. */
public final class NormalizedDesignFlowValidator {

  public void validate(NormalizedDesignFlow flow) {
    Objects.requireNonNull(flow, "flow");
    if (flow.chainName() == null || flow.chainName().isBlank()) {
      throw new IllegalArgumentException("normalized flow requires a nonblank chain name");
    }
    if (flow.trigger() == null) {
      throw new IllegalArgumentException("normalized flow requires exactly one trigger");
    }
    if (flow.steps() == null || flow.steps().isEmpty()) {
      throw new IllegalArgumentException("normalized flow requires at least one process step");
    }

    Set<String> participantIds = new HashSet<>();
    for (NormalizedDesignFlow.Participant participant : flow.participants()) {
      if (participant == null || participant.participantId() == null) {
        throw new IllegalArgumentException("participant id is required");
      }
      if (!participantIds.add(participant.participantId())) {
        throw new IllegalArgumentException("duplicate participant id " + participant.participantId());
      }
      requireProvenance("participant " + participant.participantId(), participant.sourceFactIds());
    }
    requireParticipant(participantIds, flow.trigger().sourceParticipantId(), "trigger");
    requireProvenance("trigger", flow.trigger().sourceFactIds());

    Set<String> stepIds = new HashSet<>();
    for (NormalizedDesignFlow.Step step : flow.steps()) {
      if (step == null) {
        throw new IllegalArgumentException("steps must not contain null entries");
      }
      if (!stepIds.add(step.stepId())) {
        throw new IllegalArgumentException("duplicate step id " + step.stepId());
      }
      if (step.fromParticipantId() != null) {
        requireParticipant(participantIds, step.fromParticipantId(), step.stepId());
      }
      if (step.toParticipantId() != null) {
        requireParticipant(participantIds, step.toParticipantId(), step.stepId());
      }
      if ("service-call".equalsIgnoreCase(step.kind())
          && (step.operationQuery() == null || step.operationQuery().isBlank())) {
        throw new IllegalArgumentException(
            "outbound service-call step " + step.stepId() + " requires an operationQuery");
      }
      requireProvenance("step " + step.stepId(), step.sourceFactIds());
    }

    for (NormalizedDesignFlow.Connection connection : flow.connections()) {
      requireStepOrTrigger(stepIds, connection.fromStepId(), "connection");
      requireStepOrTrigger(stepIds, connection.toStepId(), "connection");
      requireProvenance("connection", connection.sourceFactIds());
    }
    for (NormalizedDesignFlow.Transformation transformation : flow.transformations()) {
      requireStep(stepIds, transformation.fromStepId(), "transformation");
      requireStep(stepIds, transformation.toStepId(), "transformation");
      requireProvenance("transformation", transformation.sourceFactIds());
    }
    for (NormalizedDesignFlow.DataMapping mapping : flow.dataMappings()) {
      requireStepOrTrigger(stepIds, mapping.fromStepId());
      requireStepOrTrigger(stepIds, mapping.toStepId());
      requireProvenance("mapping " + mapping.mappingId(), mapping.sourceFactIds());
      if (mapping.mode() == NormalizedDesignFlow.MappingMode.EXPLICIT
          && (mapping.rules() == null || mapping.rules().isEmpty())) {
        throw new IllegalArgumentException(
            "EXPLICIT mapping " + mapping.mappingId() + " requires rules");
      }
      if (mapping.mode() == NormalizedDesignFlow.MappingMode.PASS_THROUGH
          && mapping.rules() != null
          && !mapping.rules().isEmpty()) {
        throw new IllegalArgumentException(
            "PASS_THROUGH mapping " + mapping.mappingId() + " must not declare rules");
      }
    }
  }

  private static void requireParticipant(Set<String> ids, String participantId, String owner) {
    if (participantId == null || !ids.contains(participantId)) {
      throw new IllegalArgumentException(
          owner + " references unknown participant " + participantId);
    }
  }

  private static void requireStep(Set<String> stepIds, String stepId, String owner) {
    if (stepId == null || !stepIds.contains(stepId)) {
      throw new IllegalArgumentException(owner + " references unknown step " + stepId);
    }
  }

  private static void requireStepOrTrigger(Set<String> stepIds, String stepId, String owner) {
    if ("step-trigger".equals(stepId)) {
      return;
    }
    requireStep(stepIds, stepId, owner);
  }

  private static void requireStepOrTrigger(Set<String> stepIds, String stepId) {
    requireStepOrTrigger(stepIds, stepId, "mapping");
  }

  private static void requireProvenance(String owner, List<String> sourceFactIds) {
    if (sourceFactIds == null || sourceFactIds.isEmpty()) {
      throw new IllegalArgumentException(owner + " requires provenance sourceFactIds");
    }
  }
}
