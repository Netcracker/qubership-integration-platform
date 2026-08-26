package org.qubership.integration.platform.ai.a2a.transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainArtifactEvidence;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainPublicArtifact;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainPublicArtifactProjector;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ImplementationBlockedRecovery;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;

/**
 * Maps create-chain facade results onto durable A2A Task states.
 *
 * <p>{@code WAITING_FOR_IMPLEMENT} stays {@link A2aTaskState#WORKING} on the normal path. Only a
 * typed {@link ApproveCreateChainOutcome.ImplementationBlocked} maps to {@code INPUT_REQUIRED}.
 */
public final class CreateChainA2aStateMapper {

  private CreateChainA2aStateMapper() {}

  public static A2aTaskState fromSnapshotStatus(CreateChainExecutionStatus status) {
    Objects.requireNonNull(status, "status");
    return switch (status) {
      case WORKING -> A2aTaskState.WORKING;
      case INPUT_REQUIRED -> A2aTaskState.INPUT_REQUIRED;
      case COMPLETED -> A2aTaskState.COMPLETED;
      case FAILED -> A2aTaskState.FAILED;
    };
  }

  public static ProjectedTask project(
      CreateChainExecutionSnapshot snapshot, List<CreateChainEvent> events) {
    Objects.requireNonNull(snapshot, "snapshot");
    events = events == null ? List.of() : List.copyOf(events);
    A2aTaskState state = fromSnapshotStatus(snapshot.status());
    CreateChainPendingAction pending = snapshot.pendingAction();
    for (int i = events.size() - 1; i >= 0; i--) {
      CreateChainEvent event = events.get(i);
      if (event instanceof CreateChainEvent.Waiting waiting) {
        pending = waiting.pendingAction();
        // Waiting always advertises a pending action; stale WORKING snapshots must not downgrade.
        state = A2aTaskState.INPUT_REQUIRED;
        break;
      }
    }
    List<CreateChainPublicArtifact> artifacts = projectArtifacts(events);
    return new ProjectedTask(
        snapshot.taskId(),
        state,
        snapshot,
        pending,
        pendingActionData(pending),
        statusText(snapshot, pending),
        artifacts);
  }

