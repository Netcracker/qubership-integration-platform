package org.qubership.integration.platform.ai.a2a.transport;

import io.smallrye.mutiny.Multi;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.server.util.async.EventConsumerExecutorProducer.EventConsumerExecutor;
import org.a2aproject.sdk.server.util.async.Internal;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.qubership.integration.platform.ai.a2a.A2aFeatureGate;
import org.qubership.integration.platform.ai.a2a.access.CallerContext;
import org.qubership.integration.platform.ai.a2a.access.CallerContextProvider;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessDeniedException;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessPolicy;
import org.qubership.integration.platform.ai.a2a.access.TaskIdentity;
import org.qubership.integration.platform.ai.a2a.access.TaskOperation;
import org.qubership.integration.platform.ai.a2a.persistence.A2aCallerMessageReceipt;
import org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.a2a.transport.A2aInboundMessageParser.InboundCommand;

/**
 * Replaces {@link DefaultRequestHandler} for durable Get Task, cancel rejection without state
 * change, and lost-initial-response recovery via caller-scoped Message receipts.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class CreateChainCancelRejectingRequestHandler extends DefaultRequestHandler {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(CreateChainCancelRejectingRequestHandler.class);

  private final A2aTaskSnapshotPersister snapshotPersister;
  private final CallerContextProvider callerContextProvider;
  private final TaskAccessPolicy accessPolicy;
  private final TaskEventHub eventHub;
  private final A2aFeatureGate featureGate;
  private final A2aMessageReceiptRepository receiptRepository;
  private final TaskStore taskStore;
  private final java.util.concurrent.atomic.AtomicReference<Runnable> afterRegisterHook =
      new java.util.concurrent.atomic.AtomicReference<>();

  @Inject
  public CreateChainCancelRejectingRequestHandler(
      AgentExecutor agentExecutor,
      TaskStore taskStore,
      QueueManager queueManager,
      PushNotificationConfigStore pushConfigStore,
      MainEventBusProcessor mainEventBusProcessor,
      @Internal Executor executor,
      @EventConsumerExecutor Executor eventConsumerExecutor,
      A2aTaskSnapshotPersister snapshotPersister,
      CallerContextProvider callerContextProvider,
      TaskAccessPolicy accessPolicy,
      TaskEventHub eventHub,
      A2aFeatureGate featureGate,
      A2aMessageReceiptRepository receiptRepository) {
    super(
        agentExecutor,
        taskStore,
        queueManager,
        pushConfigStore,
        mainEventBusProcessor,
        executor,
        eventConsumerExecutor);
    this.snapshotPersister = snapshotPersister;
    this.callerContextProvider = callerContextProvider;
    this.accessPolicy = accessPolicy;
    this.eventHub = eventHub;
    this.featureGate = featureGate;
    this.receiptRepository = receiptRepository;
    this.taskStore = taskStore;
  }

  /** Test seam: runs once after eager slot registration and before the durable snapshot read. */
  void setAfterRegisterHook(Runnable hook) {
    afterRegisterHook.set(hook);
  }

  /**
   * @deprecated Prefer {@link #setAfterRegisterHook(Runnable)}; the second uncoordinated reconcile
   *     read was removed in prompt 10.
   */
  @Deprecated
  void setAfterReconcileReadHook(Runnable hook) {
    afterRegisterHook.set(hook);
  }

  @Override
  public EventKind onMessageSend(MessageSendParams params, ServerCallContext context)
      throws A2AError {
    featureGate.requireEnabled();
    BoundRequest bound = bindClientCorrelation(params);
    params = bound.params();
    boolean dispatched = false;
    try {
      Optional<Task> recovered = recoverLostInitialResponse(params, bound.holder());
      if (recovered.isPresent()) {
        return recovered.get();
      }
      params = rebindIncompleteInitial(params, bound.holder());
      seedTaskStoreFromDurable(params);
      EventKind result = super.onMessageSend(params, context);
      dispatched = true;
      return result;
    } finally {
      if (bound.requestId() != null && !dispatched) {
        A2aClientCorrelationCarrier.clear(bound.requestId());
      }
    }
  }

  @Override
  public Flow.Publisher<StreamingEventKind> onMessageSendStream(
      MessageSendParams params, ServerCallContext context) throws A2AError {
    featureGate.requireEnabled();
    BoundRequest bound = bindClientCorrelation(params);
    params = bound.params();
    boolean dispatched = false;
    try {
      Optional<Task> recovered = recoverLostInitialResponse(params, bound.holder());
      if (recovered.isPresent()) {
        return Multi.createFrom().item(recovered.get());
      }
      params = rebindIncompleteInitial(params, bound.holder());
      seedTaskStoreFromDurable(params);
      Flow.Publisher<StreamingEventKind> result = super.onMessageSendStream(params, context);
      dispatched = true;
      return result;
    } finally {
      if (bound.requestId() != null && !dispatched) {
        A2aClientCorrelationCarrier.clear(bound.requestId());
      }
    }
  }

  @Override
  public Task onGetTask(TaskQueryParams params, ServerCallContext context) throws A2AError {
    featureGate.requireEnabled();
    try {
      accessPolicy.check(
          callerContextProvider.current(),
          TaskOperation.READ,
          new TaskIdentity(params.id(), null));
    } catch (TaskAccessDeniedException denied) {
      throw A2aProtocolErrorMapper.fromAccessDenied(denied);
    }

    Task task =
        snapshotPersister
            .loadSdkTask(params.id())
            .orElseThrow(A2aProtocolErrorMapper::taskNotFound);
    return limitTaskHistory(task, params.historyLength());
  }

  @Override
  public Task onCancelTask(CancelTaskParams params, ServerCallContext context) throws A2AError {
    featureGate.requireEnabled();
    try {
      accessPolicy.check(
          callerContextProvider.current(),
          TaskOperation.CANCEL,
          new TaskIdentity(params.id(), null));
    } catch (TaskAccessDeniedException denied) {
      throw A2aProtocolErrorMapper.fromAccessDenied(denied);
    }
    // Prove the Task exists (durable snapshot) without mutating it, then reject cancel.
    onGetTask(new TaskQueryParams(params.id(), null), context);
    throw A2aProtocolErrorMapper.taskNotCancelable();
  }

  @Override
  public void validateRequestedTask(String requestedTaskId) throws A2AError {
    if (requestedTaskId == null) {
      return;
    }
    if (snapshotPersister.loadSdkTask(requestedTaskId).isEmpty()) {
      throw A2aProtocolErrorMapper.taskNotFound();
    }
  }

  @Override
  public Flow.Publisher<StreamingEventKind> onSubscribeToTask(
      TaskIdParams params, ServerCallContext context) throws A2AError {
    featureGate.requireEnabled();
    try {
      accessPolicy.check(
          callerContextProvider.current(),
          TaskOperation.SUBSCRIBE,
          new TaskIdentity(params.id(), null));
    } catch (TaskAccessDeniedException denied) {
      throw A2aProtocolErrorMapper.fromAccessDenied(denied);
    }

    // Register the bounded slot before the single coordinated durable read. Do not retain a
    // preliminary existence or terminal-state read.
    TaskEventHub.SubscriptionHandle handle = eventHub.openSubscription(params.id());
    Runnable raceHook = afterRegisterHook.getAndSet(null);
    if (raceHook != null) {
      raceHook.run();
    }
    A2aTaskSnapshotPersister.DurableSnapshot durable;
    try {
      durable =
          snapshotPersister
              .loadDurable(params.id())
              .orElseThrow(A2aProtocolErrorMapper::taskNotFound);
    } catch (RuntimeException | Error ex) {
      handle.close();
      throw ex;
    }
    Task reconciled = durable.task();
    long snapshotRevision = durable.revision();

    if (reconciled.status() != null
        && reconciled.status().state() != null
        && reconciled.status().state().isFinal()) {
      handle.close();
      throw new UnsupportedOperationError(
          null,
          "Cannot subscribe to task %s - task is in terminal state: %s"
              .formatted(reconciled.id(), reconciled.status().state()),
          null);
    }

    A2aTaskState reconciledState = mapSdkState(reconciled);
    if (TaskEventHub.closesStream(reconciledState) || isTerminal(reconciled)) {
      handle.close();
      return Multi.createFrom().item(reconciled);
    }
    Multi<StreamingEventKind> live = handle.liveAfter(snapshotRevision);
    return Multi.createBy()
        .concatenating()
        .streams(Multi.createFrom().item(reconciled), live);
  }

  /**
   * Binds a per-request correlation holder and stamps the server-owned request id onto params
   * metadata so the executor can look it up after asynchronous dispatch.
   */
  private BoundRequest bindClientCorrelation(MessageSendParams params) {
    Message message = params == null ? null : params.message();
    if (message == null || message.messageId() == null || message.messageId().isBlank()) {
      return new BoundRequest(params, null, new A2aClientCorrelationCarrier.Holder(null, null));
    }
    // Logged here and nowhere later: this is the last point at which the caller's own contextId is
    // visible. RequestContext.Builder mints one when the caller omits it and rewrites the Message
    // with it, so every reader downstream sees a value either way and cannot tell them apart.
    LOG.infof(
        "A2A inbound message/send messageId=%s contextId=%s",
        message.messageId(),
        message.contextId() == null || message.contextId().isBlank()
            ? "absent"
            : message.contextId());
    A2aClientCorrelationCarrier.Binding binding =
        A2aClientCorrelationCarrier.bind(message.taskId(), message.contextId());
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (params.metadata() != null) {
      metadata.putAll(params.metadata());
    }
    // Always overwrite: clients must not select or forge the server-owned carrier key.
    metadata.put(A2aClientCorrelationCarrier.METADATA_KEY, binding.requestId());
    MessageSendParams stamped =
        MessageSendParams.builder()
            .message(params.message())
            .configuration(params.configuration())
            .metadata(metadata)
            .tenant(params.tenant())
            .build();
    return new BoundRequest(stamped, binding.requestId(), binding.holder());
  }

  private record BoundRequest(
      MessageSendParams params, String requestId, A2aClientCorrelationCarrier.Holder holder) {}

  private static boolean isTerminal(Task task) {
    return task.status() != null
        && task.status().state() != null
        && task.status().state().isFinal();
  }

  private static A2aTaskState mapSdkState(Task task) {
    if (task.status() == null || task.status().state() == null) {
      return A2aTaskState.WORKING;
    }
    return switch (task.status().state()) {
      case TASK_STATE_SUBMITTED -> A2aTaskState.SUBMITTED;
      case TASK_STATE_WORKING -> A2aTaskState.WORKING;
      case TASK_STATE_INPUT_REQUIRED -> A2aTaskState.INPUT_REQUIRED;
      case TASK_STATE_COMPLETED -> A2aTaskState.COMPLETED;
      case TASK_STATE_FAILED -> A2aTaskState.FAILED;
      default -> A2aTaskState.WORKING;
    };
  }

  /**
   * When the client retries an initial Message without {@code taskId} after a lost response, return
   * the durable Task bound to the caller-scoped {@code messageId}. A different command fingerprint
   * is an idempotency conflict.
   */
  private Optional<Task> recoverLostInitialResponse(
      MessageSendParams params, A2aClientCorrelationCarrier.Holder clientIds) throws A2AError {
    Message message = params.message();
    if (message == null || message.taskId() != null || message.messageId() == null) {
      return Optional.empty();
    }
    Optional<A2aCallerMessageReceipt> receipt = findMatchingInitialReceipt(message, clientIds);
    if (receipt.isEmpty()) {
      return Optional.empty();
    }
    if (receipt.get().incomplete()) {
      // Incomplete receipts must re-enter the executor rather than return a stale snapshot.
      return Optional.empty();
    }
    return snapshotPersister.loadSdkTask(receipt.get().taskId());
  }

  /**
   * Lost-initial retries omit {@code taskId}, so the SDK would mint a new id and event queue.
   * Rebind incomplete receipts to the durable Task before dispatch so resume stays on one Task.
   */
  private MessageSendParams rebindIncompleteInitial(
      MessageSendParams params, A2aClientCorrelationCarrier.Holder clientIds) throws A2AError {
    Message message = params.message();
    if (message == null || message.taskId() != null || message.messageId() == null) {
      return params;
    }
    Optional<A2aCallerMessageReceipt> receipt = findMatchingInitialReceipt(message, clientIds);
    if (receipt.isEmpty() || !receipt.get().incomplete()) {
      return params;
    }
    Message rebound = Message.builder(message).taskId(receipt.get().taskId()).build();
    return MessageSendParams.builder()
        .message(rebound)
        .configuration(params.configuration())
        .metadata(params.metadata())
        .tenant(params.tenant())
        .build();
  }

  private Optional<A2aCallerMessageReceipt> findMatchingInitialReceipt(
      Message message, A2aClientCorrelationCarrier.Holder clientIds) throws A2AError {
    CallerContext caller = callerContextProvider.current();
    Optional<A2aCallerMessageReceipt> receipt =
        receiptRepository.findCallerReceipt(
            caller.tenantId(), caller.subjectId(), message.messageId());
    if (receipt.isEmpty()) {
      return Optional.empty();
    }
    InboundCommand command = A2aInboundMessageParser.parse(message);
    String fingerprint =
        A2aCommandFingerprint.compute(
            message, command, clientIds.taskId(), clientIds.contextId());
    if (!fingerprint.equals(receipt.get().commandFingerprint())) {
      throw A2aProtocolErrorMapper.idempotencyConflict(
          message.messageId(), receipt.get().taskId());
    }
    return receipt;
  }

  /**
   * Reconciles the SDK in-memory {@link TaskStore} with the durable JDBC snapshot before the SDK
   * decides whether a Message may continue the Task.
   *
   * <p>PostgreSQL owns A2A Tasks; the SDK store is a derived cache. It must never win over the
   * authoritative snapshot. A dispatch that throws leaves the cached Task {@code FAILED} while the
   * durable Task is still non-terminal and recoverable, and seeding only when the cache is empty
   * would keep that stale terminal state forever, rejecting every retry of the same Message.
   *
   * <p>Overwriting cannot lose a newer state: each status change is persisted before it is
   * published, so the durable snapshot is never behind the cache.
   */
  private void seedTaskStoreFromDurable(MessageSendParams params) {
    Message message = params.message();
    if (message == null || message.taskId() == null) {
      return;
    }
    snapshotPersister
        .loadSdkTask(message.taskId())
        .ifPresent(task -> taskStore.save(task, false));
  }

  /**
   * Mirrors SDK {@code DefaultRequestHandler.limitTaskHistory}: keep the most recent N messages when
   * {@code historyLength} is set and smaller than the stored history.
   */
  private static Task limitTaskHistory(Task task, Integer historyLength) {
    if (task.history() == null || historyLength == null || historyLength >= task.history().size()) {
      return task;
    }
    List<Message> limitedHistory =
        task.history().subList(task.history().size() - historyLength, task.history().size());
    return Task.builder(task).history(limitedHistory).build();
  }
}
