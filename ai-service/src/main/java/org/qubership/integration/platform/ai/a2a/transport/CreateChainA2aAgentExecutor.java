package org.qubership.integration.platform.ai.a2a.transport;

import io.smallrye.mutiny.Multi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.QueueClosedEvent;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.qubership.integration.platform.ai.a2a.A2aFeatureDisabledException;
import org.qubership.integration.platform.ai.a2a.A2aFeatureGate;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.CallerContextProvider;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessDeniedException;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.access.TaskIdentity;
import org.qubership.integration.platform.ai.a2a.access.TaskOperation;
import org.qubership.integration.platform.ai.a2a.artifacts.CreateChainPublicArtifact;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageClaimResult;
import org.qubership.integration.platform.ai.a2a.persistence.A2aDispatchAcquisition;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskCreate;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.A2aInboundMessageParser.InboundCommand;
import org.qubership.integration.platform.ai.a2a.transport.A2aTaskSnapshotPersister.PersistResult;
import org.qubership.integration.platform.ai.a2a.transport.CreateChainA2aStateMapper.ProjectedTask;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainArtifactCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * A2A {@link AgentExecutor} for create-chain@2. Calls only {@link CreateChainApplicationFacade}.
 *
 * <p>Processes facade events one at a time: project → persist → publish. Never drains a facade
 * {@link Multi} before the first progress update reaches the transport.
 */
public final class CreateChainA2aAgentExecutor implements AgentExecutor {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(CreateChainA2aAgentExecutor.class);

  private final CreateChainApplicationFacade facade;
  private final A2aTaskSnapshotPersister persister;
  private final A2aMessageReceiptRepository receiptRepository;
  private final CallerContextProvider callerContextProvider;
  private final TaskAccessPolicy accessPolicy;
  private final A2aFeatureGate featureGate;
  private final A2aDispatchCrashGate crashGate;
  private final DispatchLeaseHeartbeat leaseHeartbeat;
  private final AtomicInteger facadeInvocations = new AtomicInteger();
  private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, ActiveExecution>
      activeExecutions = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Facade events are requested one at a time, so the handoff queue holds at most the in-flight
   * event plus the completion sentinel. The capacity is headroom, not a tuning knob.
   */
  private static final int FACADE_EVENT_QUEUE_CAPACITY = 64;

  public CreateChainA2aAgentExecutor(
      CreateChainApplicationFacade facade,
      A2aTaskSnapshotPersister persister,
      A2aMessageReceiptRepository receiptRepository,
      CallerContextProvider callerContextProvider,
      TaskAccessPolicy accessPolicy) {
    this(facade, persister, receiptRepository, callerContextProvider, accessPolicy, null, null, null);
  }

  public CreateChainA2aAgentExecutor(
      CreateChainApplicationFacade facade,
      A2aTaskSnapshotPersister persister,
      A2aMessageReceiptRepository receiptRepository,
      CallerContextProvider callerContextProvider,
      TaskAccessPolicy accessPolicy,
      A2aFeatureGate featureGate) {
    this(
        facade,
        persister,
        receiptRepository,
        callerContextProvider,
        accessPolicy,
        featureGate,
        null,
        null);
  }

  public CreateChainA2aAgentExecutor(
      CreateChainApplicationFacade facade,
      A2aTaskSnapshotPersister persister,
      A2aMessageReceiptRepository receiptRepository,
      CallerContextProvider callerContextProvider,
      TaskAccessPolicy accessPolicy,
      A2aFeatureGate featureGate,
      A2aDispatchCrashGate crashGate) {
    this(
        facade,
        persister,
        receiptRepository,
        callerContextProvider,
        accessPolicy,
        featureGate,
        crashGate,
        null);
  }

  public CreateChainA2aAgentExecutor(
      CreateChainApplicationFacade facade,
      A2aTaskSnapshotPersister persister,
      A2aMessageReceiptRepository receiptRepository,
      CallerContextProvider callerContextProvider,
      TaskAccessPolicy accessPolicy,
      A2aFeatureGate featureGate,
      A2aDispatchCrashGate crashGate,
      DispatchLeaseHeartbeat leaseHeartbeat) {
    this.facade = Objects.requireNonNull(facade, "facade");
    this.persister = Objects.requireNonNull(persister, "persister");
    this.receiptRepository = Objects.requireNonNull(receiptRepository, "receiptRepository");
    this.callerContextProvider = Objects.requireNonNull(callerContextProvider, "callerContextProvider");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.featureGate = featureGate;
    this.crashGate = crashGate;
    this.leaseHeartbeat = leaseHeartbeat;
  }

  /** Test seam: counts facade command invocations after idempotent receipt acceptance. */
  public int facadeInvocationCount() {
    return facadeInvocations.get();
  }