  public static ProjectedTask projectBlocked(
      String taskId, ImplementationBlockedRecovery recovery, CreateChainExecutionSnapshot snapshot) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(recovery, "recovery");
    CreateChainPendingAction pending = toPending(recovery);
    CreateChainExecutionSnapshot blockedSnapshot =
        new CreateChainExecutionSnapshot(
            taskId,
            snapshot == null ? "" : snapshot.runId(),
            CreateChainExecutionStatus.INPUT_REQUIRED,
            snapshot == null ? 0L : snapshot.revision(),
            pending,
            "");
    return new ProjectedTask(
        taskId,
        A2aTaskState.INPUT_REQUIRED,
        blockedSnapshot,
        pending,
        pendingActionData(pending),
        recovery.reason(),
        List.of());
  }

  public static ProjectedTask projectOutcome(
      String taskId, ApproveCreateChainOutcome outcome, CreateChainExecutionSnapshot fallback) {
    Objects.requireNonNull(outcome, "outcome");
    if (outcome instanceof ApproveCreateChainOutcome.Accepted accepted) {
      return project(accepted.snapshot(), accepted.events());
    }
    if (outcome instanceof ApproveCreateChainOutcome.ImplementationBlocked blocked) {
      return projectBlocked(taskId, blocked.recovery(), fallback);
    }
    if (outcome instanceof ApproveCreateChainOutcome.NonRecoverableFailure failure) {
      CreateChainExecutionSnapshot failed =
          new CreateChainExecutionSnapshot(
              taskId,
              fallback == null ? "" : fallback.runId(),
              CreateChainExecutionStatus.FAILED,
              fallback == null ? 0L : fallback.revision(),
              null,
              failure.reason());
      return new ProjectedTask(
          taskId, A2aTaskState.FAILED, failed, null, Map.of(), failure.reason(), List.of());
    }
    if (outcome instanceof ApproveCreateChainOutcome.DuplicateApproval) {
      return project(
          fallback == null
              ? new CreateChainExecutionSnapshot(
                  taskId, "", CreateChainExecutionStatus.WORKING, 0L, null, "")
              : fallback,
          List.of());
    }
    throw new IllegalArgumentException("Unhandled approval outcome: " + outcome.getClass().getName());
  }

  /**
   * Keeps a waiting Task waiting after input the agent cannot act on.
   *
   * <p>A2A has no way to report an error inside an open stream, and the interrupted state is not a
   * failure: the caller can still send the input the Task is waiting for. Refusing with the reason
   * in the status message leaves that door open, where a terminal state would close it.
   */
  public static ProjectedTask projectRefusal(
      String taskId, CreateChainExecutionSnapshot snapshot, String reason) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(snapshot, "snapshot");
    CreateChainPendingAction pending = snapshot.pendingAction();
    CreateChainExecutionSnapshot waiting =
        new CreateChainExecutionSnapshot(
            taskId,
            snapshot.runId(),
            CreateChainExecutionStatus.INPUT_REQUIRED,
            snapshot.revision(),
            pending,
            "");
    return new ProjectedTask(
        taskId,
        A2aTaskState.INPUT_REQUIRED,
        waiting,
        pending,
        pendingActionData(pending),
        reason,
        List.of());
  }

  public static List<CreateChainPublicArtifact> projectArtifacts(List<CreateChainEvent> events) {
    if (events == null || events.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    List<CreateChainPublicArtifact> artifacts = new ArrayList<>();
    for (CreateChainEvent event : events) {
      if (!(event instanceof CreateChainEvent.ArtifactReady ready)) {
        continue;
      }
      Optional<CreateChainPublicArtifact> projected =
          CreateChainPublicArtifactProjector.project(
              new CreateChainArtifactEvidence(
                  ready.artifactId(),
                  ready.artifactType(),
                  ready.revision(),
                  ready.artifactHash(),
                  ready.content()));
      if (projected.isEmpty()) {
        continue;
      }
      CreateChainPublicArtifact artifact = projected.get();
      if (seen.add(artifact.revisionKey())) {
        artifacts.add(artifact);
      }
    }
    return List.copyOf(artifacts);
  }

  private static CreateChainPendingAction toPending(ImplementationBlockedRecovery recovery) {
    if (recovery instanceof ImplementationBlockedRecovery.ApprovePlanEvidence approve) {
      return new CreateChainPendingAction.Approve(
          approve.artifactType(), approve.artifactHash(), approve.revision(), approve.reason());
    }
    if (recovery instanceof ImplementationBlockedRecovery.ClarifyMissingEvidence clarify) {
      return new CreateChainPendingAction.Clarify(clarify.reason(), clarify.missingEvidence());
    }
    throw new IllegalArgumentException("Unhandled recovery: " + recovery.getClass().getName());
  }

  private static Map<String, Object> pendingActionData(CreateChainPendingAction pending) {
    if (pending == null) {
      return Map.of();
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", pending.action());
    if (pending instanceof CreateChainPendingAction.Approve approve) {
      data.put("artifactType", approve.artifactType());
      data.put("artifactHash", approve.artifactHash());
      data.put("revision", approve.revision());
      if (!approve.prompt().isBlank()) {
        data.put("prompt", approve.prompt());
      }
      data.put("allowedActions", List.of("approve"));
    } else if (pending instanceof CreateChainPendingAction.Clarify clarify) {
      data.put("reason", clarify.reason());
      data.put("missingEvidence", clarify.missingEvidence());
      if (PipelineGates.STAGE_RETRY.equals(clarify.gateId())) {
        data.put("action", PipelineGates.RETRY_ACTION);
        data.put("allowedActions", List.of(PipelineGates.RETRY_ACTION));
      } else if (PipelineGates.STAGE_REVISE.equals(clarify.gateId())) {
        data.put("allowedActions", List.of(PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION));
      } else if (PipelineGates.STAGE_INTERNAL_FAILURE.equals(clarify.gateId())) {
        data.put("allowedActions", clarify.missingEvidence());
      } else if (PipelineGates.OWNER_CHOICE.equals(clarify.gateId())) {
        data.put("allowedActions", clarify.missingEvidence());
      } else {
        data.put("allowedActions", List.of("clarify"));
      }
    }
    return Map.copyOf(data);
  }

  /**
   * Spells out the reply that approves the artifact on offer.
   *
   * <p>The status message is the only place a client learns how to approve, so the token travels
   * with the request instead of living in documentation the client never reads. Echoing it back is
   * also what distinguishes a relayed human decision from one a coordinating agent invented.
   */
  public static String approvalInstruction(CreateChainPendingAction.Approve approve) {
    Objects.requireNonNull(approve, "approve");
    return String.format(
        "Reply \"approve %s\" to approve %s revision %d.",
        approvalToken(approve.artifactHash()), approve.artifactType(), approve.revision());
  }

  /**
   * Appends the text tokens an A2A client must echo. Chat has Yes/No and Pass through buttons; A2A
   * has only the status message, so the keywords that latch {@code pendingDesignMode} travel with
   * the question.
   */
  static String clarifyInstruction(CreateChainPendingAction.Clarify clarify) {
    Objects.requireNonNull(clarify, "clarify");
    String gate =
        clarify.gateId() == null || clarify.gateId().isBlank()
            ? PipelineGates.gateOf(clarify.reason()).orElse("")
            : clarify.gateId();
    String reason = PipelineGates.strip(clarify.reason() == null ? "" : clarify.reason());
    if (PipelineGates.IDS_PATH_CHOICE.equals(gate)) {
      return reason + "\nReply \"yes\" or \"no\".";
    }
    if (PipelineGates.MAPPING_GAP.equals(gate)) {
      StringBuilder text = new StringBuilder(reason);
      for (String edge : clarify.missingEvidence()) {
        text.append("\n- ").append(edge);
      }
      text.append(
          "\nReply PASS_THROUGH to apply pass-through for every missing edge, or describe EXPLICIT"
              + " field mappings.");
      return text.toString();
    }
    if (PipelineGates.STAGE_RETRY.equals(gate)) {
      return reason + "\nReply \"retry\" to repeat this stage.";
    }
    if (PipelineGates.STAGE_REVISE.equals(gate)) {
      return reason
          + "\nReply \"retry\" to repeat this stage, or \"revise\" to reopen the diagnosed owner.";
    }
    if (PipelineGates.STAGE_INTERNAL_FAILURE.equals(gate)) {
      if (clarify.missingEvidence().isEmpty()) {
        return reason;
      }
      StringBuilder text = new StringBuilder(reason);
      for (String stageId : clarify.missingEvidence()) {
        text.append("\n- ").append(stageId);
      }
      text.append("\nReply with one of those stage ids to reopen it.");
      return text.toString();
    }
    if (PipelineGates.OWNER_CHOICE.equals(gate)) {
      StringBuilder text = new StringBuilder(reason);
      for (String stageId : clarify.missingEvidence()) {
        text.append("\n- ").append(stageId);
      }
      text.append("\nReply with one of those stage ids.");
      return text.toString();
    }
    return reason;
  }

  /** Shortens an artifact hash to the token a client echoes back. */
  public static String approvalToken(String artifactHash) {
    if (artifactHash == null || artifactHash.isBlank()) {
      return "";
    }
    return artifactHash.length() <= A2aProtocolConstants.APPROVAL_TOKEN_LENGTH
        ? artifactHash
        : artifactHash.substring(0, A2aProtocolConstants.APPROVAL_TOKEN_LENGTH);
  }

  private static String statusText(
      CreateChainExecutionSnapshot snapshot, CreateChainPendingAction pending) {
    if (snapshot.status() == CreateChainExecutionStatus.FAILED
        && snapshot.failureMessage() != null
        && !snapshot.failureMessage().isBlank()) {
      return snapshot.failureMessage();
    }
    if (pending instanceof CreateChainPendingAction.Approve approve) {
      String instruction = approvalInstruction(approve);
      return approve.prompt().isBlank() ? instruction : approve.prompt() + "\n" + instruction;
    }
    if (pending instanceof CreateChainPendingAction.Clarify clarify) {
      return clarifyInstruction(clarify);
    }
    return switch (snapshot.status()) {
      case WORKING -> "Working";
      case INPUT_REQUIRED -> "Input required";
      case COMPLETED -> "Completed";
      case FAILED -> "Failed";
    };
  }

  /** Durable projection ready for persistence and AgentEmitter updates. */
  public record ProjectedTask(
      String taskId,
      A2aTaskState state,
      CreateChainExecutionSnapshot snapshot,
      CreateChainPendingAction pendingAction,
      Map<String, Object> pendingActionData,
      String statusText,
      List<CreateChainPublicArtifact> artifacts) {

    public ProjectedTask {
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(snapshot, "snapshot");
      pendingActionData =
          pendingActionData == null ? Map.of() : Map.copyOf(pendingActionData);
      statusText = statusText == null ? "" : statusText;
      artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public boolean terminal() {
      return state == A2aTaskState.COMPLETED || state == A2aTaskState.FAILED;
    }
  }
}
