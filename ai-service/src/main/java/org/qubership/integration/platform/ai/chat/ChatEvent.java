package org.qubership.integration.platform.ai.chat;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.activity.ActivityDisplayLabels;
import org.qubership.integration.platform.ai.productpipeline.facade.PendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;

/**
 * One item a {@code ScenarioHandler} emits during a chat turn. Handlers produce typed events only;
 * {@code ChatExecutionService} owns all SSE framing and escaping so the wire format lives in one place.
 */
public sealed interface ChatEvent {

  /** Action names a decision card offers, localized in the interface, never on the wire. */
  String APPROVE_ACTION = "approve";

  String REQUEST_CHANGES_ACTION = "request-changes";

  /** Writes the chain into the catalog: the one irreversible step, never a model's to take. */
  String CREATE_ACTION = "create-chain";

  /** Approves the plan and creates the chain, each validated against its own binding. */
  String APPROVE_AND_CREATE_ACTION = "approve-and-create";

  /** Imports the selected API Hub specification into the runtime catalog. */
  String IMPORT_ACTION = "import-specification";

  /** Writes a proposed change into a chain the user already has: irreversible, so never a model's. */
  String APPLY_CHAIN_PATCH_ACTION = "apply-chain-patch";

  /** Retries the supported create operation without exposing its pipeline stage. */
  String RETRY_CREATION_ACTION = "retry-creation";

  /** Reopens requirement analysis for a defective requirement brief. */
  String EDIT_REQUIREMENTS_ACTION = "edit-requirements";

  /** Reopens design planning for a defective implementation plan. */
  String REBUILD_PLAN_ACTION = "rebuild-plan";

  /** Replaces a live deployment after the reader confirms: irreversible, so never a model's. */
  String REDEPLOY_ACTION = "redeploy-chain";

  /** Leaves the live deployment as it is. Distinct from request-changes, which belongs to patch. */
  String CANCEL_REDEPLOY_ACTION = "cancel-redeploy";

  /** Deploys a chain that is not live on domain default: irreversible, so never a model's. */
  String DEPLOY_ACTION = "deploy-chain";

  /** Leaves the chain undeployed. Distinct from cancel-redeploy, which keeps a live deployment. */
  String CANCEL_DEPLOY_ACTION = "cancel-deploy";

  /** Removes a live deployment after the reader confirms: irreversible, so never a model's. */
  String UNDEPLOY_ACTION = "undeploy-chain";

  /** Leaves the live deployment in place. Distinct from cancel-deploy, which never deployed. */
  String CANCEL_UNDEPLOY_ACTION = "cancel-undeploy";

  /** Re-reads deployment state after an asynchronous deployment is still processing. */
  String REFRESH_DEPLOYMENT_ACTION = "refresh-deployment";

  /** Starts a patch proposal grounded in the deployment failure kept by the server. */
  String PROPOSE_DEPLOYMENT_FIX_ACTION = "propose-deployment-fix";

  /** Acknowledges a deployment failure without mutating the chain. */
  String DISMISS_DEPLOYMENT_FAILURE_ACTION = "dismiss-deployment-failure";

  /** Session logging Off, chosen on the card that runs before createDeployment. */
  String SESSION_LOGGING_OFF_ACTION = "session-logging-off";

  /** Session logging Error. */
  String SESSION_LOGGING_ERROR_ACTION = "session-logging-error";

  /** Session logging Info. */
  String SESSION_LOGGING_INFO_ACTION = "session-logging-info";

  /** Session logging Debug. */
  String SESSION_LOGGING_DEBUG_ACTION = "session-logging-debug";

  /** Typed session-logging actions; the level is never parsed from free text. */
  List<String> SESSION_LOGGING_ACTIONS =
      List.of(
          SESSION_LOGGING_OFF_ACTION,
          SESSION_LOGGING_ERROR_ACTION,
          SESSION_LOGGING_INFO_ACTION,
          SESSION_LOGGING_DEBUG_ACTION);