  /**
   * Logs the shape of an inbound Message so a rejected client request can be diagnosed without a
   * debugger.
   *
   * <p>Records part kinds and whether a structured part declares an action — the two things that
   * decide whether the command parses. Never records part text, structured payload values, or
   * artifact bodies, per the launch observability rules.
   */
  private static void logInboundShape(Message message, String taskId, boolean isNew) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    List<String> parts = new ArrayList<>();
    for (var part : message.parts()) {
      if (part instanceof org.a2aproject.sdk.spec.TextPart textPart) {
        parts.add("text[" + (textPart.text() == null ? 0 : textPart.text().length()) + "]");
      } else if (part instanceof org.a2aproject.sdk.spec.DataPart dataPart) {
        parts.add("data{" + describeDataKeys(dataPart.data()) + "}");
      } else {
        parts.add(part.getClass().getSimpleName());
      }
    }
    LOG.infof(
        "A2A inbound messageId=%s taskId=%s isNew=%s clientTaskId=%s parts=%s",
        message.messageId(),
        taskId,
        isNew,
        message.taskId() == null || message.taskId().isBlank() ? "none" : message.taskId(),
        parts);
  }

  /** Names the keys of a structured part. Keys are protocol field names, never user content. */
  private static String describeDataKeys(Object data) {
    if (!(data instanceof Map<?, ?> map)) {
      return "not-an-object";
    }
    List<String> keys = new ArrayList<>();
    for (Object key : map.keySet()) {
      keys.add(String.valueOf(key));
    }
    return String.join(",", keys);
  }

  /** Logs a typed protocol rejection with its stage, so clients can be diagnosed from logs. */
  private static void logRejection(String stage, Message message, String taskId, A2AError error) {
    LOG.warnf(
        "A2A rejected at %s messageId=%s taskId=%s error=%s detail=%s",
        stage,
        message.messageId(),
        taskId,
        error.getClass().getSimpleName(),
        error.getMessage());
  }

  @Override
  public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(emitter, "emitter");
    requireFeatureEnabled();

    CallerContext caller = callerContextProvider.current();
    String taskId = context.getTaskId();
    String contextId = context.getContextId();
    Message message = context.getMessage();
    if (message == null) {
      throw A2aProtocolErrorMapper.malformedStructuredData("Message is required");
    }

    boolean isNew = context.getTask() == null;
    TaskOperation operation = isNew ? TaskOperation.CREATE : resolveContinueOperation(message);
    try {
      accessPolicy.check(caller, operation, new TaskIdentity(taskId, contextId));
    } catch (TaskAccessDeniedException denied) {
      throw A2aProtocolErrorMapper.fromAccessDenied(denied);
    }

    logInboundShape(message, taskId, isNew);
    InboundCommand command;
    try {
      command = A2aInboundMessageParser.parse(message);
      if (isNew) {
        requireLegalInitialCommand(command);
      }
    } catch (A2AError rejected) {
      logRejection("parse", message, taskId, rejected);
      throw rejected;
    }

    Optional<CreateChainExecutionSnapshot> priorSnapshot =
        isNew ? Optional.empty() : facade.snapshot(taskId);
    // The receipt, the fingerprint, and the resume evidence are all keyed by command kind, so a
    // text approval has to become an approve command here, before the claim, rather than while
    // dispatching.
    if (!activateExactApproval(context)) {
      command = resolveTextApproval(command, priorSnapshot);
    }

    A2aClientCorrelationCarrier.Holder clientIds =
        A2aClientCorrelationCarrier.lookup(requestCorrelationId(context));
    String requestCorrelationId = requestCorrelationId(context);
    String fingerprint =
        A2aCommandFingerprint.compute(
            message, command, clientIds.taskId(), clientIds.contextId());
    String commandId =
        A2aCommandId.derive(caller.tenantId(), caller.subjectId(), message.messageId());
    String commandKind = A2aInboundMessageParser.commandKind(command, isNew);
    Long preconditionRevision =
        priorSnapshot.map(CreateChainExecutionSnapshot::revision).orElse(null);
    List<Message> history = buildHistory(context.getTask(), message);

    try {
      A2aCallerMessageClaimResult claim;
      if (isNew) {
        A2aTaskCreate workingCreate = initialWorkingCreate(taskId, contextId, caller, history);
        claim =
            receiptRepository.claimInitialWithWorkingTask(
                caller.tenantId(),
                caller.subjectId(),
                message.messageId(),
                fingerprint,
                commandKind,
                workingCreate);
      } else {
        claim =
            receiptRepository.claimContinuation(
                caller.tenantId(),
                caller.subjectId(),
                message.messageId(),
                fingerprint,
                commandKind,
                taskId,
                preconditionRevision);
      }

      if (claim instanceof A2aCallerMessageClaimResult.FingerprintConflict conflict) {
        throw A2aProtocolErrorMapper.idempotencyConflict(message.messageId(), conflict.taskId());
      }
      if (claim instanceof A2aCallerMessageClaimResult.TaskBindingConflict binding) {
        throw A2aProtocolErrorMapper.idempotencyConflict(message.messageId(), binding.boundTaskId());
      }
      if (claim instanceof A2aCallerMessageClaimResult.AlreadyBound bound) {
        emitDurableSnapshot(bound.taskId(), contextId, emitter, isNew);
        return;
      }
      // An incomplete receipt redispatches the same idempotent facade command. Durable command
      // evidence in the run document decides which internal steps still need to run, so the
      // transport never infers application from Task status, revision, or pending action.
      if (claim instanceof A2aCallerMessageClaimResult.Incomplete incomplete) {
        taskId = incomplete.taskId();
        isNew =
            facade.snapshot(taskId).isEmpty() && command instanceof InboundCommand.ClarifyText;
      }

      checkCrash(A2aDispatchCrashGate.Point.AFTER_CLAIM);

      A2aDispatchAcquisition acquisition =
          receiptRepository.acquireDispatch(
              caller.tenantId(), caller.subjectId(), message.messageId());
      if (acquisition.result() != A2aDispatchAcquisition.Result.ACQUIRED) {
        emitDurableSnapshot(taskId, contextId, emitter, isNew);
        return;
      }

      boolean completed = false;
      java.util.concurrent.atomic.AtomicBoolean ownershipLost =
          new java.util.concurrent.atomic.AtomicBoolean();
      java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription>
          facadeSubscription = new java.util.concurrent.atomic.AtomicReference<>();
      ActiveExecution activeExecution = new ActiveExecution();
      activeExecutions.put(acquisition.ownerToken(), activeExecution);
      AutoCloseable heartbeat =
          startHeartbeat(
              caller,
              message.messageId(),
              acquisition.ownerToken(),
              ownershipLost,
              facadeSubscription);
      try {
        checkCrash(A2aDispatchCrashGate.Point.AFTER_DISPATCHING);
        renewLease(caller, message.messageId(), acquisition.ownerToken());
        ensureOwnership(ownershipLost);

        ProjectedTask projected =
            dispatchIncremental(
                taskId,
                contextId,
                caller,
                isNew,
                command,
                history,
                emitter,
                message.messageId(),
                acquisition.ownerToken(),
                ownershipLost,
                facadeSubscription,
                commandId);

        ensureOwnership(ownershipLost);
        long revision =
            projected.snapshot() == null ? 1L : Math.max(1L, projected.snapshot().revision());
        receiptRepository.completeDispatch(
            caller.tenantId(),
            caller.subjectId(),
            message.messageId(),
            acquisition.ownerToken(),
            revision,
            revision);
        completed = true;
        checkCrash(A2aDispatchCrashGate.Point.AFTER_COMPLETED);
      } catch (Exception ex) {
        if (!completed) {
          receiptRepository.releaseDispatch(
              caller.tenantId(),
              caller.subjectId(),
              message.messageId(),
              acquisition.ownerToken());
        }
        if (ex instanceof A2AError a2aError) {
          throw a2aError;
        }
        if (ex instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new IllegalStateException(ex);
      } finally {
        activeExecutions.remove(acquisition.ownerToken());
        closeQuietly(heartbeat);
      }
    } finally {
      A2aClientCorrelationCarrier.clear(requestCorrelationId);
    }
  }

  @Override
  public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
    requireFeatureEnabled();
    CallerContext caller = callerContextProvider.current();
    String taskId = context.getTaskId();
    try {
      accessPolicy.check(
          caller, TaskOperation.CANCEL, new TaskIdentity(taskId, context.getContextId()));
    } catch (TaskAccessDeniedException denied) {
      throw A2aProtocolErrorMapper.fromAccessDenied(denied);
    }
    throw new TaskNotCancelableError(
        "Task cancellation is not supported for create-chain in this launch horizon");
  }

  private void requireFeatureEnabled() {
    if (featureGate != null && !featureGate.enabled()) {
      throw new A2aFeatureDisabledException(A2aFeatureGate.DISABLED_MESSAGE);
    }
  }

  private void checkCrash(A2aDispatchCrashGate.Point point) {
    if (crashGate != null) {
      crashGate.check(point);
    }
  }

  private void renewLease(CallerContext caller, String messageId, java.util.UUID ownerToken) {
    if (!receiptRepository.renewDispatch(
        caller.tenantId(), caller.subjectId(), messageId, ownerToken)) {
      throw new DispatchOwnershipLostException(
          "Dispatch lease renewal failed for messageId=" + messageId);
    }
  }

  private AutoCloseable startHeartbeat(
      CallerContext caller,
      String messageId,
      java.util.UUID ownerToken,
      java.util.concurrent.atomic.AtomicBoolean ownershipLost,
      java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription>
          facadeSubscription) {
    if (leaseHeartbeat == null) {
      return () -> {};
    }
    return leaseHeartbeat.start(
        ownerToken,
        () ->
            receiptRepository.renewDispatch(
                caller.tenantId(), caller.subjectId(), messageId, ownerToken),
        () -> {
          ownershipLost.set(true);
          java.util.concurrent.Flow.Subscription subscription = facadeSubscription.get();
          if (subscription != null) {
            subscription.cancel();
          }
          ActiveExecution active = activeExecutions.get(ownerToken);
          if (active != null) {
            active.cancelRequested.set(true);
            java.util.concurrent.BlockingQueue<Object> queue = active.eventQueue;
            Object sentinel = active.completeSentinel;
            if (queue != null && sentinel != null) {
              queue.offer(sentinel);
            }
          }
        });
  }

  private static void ensureOwnership(java.util.concurrent.atomic.AtomicBoolean ownershipLost) {
    if (ownershipLost != null && ownershipLost.get()) {
      throw new DispatchOwnershipLostException("Dispatch ownership lost during active execution");
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // Best-effort heartbeat cleanup.
    }
  }

  private static String requestCorrelationId(RequestContext context) {
    java.util.Map<String, Object> metadata = context.getMetadata();
    if (metadata == null) {
      return null;
    }
    Object value = metadata.get(A2aClientCorrelationCarrier.METADATA_KEY);
    return value == null ? null : String.valueOf(value);
  }

  /** Raised when this execution no longer owns the dispatch lease. */
  static final class DispatchOwnershipLostException extends RuntimeException {
    DispatchOwnershipLostException(String message) {
      super(message);
    }
  }

  private static final class ActiveExecution {
    private final java.util.concurrent.atomic.AtomicBoolean cancelRequested =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile java.util.concurrent.BlockingQueue<Object> eventQueue;
    private volatile Object completeSentinel;
  }

  /** Test seam: active cancellable facade executions still owned by this executor. */
  int activeExecutionCountForTest() {
    return activeExecutions.size();
  }

  private static void requireLegalInitialCommand(InboundCommand command) throws A2AError {
    if (command instanceof InboundCommand.ClarifyText) {
      return;
    }
    throw A2aProtocolErrorMapper.malformedStructuredData(
        "New Tasks require text requirements input");
  }

  private TaskOperation resolveContinueOperation(Message message) throws A2AError {
    InboundCommand command = A2aInboundMessageParser.parse(message);
    if (command instanceof InboundCommand.Approve) {
      return TaskOperation.APPROVE;
    }
    return TaskOperation.CONTINUE;
  }

  private static A2aTaskCreate initialWorkingCreate(
      String taskId, String contextId, CallerContext caller, List<Message> history) {
    Task sdkTask =
        Task.builder()
            .id(taskId)
            .contextId(contextId)
            .status(
                new TaskStatus(
                    A2aTaskState.WORKING.toSdk(),
                    Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .messageId(java.util.UUID.randomUUID().toString())
                        .parts(List.of(new TextPart("Working")))
                        .build(),
                    null))
            .history(history)
            .build();
    String snapshotJson;
    String historyJson;
    try {
      snapshotJson = JsonUtil.toJson(sdkTask);
      historyJson = JsonUtil.toJson(history);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to serialize initial WORKING Task snapshot", e);
    }
    return new A2aTaskCreate(
        taskId,
        contextId,
        taskId,
        A2aTaskState.WORKING,
        caller.tenantId(),
        caller.subjectId(),
        snapshotJson,
        historyJson,
        "[]",
        null);
  }

  private ProjectedTask dispatchIncremental(
      String taskId,
      String contextId,
      CallerContext caller,
      boolean isNew,
      InboundCommand command,
      List<Message> history,
      AgentEmitter emitter,
      String messageId,
      java.util.UUID ownerToken)
      throws A2AError {
    return dispatchIncremental(
        taskId,
        contextId,
        caller,
        isNew,
        command,
        history,
        emitter,
        messageId,
        ownerToken,
        null,
        new java.util.concurrent.atomic.AtomicReference<>(),
        null);
  }

  private ProjectedTask dispatchIncremental(
      String taskId,
      String contextId,
      CallerContext caller,
      boolean isNew,
      InboundCommand command,
      List<Message> history,
      AgentEmitter emitter,
      String messageId,
      java.util.UUID ownerToken,
      java.util.concurrent.atomic.AtomicBoolean ownershipLost,
      java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription>
          facadeSubscription,
      String commandId)
      throws A2AError {
    facadeInvocations.incrementAndGet();

    if (command instanceof InboundCommand.Approve approve) {
      CreateChainExecutionSnapshot before =
          facade
              .snapshot(taskId)
              .orElseThrow(A2aProtocolErrorMapper::taskNotFound);
      ApproveCreateChainArtifactCommand approveCommand =
          new ApproveCreateChainArtifactCommand(
              taskId, approve.artifactType(), approve.artifactHash(), approve.revision(), commandId);
      // A retry whose approve step already applied durably has moved the Task past the approval
      // wait, so the advertised pending action no longer describes it. Ask the facade for durable
      // evidence first and only gate a genuinely new approval.
      if (!facade.approvalAlreadyApplied(approveCommand)) {
        CreateChainPendingAction pending = before.pendingAction();
        if (pending == null) {
          throw A2aProtocolErrorMapper.malformedStructuredData(
              "Task is not advertising an approve pending action");
        }
        if (!(pending instanceof CreateChainPendingAction.Approve)) {
          ensureOwnership(ownershipLost);
          return refuseAndKeepWaiting(
              taskId,
              contextId,
              caller,
              before,
              history,
              emitter,
              "This Task is not waiting for approval.");
        }
      }
      Optional<ApproveCreateChainOutcome> rejected = facade.validateApprove(approveCommand);
      if (rejected.isPresent()) {
        ApproveCreateChainOutcome outcome = rejected.get();
        if (outcome instanceof ApproveCreateChainOutcome.ImplementationBlocked
            || outcome instanceof ApproveCreateChainOutcome.NonRecoverableFailure
            || outcome instanceof ApproveCreateChainOutcome.DuplicateApproval) {
          ensureOwnership(ownershipLost);
          ProjectedTask projected = CreateChainA2aStateMapper.projectOutcome(taskId, outcome, before);
          PersistResult persisted =
              persister.persistAndBuildSdkTask(taskId, contextId, caller, projected, history);
          emitProjected(emitter, persisted, projected, false, true);
          return projected;
        }
        throw A2aProtocolErrorMapper.fromApproveOutcome(outcome);
      }
      renewLease(caller, messageId, ownerToken);
      ensureOwnership(ownershipLost);
      return consumeFacadeEvents(
          taskId,
          contextId,
          caller,
          false,
          history,
          emitter,
          facade.streamApprove(
              new ApproveCreateChainArtifactCommand(
                  taskId,
                  approve.artifactType(),
                  approve.artifactHash(),
                  approve.revision(),
                  commandId)),
          messageId,
          ownerToken,
          ownershipLost,
          facadeSubscription);
    }

    if (isNew) {
      if (!(command instanceof InboundCommand.ClarifyText clarify)) {
        throw A2aProtocolErrorMapper.malformedStructuredData(
            "New Tasks require text requirements input");
      }
      renewLease(caller, messageId, ownerToken);
      ensureOwnership(ownershipLost);
      return consumeFacadeEvents(
          taskId,
          contextId,
          caller,
          true,
          history,
          emitter,
          facade.start(new StartCreateChainCommand(taskId, clarify.text(), commandId)),
          messageId,
          ownerToken,
          ownershipLost,
          facadeSubscription);
    }

    if (command instanceof InboundCommand.ClarifyText clarify) {
      CreateChainExecutionSnapshot before =
          facade
              .snapshot(taskId)
              .orElseThrow(A2aProtocolErrorMapper::taskNotFound);
      // A retry whose clarification already applied durably has moved the run past the wait it
      // satisfied. Validating it against the current pending action would reject it, so ask the
      // facade for durable evidence first and only gate a genuinely new command.
      if (!facade.inputAlreadyApplied(taskId, commandId, clarify.text())
          && before.pendingAction() instanceof CreateChainPendingAction.Approve approve) {
        ensureOwnership(ownershipLost);
        return refuseAndKeepWaiting(
            taskId,
            contextId,
            caller,
            before,
            history,
            emitter,
            "Free-form text does not approve an artifact. "
                + CreateChainA2aStateMapper.approvalInstruction(approve));
      }
      renewLease(caller, messageId, ownerToken);
      ensureOwnership(ownershipLost);
      return consumeFacadeEvents(
          taskId,
          contextId,
          caller,
          false,
          history,
          emitter,
          facade.continueWithInput(
              new ContinueCreateChainCommand(taskId, clarify.text(), commandId)),
          messageId,
          ownerToken,
          ownershipLost,
          facadeSubscription);
    }

    throw A2aProtocolErrorMapper.malformedStructuredData("Unsupported inbound command");
  }

  private ProjectedTask consumeFacadeEvents(
      String taskId,
      String contextId,
      CallerContext caller,
      boolean isNew,
      List<Message> history,
      AgentEmitter emitter,
      Multi<CreateChainEvent> events,
      String messageId,
      java.util.UUID ownerToken)
      throws A2AError {
    return consumeFacadeEvents(
        taskId,
        contextId,
        caller,
        isNew,
        history,
        emitter,
        events,
        messageId,
        ownerToken,
        null,
        new java.util.concurrent.atomic.AtomicReference<>());
  }

  private ProjectedTask consumeFacadeEvents(
      String taskId,
      String contextId,
      CallerContext caller,
      boolean isNew,
      List<Message> history,
      AgentEmitter emitter,
      Multi<CreateChainEvent> events,
      String messageId,
      java.util.UUID ownerToken,
      java.util.concurrent.atomic.AtomicBoolean ownershipLost,
      java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription>
          facadeSubscription)
      throws A2AError {
    List<CreateChainEvent> seen = new ArrayList<>();
    AtomicReference<ProjectedTask> last = new AtomicReference<>();
    AtomicInteger emitCount = new AtomicInteger();
    AtomicInteger persistCount = new AtomicInteger();
    AtomicReference<Boolean> workingEmitted = new AtomicReference<>(false);
    AtomicReference<A2aTaskState> lastEmittedState = new AtomicReference<>();
    java.util.concurrent.BlockingQueue<Object> queue =
        new java.util.concurrent.ArrayBlockingQueue<>(FACADE_EVENT_QUEUE_CAPACITY);
    Object completeSentinel = new Object();
    AtomicReference<Throwable> upstreamFailure = new AtomicReference<>();
    ActiveExecution active = ownerToken == null ? null : activeExecutions.get(ownerToken);
    if (active != null) {
      active.eventQueue = queue;
      active.completeSentinel = completeSentinel;
    }

    if (isNew) {
      Optional<Task> working = persister.loadSdkTask(taskId);
      if (working.isPresent()) {
        emitter.addTask(working.get());
        Message statusMessage =
            working.get().status() == null ? null : working.get().status().message();
        emitter.startWork(statusMessage);
        workingEmitted.set(true);
        lastEmittedState.set(A2aTaskState.WORKING);
      }
    }

    events
        .subscribe()
        .withSubscriber(
            new io.smallrye.mutiny.subscription.MultiSubscriber<CreateChainEvent>() {
              @Override
              public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                if (ownershipLost.get()) {
                  // Ownership was already lost, so never ask this publisher for work.
                  subscription.cancel();
                  queue.offer(completeSentinel);
                  return;
                }
                facadeSubscription.set(subscription);
                subscription.request(1L);
              }

              @Override
              public void onItem(CreateChainEvent item) {
                if (!queue.offer(item)) {
                  // One event is in flight at a time, so a full queue means the contract broke.
                  // Fail closed instead of dropping a projected Task revision.
                  upstreamFailure.set(
                      new IllegalStateException("facade event queue overflowed"));
                  queue.offer(completeSentinel);
                }
              }

              @Override
              public void onFailure(Throwable failure) {
                upstreamFailure.set(failure);
                queue.offer(completeSentinel);
              }

              @Override
              public void onCompletion() {
                queue.offer(completeSentinel);
              }
            });

    try {
      while (true) {
        ensureOwnership(ownershipLost);
        Object next = queue.take();
        if (next == completeSentinel) {
          break;
        }
        ensureOwnership(ownershipLost);
        CreateChainEvent event = (CreateChainEvent) next;
        renewLease(caller, messageId, ownerToken);
        ensureOwnership(ownershipLost);
        seen.add(event);
        CreateChainExecutionSnapshot snapshot =
            facade.snapshot(taskId).orElseGet(() -> snapshotFromEvents(taskId, seen));
        ProjectedTask projected = CreateChainA2aStateMapper.project(snapshot, seen);
        if (isNew
            && !Boolean.TRUE.equals(workingEmitted.get())
            && projected.state() != A2aTaskState.WORKING) {
          Optional<Task> working = persister.loadSdkTask(taskId);
          if (working.isPresent()) {
            emitter.addTask(working.get());
            Message statusMessage =
                working.get().status() == null ? null : working.get().status().message();
            emitter.startWork(statusMessage);
            workingEmitted.set(true);
            lastEmittedState.set(A2aTaskState.WORKING);
          }
        }
        ensureOwnership(ownershipLost);
        checkCrash(A2aDispatchCrashGate.Point.AFTER_RUNTIME_COMMIT);
        PersistResult persisted =
            persister.persistAndBuildSdkTask(taskId, contextId, caller, projected, history);
        int persists = persistCount.incrementAndGet();
        boolean emitAsNew =
            isNew
                && emitCount.getAndIncrement() == 0
                && !Boolean.TRUE.equals(workingEmitted.get());
        boolean emitStatus =
            lastEmittedState.get() != projected.state()
                && !TaskEventHub.closesStream(lastEmittedState.get());
        emitProjected(emitter, persisted, projected, emitAsNew, emitStatus);
        lastEmittedState.set(projected.state());
        last.set(projected);
        if (persists == 1) {
          checkCrash(A2aDispatchCrashGate.Point.AFTER_FIRST_PERSIST);
        }
        // Ask for the next event only now: persistence and publication for this one are done, so
        // the publisher can never run ahead of durable state.
        java.util.concurrent.Flow.Subscription current = facadeSubscription.get();
        if (current != null) {
          current.request(1L);
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DispatchOwnershipLostException("Dispatch interrupted after ownership loss");
    } catch (RuntimeException ex) {
      Throwable root = ex;
      while (root.getCause() != null && root.getCause() != root) {
        root = root.getCause();
      }
      if (root instanceof A2AError a2a) {
        throw a2a;
      }
      throw ex;
    } finally {
      java.util.concurrent.Flow.Subscription subscription = facadeSubscription.getAndSet(null);
      if (subscription != null) {
        subscription.cancel();
      }
    }

    Throwable upstream = upstreamFailure.get();
    if (upstream != null) {
      Throwable root = upstream;
      while (root.getCause() != null && root.getCause() != root) {
        root = root.getCause();
      }
      if (root instanceof A2AError a2a) {
        throw a2a;
      }
      if (upstream instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(upstream);
    }

    if (ownershipLost != null && ownershipLost.get()) {
      throw new DispatchOwnershipLostException("Dispatch ownership lost during active execution");
    }

    ProjectedTask projected = last.get();
    if (projected == null) {
      ensureOwnership(ownershipLost);
      CreateChainExecutionSnapshot snapshot =
          facade
              .snapshot(taskId)
              .orElseThrow(() -> new IllegalStateException("missing snapshot after empty Multi"));
      projected = CreateChainA2aStateMapper.project(snapshot, seen);
      PersistResult persisted =
          persister.persistAndBuildSdkTask(taskId, contextId, caller, projected, history);
      emitProjected(emitter, persisted, projected, isNew, true);
    }
    return projected;
  }

  private static CreateChainExecutionSnapshot snapshotFromEvents(
      String taskId, List<CreateChainEvent> events) {
    for (int i = events.size() - 1; i >= 0; i--) {
      CreateChainEvent event = events.get(i);
      if (event instanceof CreateChainEvent.Completed completed) {
        return completed.snapshot();
      }
      if (event instanceof CreateChainEvent.Failed failed) {
        return failed.snapshot();
      }
      if (event instanceof CreateChainEvent.Waiting waiting) {
        return new CreateChainExecutionSnapshot(
            taskId,
            "",
            org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus
                .INPUT_REQUIRED,
            0L,
            waiting.pendingAction(),
            "");
      }
    }
    return new CreateChainExecutionSnapshot(
        taskId,
        "",
        org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus
            .WORKING,
        0L,
        null,
        "");
  }

  /**
   * Re-advertises the pending action instead of failing the Task.
   *
   * <p>An open stream carries no error frame, and input the agent cannot act on is not an execution
   * failure. The Task stays interrupted with the reason attached, so the caller can answer again.
   */
  private ProjectedTask refuseAndKeepWaiting(
      String taskId,
      String contextId,
      CallerContext caller,
      CreateChainExecutionSnapshot before,
      List<Message> history,
      AgentEmitter emitter,
      String reason) {
    ProjectedTask projected = CreateChainA2aStateMapper.projectRefusal(taskId, before, reason);
    PersistResult persisted =
        persister.persistAndBuildSdkTask(taskId, contextId, caller, projected, history);
    emitProjected(emitter, persisted, projected, false, true);
    return projected;
  }

  /**
   * Reports whether the caller opted into structured-only approval, activating it when they did.
   */
  private static boolean activateExactApproval(RequestContext context) {
    ServerCallContext callContext = context.getCallContext();
    if (callContext == null
        || !callContext.isExtensionRequested(A2aProtocolConstants.EXACT_APPROVAL_EXTENSION_URI)) {
      return false;
    }
    callContext.activateExtension(A2aProtocolConstants.EXACT_APPROVAL_EXTENSION_URI);
    return true;
  }

  /**
   * Reads the approval token out of a text reply from a client that cannot send data parts.
   *
   * <p>The token is the artifact hash prefix the status message printed, so approval stays bound to
   * the revision the caller was shown. Anything else stays a clarification.
   */
  private static InboundCommand resolveTextApproval(
      InboundCommand command, Optional<CreateChainExecutionSnapshot> priorSnapshot) {
    if (!(command instanceof InboundCommand.ClarifyText clarify)) {
      return command;
    }
    CreateChainPendingAction pending =
        priorSnapshot.map(CreateChainExecutionSnapshot::pendingAction).orElse(null);
    if (!(pending instanceof CreateChainPendingAction.Approve approve)) {
      return command;
    }
    String expected =
        "approve " + CreateChainA2aStateMapper.approvalToken(approve.artifactHash());
    String text = clarify.text() == null ? "" : clarify.text();
    // A2aInboundMessageParser joins every TextPart with "\n", and a client that re-transfers
    // through an intermediary (ADK's RemoteA2aAgent, notably) always prepends narration parts
    // ahead of the caller's actual reply. Matching per line, rather than the whole joined text,
    // still refuses a token merely mentioned inside free-form prose.
    boolean matches =
        text.lines().map(line -> line.trim().replaceAll("\\s+", " "))
            .anyMatch(line -> line.equalsIgnoreCase(expected));
    if (!matches) {
      return command;
    }
    return new InboundCommand.Approve(
        approve.artifactType(), approve.artifactHash(), approve.revision(), null);
  }

  private void emitDurableSnapshot(
      String taskId, String contextId, AgentEmitter emitter, boolean isNew) throws A2AError {
    Optional<Task> task = persister.loadSdkTask(taskId);
    if (task.isEmpty()) {
      throw A2aProtocolErrorMapper.taskNotFound();
    }
    Task sdkTask = task.get();
    A2aTaskState state = mapSdkState(sdkTask);
    Message statusMessage = sdkTask.status() == null ? null : sdkTask.status().message();
    if (isNew) {
      emitter.addTask(sdkTask);
    }
    emitState(emitter, state, statusMessage, false);
    closeStreamIfNeeded(emitter, taskId, state);
  }

  private static A2aTaskState mapSdkState(Task task) {
    return switch (task.status().state()) {
      case TASK_STATE_SUBMITTED -> A2aTaskState.SUBMITTED;
      case TASK_STATE_WORKING -> A2aTaskState.WORKING;
      case TASK_STATE_INPUT_REQUIRED -> A2aTaskState.INPUT_REQUIRED;
      case TASK_STATE_COMPLETED -> A2aTaskState.COMPLETED;
      case TASK_STATE_FAILED -> A2aTaskState.FAILED;
      default -> A2aTaskState.WORKING;
    };
  }

  private void emitProjected(
      AgentEmitter emitter,
      PersistResult persisted,
      ProjectedTask projected,
      boolean isNew,
      boolean emitStatus) {
    Task sdkTask = persisted.task();
    Message statusMessage = sdkTask.status() == null ? null : sdkTask.status().message();
    if (isNew) {
      emitter.addTask(sdkTask);
    } else if (!isNew) {
      emitter.addTask(sdkTask);
    }
    emitArtifacts(emitter, persisted.newlyCommittedArtifacts());
    if (emitStatus) {
      emitState(emitter, projected.state(), statusMessage, false);
      closeStreamIfNeeded(emitter, sdkTask.id(), projected.state());
    }
  }

  private static void emitArtifacts(
      AgentEmitter emitter, List<CreateChainPublicArtifact> newlyCommitted) {
    if (newlyCommitted == null || newlyCommitted.isEmpty()) {
      return;
    }
    for (CreateChainPublicArtifact artifact : newlyCommitted) {
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("type", artifact.type());
      metadata.put("revision", artifact.revision());
      metadata.put("contentHash", artifact.contentHash());
      emitter.addArtifact(
          List.of(new org.a2aproject.sdk.spec.DataPart(new LinkedHashMap<>(artifact.payload()))),
          artifact.id(),
          artifact.type(),
          metadata);
    }
  }

  private static void emitState(
      AgentEmitter emitter, A2aTaskState state, Message statusMessage, boolean isNew) {
    if (isNew) {
      emitter.submit();
    }
    switch (state) {
      case SUBMITTED -> emitter.submit(statusMessage);
      case WORKING -> emitter.startWork(statusMessage);
      case INPUT_REQUIRED -> emitter.requiresInput(statusMessage, true);
      case COMPLETED -> emitter.complete(statusMessage);
      case FAILED -> emitter.fail(statusMessage);
    }
  }

  private static void closeStreamIfNeeded(AgentEmitter emitter, String taskId, A2aTaskState state) {
    if (TaskEventHub.closesStream(state)) {
      emitter.emitEvent(new QueueClosedEvent(taskId));
    }
  }

  private static List<Message> buildHistory(Task existing, Message inbound) {
    List<Message> history = new ArrayList<>();
    if (existing != null && existing.history() != null) {
      history.addAll(existing.history());
    }
    history.add(inbound);
    return history;
  }
}
