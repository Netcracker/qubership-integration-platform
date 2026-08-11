package org.qubership.integration.platform.ai.skill.impl;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.CaptureRoute;
import org.qubership.integration.platform.ai.compiler.CaptureRouter;
import org.qubership.integration.platform.ai.compiler.CompilerSkillCapabilityGate;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntime;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.pipeline.InternalPipelineSkills;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.executor.StreamingSkillExecutor;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Generic {@link StreamingSkillExecutor} adapter that delegates to {@link CompilerSkillRuntime}. */
public final class CompilerSkillExecutor implements StreamingSkillExecutor {

  private final CompilerSkillRuntime compilerSkillRuntime;
  private final String capabilityId;
  private final QipKnowledgeCapabilityPhase phase;
  private final CaptureRoute route;

  public CompilerSkillExecutor(
      CompilerSkillRuntime compilerSkillRuntime,
      CompilerSkillCapabilityGate capabilityGate,
      String capabilityId) {
    this(compilerSkillRuntime, capabilityGate, null, capabilityId);
  }

  public CompilerSkillExecutor(
      CompilerSkillRuntime compilerSkillRuntime,
      CompilerSkillCapabilityGate capabilityGate,
      CaptureRouter captureRouter,
      String capabilityId) {
    this.compilerSkillRuntime = Objects.requireNonNull(compilerSkillRuntime, "compilerSkillRuntime");
    this.capabilityId = requireCapabilityId(capabilityId);
    this.phase =
        Objects.requireNonNull(capabilityGate, "capabilityGate")
            .phaseFor(this.capabilityId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unsupported generic compiler skill: " + this.capabilityId));
    this.route =
        captureRouter != null
            ? captureRouter.routeFor(this.capabilityId)
            : defaultRoute(this.capabilityId, this.phase);
  }

  @Override
  public String skillId() {
    return capabilityId;
  }

  @Override
  public SkillExecutorKind kind() {
    return SkillExecutorKind.AGENT;
  }

  @Override
  public Set<SkillArtifactType> requiredInputs() {
    return switch (route.captureTool()) {
      case CAPTURE_REQUIREMENT_BRIEF -> EnumSet.of(SkillArtifactType.RAW_USER_REQUEST);
      case CAPTURE_SELECTED_PATTERN -> EnumSet.of(SkillArtifactType.REQUIREMENT_BRIEF);
      case CAPTURE_NAMING_MANIFEST ->
          EnumSet.of(SkillArtifactType.REQUIREMENT_BRIEF, SkillArtifactType.ELEMENT_SKELETON);
      case CAPTURE_CONFIGURED_TRIGGER_SET ->
          EnumSet.of(
              SkillArtifactType.REQUIREMENT_BRIEF,
              SkillArtifactType.SELECTED_PATTERN,
              SkillArtifactType.ELEMENT_SKELETON,
              SkillArtifactType.NAMING_MANIFEST,
              SkillArtifactType.CHAIN_PLAN_GRAPH);
      case CAPTURE_CHAIN_STRUCTURE ->
          EnumSet.of(
              SkillArtifactType.ELEMENT_SKELETON,
              SkillArtifactType.NAMING_MANIFEST,
              SkillArtifactType.CONFIGURED_TRIGGER_SET);
      case CAPTURE_CHAIN_PLAN ->
          EnumSet.of(
              SkillArtifactType.RAW_USER_REQUEST,
              SkillArtifactType.REQUIREMENT_BRIEF,
              SkillArtifactType.SELECTED_PATTERN);
      case CAPTURE_GRAPH_PATCH, REPAIR_SCRIPT_BODIES, CAPTURE_VALIDATION_RESULT ->
          EnumSet.of(SkillArtifactType.CHAIN_PLAN_GRAPH);
    };
  }

  @Override
  public Set<SkillArtifactType> outputTypes() {
    return switch (route.captureTool()) {
      case CAPTURE_REQUIREMENT_BRIEF -> EnumSet.of(SkillArtifactType.REQUIREMENT_BRIEF);
      case CAPTURE_SELECTED_PATTERN ->
          EnumSet.of(SkillArtifactType.SELECTED_PATTERN, SkillArtifactType.ELEMENT_SKELETON);
      case CAPTURE_NAMING_MANIFEST -> EnumSet.of(SkillArtifactType.NAMING_MANIFEST);
      case CAPTURE_CONFIGURED_TRIGGER_SET ->
          EnumSet.of(SkillArtifactType.CONFIGURED_TRIGGER_SET, SkillArtifactType.CHAIN_PLAN_GRAPH);
      case CAPTURE_CHAIN_STRUCTURE ->
          EnumSet.of(SkillArtifactType.CHAIN_STRUCTURE, SkillArtifactType.CHAIN_PLAN_GRAPH);
      case CAPTURE_CHAIN_PLAN -> EnumSet.of(SkillArtifactType.CHAIN_PLAN_GRAPH);
      case CAPTURE_GRAPH_PATCH, REPAIR_SCRIPT_BODIES ->
          EnumSet.of(SkillArtifactType.GRAPH_PATCH, SkillArtifactType.CHAIN_PLAN_GRAPH);
      case CAPTURE_VALIDATION_RESULT ->
          EnumSet.of(
              SkillArtifactType.PRE_BUILD_VALIDATION, SkillArtifactType.PLAN_CAPTURE_OUTCOME);
    };
  }

  @Override
  public Multi<ChatEvent> runStreaming(SkillRunContext context, SkillWorkspace workspace) {
    return compilerSkillRuntime.runStreaming(context, workspace, capabilityId);
  }

  @Override
  public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
    return compilerSkillRuntime.run(context, workspace, capabilityId);
  }

  private static String requireCapabilityId(String capabilityId) {
    Objects.requireNonNull(capabilityId, "capabilityId");
    String trimmed = capabilityId.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("capabilityId is required");
    }
    return trimmed;
  }

  private static CaptureRoute defaultRoute(
      String capabilityId, QipKnowledgeCapabilityPhase phase) {
    CaptureTool captureTool =
        switch (phase) {
          case DISCOVERY -> CaptureTool.CAPTURE_REQUIREMENT_BRIEF;
          case GRAPH_CONSTRUCTION -> CaptureTool.CAPTURE_CHAIN_PLAN;
          case GENERATOR -> CaptureTool.CAPTURE_GRAPH_PATCH;
          case VALIDATOR ->
              InternalPipelineSkills.PLAN_VALIDATOR.equals(capabilityId)
                  ? CaptureTool.CAPTURE_VALIDATION_RESULT
                  : CaptureTool.CAPTURE_VALIDATION_RESULT;
          default -> throw new IllegalArgumentException("Unsupported compiler phase: " + phase);
        };
    return new CaptureRoute(capabilityId, captureTool);
  }
}