  /** Artifact type a chain-patch card binds to. */
  String CHAIN_PATCH_ARTIFACT = "CHAIN_PATCH";

  /** Artifact type a redeploy card binds to. */
  String REDEPLOY_ARTIFACT = "REDEPLOY";

  /** Artifact type a first-deploy card binds to. */
  String DEPLOY_ARTIFACT = "DEPLOY";

  /** Artifact type an undeploy card binds to. */
  String UNDEPLOY_ARTIFACT = "UNDEPLOY";

  /** Artifact type for a deployment result that needs a human follow-up. */
  String DEPLOYMENT_FAILURE_ARTIFACT = "DEPLOYMENT_FAILURE";

  /** Artifact type a session-logging card binds to. */
  String SESSION_LOGGING_ARTIFACT = "SESSION_LOGGING";

  /** Wire actions for the IDS path-choice gate; the interface renders them as Yes / No. */
  List<String> IDS_PATH_CHOICE_ACTIONS = List.of("yes", "no");

  /** Wire actions for the mapping-gap gate: pass the payload through, or describe the mappings. */
  List<String> MAPPING_GAP_ACTIONS = List.of("pass_through", "describe_mappings");

  /**
   * What the transcript records when the reader answers the import card.
   *
   * <p>Read by the import stage as the confirmation itself, so the stage checks a marker this
   * service wrote rather than guessing at wording a reader chose.
   */
  String IMPORT_MARKER = "Import the API Hub specification";

  /** Stream metadata emitted once at the start of an SSE turn. */
  record Meta(String conversationId) implements ChatEvent {}

  /** Streamed assistant content shown to the user. */
  record Token(String text, LastAssistantTurn.Kind turnKind) implements ChatEvent {
    public Token(String text) {
      this(text, LastAssistantTurn.Kind.OTHER);
    }

    public Token {
      turnKind = turnKind == null ? LastAssistantTurn.Kind.OTHER : turnKind;
    }
  }

  /** Activity step progress (rendered as {@code event: step}, replace-by-id). */
  record Step(String id, String kind, String status, String label, String parentId)
      implements ChatEvent {}

