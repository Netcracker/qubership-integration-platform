package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;

/**
 * Shared semantic seam for IDS parsing, brief adaptation, planner validation, and plan rendering.
 */
public record NormalizedDesignFlow(
    String schemaVersion,
    String flowId,
    String chainName,
    String description,
    Trigger trigger,
    List<Participant> participants,
    List<Step> steps,
    List<Connection> connections,
    List<Transformation> transformations,
    List<DataMapping> dataMappings,
    List<String> constraints,
    List<String> assumptions,
    BindingResolutionPolicy bindingResolutionPolicy) {

  public NormalizedDesignFlow {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    flowId = DesignArtifacts.requireText(flowId, "flowId");
    chainName = DesignArtifacts.requireText(chainName, "chainName");
    description = description == null ? "" : description.trim();
    trigger = DesignArtifacts.requireNonNull(trigger, "trigger");
    participants = DesignArtifacts.copyList(participants);
    steps = DesignArtifacts.copyList(steps);
    connections = DesignArtifacts.copyList(connections);
    transformations = DesignArtifacts.copyList(transformations);
    dataMappings = DesignArtifacts.copyList(dataMappings);
    constraints = DesignArtifacts.copyList(constraints);
    assumptions = DesignArtifacts.copyList(assumptions);
    bindingResolutionPolicy =
        bindingResolutionPolicy == null
            ? BindingResolutionPolicy.CATALOG_FIRST
            : bindingResolutionPolicy;
  }

  /** Compatibility constructor for flows created before per-flow binding policy was introduced. */
  public NormalizedDesignFlow(
      String schemaVersion,
      String flowId,
      String chainName,
      String description,
      Trigger trigger,
      List<Participant> participants,
      List<Step> steps,
      List<Connection> connections,
      List<Transformation> transformations,
      List<DataMapping> dataMappings,
      List<String> constraints,
      List<String> assumptions) {
    this(
        schemaVersion,
        flowId,
        chainName,
        description,
        trigger,
        participants,
        steps,
        connections,
        transformations,
        dataMappings,
        constraints,
        assumptions,
        BindingResolutionPolicy.CATALOG_FIRST);
  }

  public enum BindingResolutionPolicy {
    CATALOG_FIRST,
    CATALOG_ONLY
  }

  public record Trigger(
      String kind,
      String sourceParticipantId,
      String interfaceName,
      String endpointOrTopic,
      String operationName,
      List<String> sourceFactIds) {

    public Trigger {
      kind = DesignArtifacts.requireText(kind, "kind");
      sourceParticipantId = DesignArtifacts.requireText(sourceParticipantId, "sourceParticipantId");
      interfaceName = DesignArtifacts.nullableTrimmed(interfaceName);
      endpointOrTopic = DesignArtifacts.nullableTrimmed(endpointOrTopic);
      operationName = DesignArtifacts.nullableTrimmed(operationName);
      sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
    }
  }

  public record Participant(
      String participantId,
      String displayName,
      String systemType,
      List<String> sourceFactIds) {

    public Participant {
      participantId = DesignArtifacts.requireText(participantId, "participantId");
      displayName = DesignArtifacts.requireText(displayName, "displayName");
      systemType = DesignArtifacts.requireText(systemType, "systemType");
      sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
    }
  }

  public record Step(
      String stepId,
      String kind,
      String fromParticipantId,
      String toParticipantId,
      String operationQuery,
      String description,
      List<String> sourceFactIds) {

    public Step {
      stepId = DesignArtifacts.requireText(stepId, "stepId");
      kind = DesignArtifacts.requireText(kind, "kind");
      fromParticipantId = DesignArtifacts.nullableTrimmed(fromParticipantId);
      toParticipantId = DesignArtifacts.nullableTrimmed(toParticipantId);
      operationQuery = DesignArtifacts.nullableTrimmed(operationQuery);
      description = description == null ? "" : description.trim();
      sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
    }
  }

  public record Connection(
      String fromStepId,
      String toStepId,
      String condition,
      List<String> sourceFactIds) {

    public Connection {
      fromStepId = DesignArtifacts.requireText(fromStepId, "fromStepId");
      toStepId = DesignArtifacts.requireText(toStepId, "toStepId");
      condition = DesignArtifacts.nullableTrimmed(condition);
      sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
    }
  }

  public record Transformation(
      String fromStepId,
      String toStepId,
      String description,
      List<String> sourceFactIds) {

    public Transformation {
      fromStepId = DesignArtifacts.requireText(fromStepId, "fromStepId");
      toStepId = DesignArtifacts.requireText(toStepId, "toStepId");
      description = description == null ? "" : description.trim();
      sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
    }
  }

  public record DataMapping(
      String mappingId,
      MappingStage stage,
      String fromStepId,
      String toStepId,
      MappingMode mode,
      List<MappingRule> rules,
      List<String> sourceFactIds) {

    public DataMapping {
      mappingId = DesignArtifacts.requireText(mappingId, "mappingId");
      fromStepId = DesignArtifacts.requireText(fromStepId, "fromStepId");
      toStepId = DesignArtifacts.requireText(toStepId, "toStepId");
      mode = DesignArtifacts.requireNonNull(mode, "mode");
      rules = DesignArtifacts.copyList(rules);
      sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
    }
  }

  /** Leftover stage label from adapted {@code dataMappings}. Absent for v2 mapping intents. */
  public enum MappingStage {
    INITIALIZATION,
    CONVERSION,
    RESPONSE
  }

  public enum MappingMode {
    EXPLICIT,
    PASS_THROUGH
  }

  public record MappingRule(
      String sourcePath,
      String targetPath,
      String expression,
      List<String> sourceFactIds) {

    public MappingRule {
      sourcePath = DesignArtifacts.requireText(sourcePath, "sourcePath");
      targetPath = DesignArtifacts.requireText(targetPath, "targetPath");
      expression = DesignArtifacts.nullableTrimmed(expression);
      sourceFactIds = DesignArtifacts.copyList(sourceFactIds);
    }
  }
}