  /**
   * A gate the run stopped at, rendered as a card in the transcript (rendered as {@code event:
   * decision}).
   *
   * @param id identity of the gate; the same gate re-emitted after a reconnect carries the same id
   * @param kind {@code approve} or {@code clarify}
   * @param question server-authored text in the language of the conversation
   * @param actions what the gate accepts, empty when the answer is free text
   */
  record Decision(
      String id,
      String kind,
      String question,
      String artifactType,
      String artifactHash,
      long revision,
      String reason,
      List<String> missingEvidence,
      List<String> actions,
      RecoveryPresentation recovery)
      implements ChatEvent {

    public Decision(
        String id,
        String kind,
        String question,
        String artifactType,
        String artifactHash,
        long revision,
        String reason,
        List<String> missingEvidence,
        List<String> actions) {
      this(
          id,
          kind,
          question,
          artifactType,
          artifactHash,
          revision,
          reason,
          missingEvidence,
          actions,
          null);
    }

    public Decision {
      missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  /** Server-owned presentation for a contextual create-chain recovery card. */
  record RecoveryPresentation(
      String category,
      String title,
      String summary,
      String preservedWork,
      String technicalDetails,
      Long retryDelayMs,
      String runId,
      String failedStageId) {}

  /** Terminal error surfaced to the user (rendered as {@code event: error}). */
  record Error(String message) implements ChatEvent {}

  static ChatEvent meta(String conversationId) {
    return new Meta(conversationId);
  }

  static ChatEvent token(String text) {
    return new Token(text);
  }

  static ChatEvent token(String text, LastAssistantTurn.Kind turnKind) {
    return new Token(text, turnKind);
  }

  static ChatEvent step(String id, String kind, String status, String label, String parentId) {
    String display =
        "skill".equals(kind) || "tool".equals(kind)
            ? ActivityDisplayLabels.of(kind, label)
            : label;
    return new Step(id, kind, status, display, parentId);
  }

  /** Skill activity row. {@code id} is {@code skill:<skillId>}; {@code label} is the display gerund. */
  static ChatEvent skillStep(String skillId, String status) {
    return step("skill:" + skillId, "skill", status, skillId, null);
  }

  /**
   * Derives the card from what a run waits for.
   *
   * <p>The only mapping from a pipeline wait to a chat event, so a gate cannot reach the reader as
   * prose. {@code question} wins over the prompt carried by the wait, which is blank when the run
   * is resumed rather than freshly stopped.
   */
  static ChatEvent decision(PendingAction pending, long revision, String question) {
    return decision(pending, revision, question, null);
  }

  /**
   * Same, with the actions the caller knows this gate accepts.
   *
   * <p>The plan gate offers creation, the others do not, and only the pipeline knows which is
   * which — so the list is passed in rather than guessed from the artifact type here.
   */
  static ChatEvent decision(
      PendingAction pending, long revision, String question, List<String> actions) {
    Objects.requireNonNull(pending, "pending");
    String text = question == null ? "" : question.strip();
    if (pending instanceof PendingAction.Approve approve) {
      return new Decision(
          "approve:" + approve.artifactHash(),
          approve.action(),
          text.isBlank() ? approve.prompt() : text,
          approve.artifactType(),
          approve.artifactHash(),
          approve.revision(),
          null,
          List.of(),
          actions == null ? List.of(APPROVE_ACTION, REQUEST_CHANGES_ACTION) : actions,
          null);
    }
    if (pending instanceof PendingAction.Clarify clarify) {
      return new Decision(
          "clarify:" + revision,
          clarify.action(),
          text,
          null,
          null,
          revision,
          clarify.reason(),
          clarify.missingEvidence(),
          actions == null ? List.of() : actions,
          recoveryFor(clarify));
    }
    throw new IllegalArgumentException("unsupported pending action: " + pending.action());
  }

  /**
   * The implementation gate, offered as its own decision.
   *
   * <p>Creating the chain is a command distinct from approving the plan, so it carries its own id
   * and its own binding: a card left over from an earlier plan cannot create anything.
   */
  static ChatEvent createChainDecision(
      String artifactType, String planHash, long revision, String question) {
    Objects.requireNonNull(artifactType, "artifactType");
    Objects.requireNonNull(planHash, "planHash");
    return new Decision(
        "create:" + planHash,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        artifactType,
        planHash,
        revision,
        null,
        List.of(),
        List.of(CREATE_ACTION));
  }

  /**
   * The API Hub import, offered as its own decision.
   *
   * <p>A real transition backs it — the specification lands in the runtime catalog — so it is a
   * decision rather than prose. The candidate the reader was shown identifies the card.
   */
  static ChatEvent importDecision(String candidateId, String question) {
    Objects.requireNonNull(candidateId, "candidateId");
    return new Decision(
        "import:" + candidateId,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        null,
        null,
        0L,
        null,
        List.of(),
        List.of(IMPORT_ACTION));
  }

  /**
   * A proposed change to an existing chain, offered as its own decision.
   *
   * <p>Bound to the patch it describes, so an answer to a card the conversation has moved past
   * cannot write a change the reader never saw.
   */
  static ChatEvent chainPatchDecision(String patchHash, String question) {
    Objects.requireNonNull(patchHash, "patchHash");
    return new Decision(
        "chain-patch:" + patchHash,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        CHAIN_PATCH_ARTIFACT,
        patchHash,
        0L,
        null,
        List.of(),
        List.of(APPLY_CHAIN_PATCH_ACTION, REQUEST_CHANGES_ACTION));
  }

  /**
   * A pending replacement of a live deployment, offered as its own decision.
   *
   * <p>Bound to the pending operation it describes, so an answer to a card the conversation has
   * moved past cannot replace a deployment the reader never confirmed.
   */
  static ChatEvent redeployDecision(String operationId, String question) {
    Objects.requireNonNull(operationId, "operationId");
    return new Decision(
        "redeploy:" + operationId,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        REDEPLOY_ARTIFACT,
        operationId,
        0L,
        null,
        List.of(),
        List.of(REDEPLOY_ACTION, CANCEL_REDEPLOY_ACTION));
  }

  /**
   * A pending first deploy, offered as its own decision.
   *
   * <p>Bound to the pending operation it describes, so an answer to a card the conversation has
   * moved past cannot deploy a chain the reader never confirmed.
   */
  static ChatEvent deployDecision(String operationId, String question) {
    Objects.requireNonNull(operationId, "operationId");
    return new Decision(
        "deploy:" + operationId,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        DEPLOY_ARTIFACT,
        operationId,
        0L,
        null,
        List.of(),
        List.of(DEPLOY_ACTION, CANCEL_DEPLOY_ACTION));
  }

  /**
   * A pending removal of a live deployment, offered as its own decision.
   *
   * <p>Bound to the pending operation it describes, so an answer to a card the conversation has
   * moved past cannot remove a deployment the reader never confirmed.
   */
  static ChatEvent undeployDecision(String operationId, String question) {
    Objects.requireNonNull(operationId, "operationId");
    return new Decision(
        "undeploy:" + operationId,
        APPROVE_ACTION,
        question == null ? "" : question.strip(),
        UNDEPLOY_ARTIFACT,
        operationId,
        0L,
        null,
        List.of(),
        List.of(UNDEPLOY_ACTION, CANCEL_UNDEPLOY_ACTION));
  }

  static ChatEvent deploymentProcessingDecision(String deploymentId, String question) {
    Objects.requireNonNull(deploymentId, "deploymentId");
    return new Decision(
        "deployment-processing:" + deploymentId,
        "clarify",
        question == null ? "" : question.strip(),
        DEPLOYMENT_FAILURE_ARTIFACT,
        deploymentId,
        0L,
        null,
        List.of(),
        List.of(REFRESH_DEPLOYMENT_ACTION));
  }

  static ChatEvent deploymentFailureDecision(String deploymentId, String question) {
    Objects.requireNonNull(deploymentId, "deploymentId");
    return new Decision(
        "deployment-failure:" + deploymentId,
        "clarify",
        question == null ? "" : question.strip(),
        DEPLOYMENT_FAILURE_ARTIFACT,
        deploymentId,
        0L,
        null,
        List.of(),
        List.of(PROPOSE_DEPLOYMENT_FIX_ACTION, DISMISS_DEPLOYMENT_FAILURE_ACTION));
  }

  /**
   * Session logging level, offered after the reader has committed to deploy or redeploy.
   *
   * <p>Bound to the pending operation, so a leftover card cannot write logging or deploy a later
   * request. The level comes from the typed action, never from free text.
   */
  static ChatEvent sessionLoggingDecision(String operationId, String question) {
    Objects.requireNonNull(operationId, "operationId");
    return new Decision(
        "session-logging:" + operationId,
        "clarify",
        question == null ? "" : question.strip(),
        SESSION_LOGGING_ARTIFACT,
        operationId,
        0L,
        null,
        List.of(),
        SESSION_LOGGING_ACTIONS);
  }

  /**
   * Actions a gate accepts, keyed by the gate a run named rather than by words in its prompt.
   *
   * <p>A prompt is authored in the language of the conversation, so reading it to choose a card
   * works only until the first reply in another language. Returns {@code null} for a gate with no
   * enumerable answers, which is a free-text clarification.
   */
  static List<String> actionsForGate(String gateId) {
    if (gateId == null) {
      return null;
    }
    return switch (gateId) {
      case PipelineGates.IMPORT_SPECIFICATION -> List.of(IMPORT_ACTION);
      case PipelineGates.IDS_PATH_CHOICE -> IDS_PATH_CHOICE_ACTIONS;
      case PipelineGates.MAPPING_GAP -> MAPPING_GAP_ACTIONS;
      case PipelineGates.STAGE_RETRY -> List.of(PipelineGates.RETRY_ACTION);
      case PipelineGates.RECOVERY_RETRY_TECHNICAL,
              PipelineGates.RECOVERY_REGENERATE_EXECUTION ->
          List.of(RETRY_CREATION_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION);
      case PipelineGates.RECOVERY_REVISE_BRIEF ->
          List.of(EDIT_REQUIREMENTS_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION);
      case PipelineGates.RECOVERY_REBUILD_PLAN ->
          List.of(REBUILD_PLAN_ACTION, PipelineGates.STOP_WITH_REPORT_ACTION);
      case PipelineGates.STAGE_REVISE ->
          List.of(PipelineGates.RETRY_ACTION, PipelineGates.REVISE_ACTION);
      case PipelineGates.STAGE_ESCALATED -> List.of(PipelineGates.STOP_WITH_REPORT_ACTION);
      default -> null;
    };
  }

  private static RecoveryPresentation recoveryFor(PendingAction.Clarify clarify) {
    String gate = clarify.gateId();
    String category;
    String title;
    String preservedWork;
    switch (gate) {
      case PipelineGates.RECOVERY_RETRY_TECHNICAL -> {
        category = "temporary-technical-failure";
        title = "Creation paused temporarily";
        preservedWork = "Your approved requirements and plan are saved.";
      }
      case PipelineGates.RECOVERY_REGENERATE_EXECUTION -> {
        category = "regeneratable-execution-failure";
        title = "Creation output needs regeneration";
        preservedWork = "Your approved requirements and plan are saved.";
      }
      case PipelineGates.RECOVERY_REVISE_BRIEF -> {
        category = "requirement-brief-defect";
        title = "Requirements need correction";
        preservedWork = "Your approved product facts stay available.";
      }
      case PipelineGates.RECOVERY_REBUILD_PLAN -> {
        category = "plan-artifact-defect";
        title = "The plan cannot be used";
        preservedWork = "Your approved requirements stay unchanged.";
      }
      default -> {
        return null;
      }
    }
    return new RecoveryPresentation(
        category,
        title,
        clarify.reason(),
        preservedWork,
        clarify.technicalDetails(),
        clarify.retryDelayMs(),
        clarify.runId(),
        clarify.failedStageId());
  }

  /** Actions a clarify gate offers, including owner-choice stage ids from missing evidence. */
  public static List<String> actionsForClarify(PendingAction.Clarify clarify) {
    if (clarify == null) {
      return null;
    }
    if (PipelineGates.OWNER_CHOICE.equals(clarify.gateId())
        || PipelineGates.STAGE_INTERNAL_FAILURE.equals(clarify.gateId())
        || PipelineGates.STAGE_ESCALATED.equals(clarify.gateId())) {
      return clarify.missingEvidence();
    }
    if (PipelineGates.MAPPING_GAP.equals(clarify.gateId())
        && mappingGapSourceIsMissing(clarify.missingEvidence())) {
      return List.of();
    }
    return actionsForGate(clarify.gateId());
  }

  private static boolean mappingGapSourceIsMissing(List<String> missingEvidence) {
    if (missingEvidence == null || missingEvidence.isEmpty()) {
      return false;
    }
    return missingEvidence.stream()
        .filter(Objects::nonNull)
        .anyMatch(line -> line.contains("no ENDPOINT fact") || line.contains("no trigger"));
  }

  static ChatEvent error(String message) {
    return new Error(message);
  }
}
