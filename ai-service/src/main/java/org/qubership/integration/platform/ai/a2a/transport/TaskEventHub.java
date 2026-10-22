package org.qubership.integration.platform.ai.a2a.transport;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * In-memory per-Task event hub for the active replica.
 *
 * <p>Preserves generation order per Task, fans out to multiple subscribers, and bounds each
 * subscriber buffer. Overflow fails only the slow subscriber. Does not replay history.
 *
 * <p>Durable revisions travel with each event as an immutable envelope. Filtering never recovers
 * revisions through event equality, object identity, or a side map.
 *
 * <p>{@link #openSubscription(String)} registers a bounded slot eagerly before the caller reads a
 * durable snapshot, so transitions published during reconciliation are buffered rather than lost.
 */
@ApplicationScoped
public class TaskEventHub {

  private static final Logger LOG = Logger.getLogger(TaskEventHub.class);
  static final int DEFAULT_BUFFER_SIZE = 32;

  private final int bufferSize;
  private final ConcurrentHashMap<String, TaskChannel> channels = new ConcurrentHashMap<>();
  private final AtomicReference<Runnable> afterRegisterHook = new AtomicReference<>();

  public TaskEventHub() {
    this(DEFAULT_BUFFER_SIZE);
  }

  public TaskEventHub(int bufferSize) {
    if (bufferSize < 1) {
      throw new IllegalArgumentException("bufferSize must be >= 1");
    }
    this.bufferSize = bufferSize;
  }

  /** Test seam: runs once after eager slot registration and before the caller reconciles. */
  void setAfterRegisterHook(Runnable hook) {
    afterRegisterHook.set(hook);
  }

  /** Test seam: subscriber slots still registered on {@code taskId}. */
  int subscriberCountForTest(String taskId) {
    TaskChannel channel = channels.get(taskId);
    return channel == null ? 0 : channel.subscriberCount();
  }

  public Multi<StreamingEventKind> subscribe(String taskId) {
    return openSubscription(taskId).liveAfter(0L);
  }

  /**
   * Registers a bounded subscriber slot immediately and returns a handle whose live Multi drains
   * that slot when downstream demand attaches.
   */
  public SubscriptionHandle openSubscription(String taskId) {
    Objects.requireNonNull(taskId, "taskId");
    for (; ; ) {
      TaskChannel channel = openChannel(taskId);
      SubscriptionHandle handle = channel.tryOpenSubscription();
      if (handle != null) {
        Runnable hook = afterRegisterHook.getAndSet(null);
        if (hook != null) {
          hook.run();
        }
        return handle;
      }
    }
  }

  public void publish(String taskId, StreamingEventKind event) {
    publish(taskId, event, nextEphemeralDurableRevision(taskId));
  }

  /**
   * Publishes {@code event} tagged with the durable Task revision from JDBC persistence. Subscribe
   * filtering compares this revision with the coordinated snapshot revision.
   */
  public void publish(String taskId, StreamingEventKind event, long durableRevision) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(event, "event");
    if (durableRevision < 1L) {
      throw new IllegalArgumentException("durableRevision must be >= 1");
    }
    for (; ; ) {
      TaskChannel channel = openChannel(taskId);
      if (!channel.tryPublish(new RevisedEvent(durableRevision, event))) {
        continue;
      }
      LOG.debugf(
          "Publishing A2A stream event taskId=%s durableRevision=%d kind=%s",
          taskId, durableRevision, event.kind());
      if (isCloseEvent(event)) {
        LOG.debugf(
            "Closing A2A stream taskId=%s durableRevision=%d", taskId, durableRevision);
        channel.complete();
        channels.remove(taskId, channel);
      }
      return;
    }
  }

  private long nextEphemeralDurableRevision(String taskId) {
    // Compatibility path for unit tests that publish without a JDBC revision: advance past the
    // highest known durable revision so live filters still observe the event.
    TaskChannel channel = openChannel(taskId);
    long next = Math.max(channel.highestDurableRevision() + 1L, channel.nextSequence());
    return next;
  }

  public static boolean closesStream(A2aTaskState state) {
    return state == A2aTaskState.INPUT_REQUIRED
        || state == A2aTaskState.COMPLETED
        || state == A2aTaskState.FAILED;
  }

  private TaskChannel openChannel(String taskId) {
    return channels.compute(
        taskId,
        (id, existing) -> {
          if (existing == null || existing.isClosed()) {
            return new TaskChannel(bufferSize);
          }
          return existing;
        });
  }

  private static boolean isCloseEvent(StreamingEventKind event) {
    if (event instanceof TaskStatusUpdateEvent status) {
      return closesStream(mapState(status.status().state()));
    }
    if (event instanceof Task task && task.status() != null && task.status().state() != null) {
      return closesStream(mapState(task.status().state()));
    }
    return false;
  }

  private static A2aTaskState mapState(org.a2aproject.sdk.spec.TaskState state) {
    return switch (state) {
      case TASK_STATE_SUBMITTED -> A2aTaskState.SUBMITTED;
      case TASK_STATE_WORKING -> A2aTaskState.WORKING;
      case TASK_STATE_INPUT_REQUIRED -> A2aTaskState.INPUT_REQUIRED;
      case TASK_STATE_COMPLETED -> A2aTaskState.COMPLETED;
      case TASK_STATE_FAILED -> A2aTaskState.FAILED;
      default -> A2aTaskState.WORKING;
    };
  }

  /** Eager subscription handle with revision-aware live stream. */
  public final class SubscriptionHandle implements AutoCloseable {
    private final TaskChannel channel;
    private final SubscriberSlot slot;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SubscriptionHandle(TaskChannel channel, SubscriberSlot slot) {
      this.channel = channel;
      this.slot = slot;
    }

    /**
     * Live stream of SDK events whose envelope revision is strictly greater than {@code
     * snapshotDurableRevision}. Events at or below the snapshot revision are suppressed.
     */
    public Multi<StreamingEventKind> liveAfter(long snapshotDurableRevision) {
      slot.suppressAtOrBelow(snapshotDurableRevision);
      return Multi.createFrom()
          .publisher(
              subscriber -> {
                subscriber.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long n) {
                        if (n <= 0) {
                          subscriber.onError(new IllegalArgumentException("request must be > 0"));
                          return;
                        }
                        slot.request(n);
                      }

                      @Override
                      public void cancel() {
                        close();
                      }
                    });
                slot.attach(subscriber);
                // Drain any events buffered between eager registration and downstream attach,
                // including a close that already completed the channel.
                slot.drain();
              });
    }

    /** Unfiltered live stream (equivalent to {@code liveAfter(0)}). */
    public Multi<StreamingEventKind> live() {
      return liveAfter(0L);
    }

    public long currentRevision() {
      return channel.highestDurableRevision();
    }

    /** Test seam: envelopes retained in the bounded buffer (not a side map of delivered events). */
    int retainedEnvelopeCountForTest() {
      return slot.retainedCount();
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      channel.removeSubscriber(slot);
      slot.cancel();
    }
  }

  /** Immutable durable-revision + event pair. Remains internal to the hub. */
  static final class RevisedEvent {
    final long revision;
    final StreamingEventKind event;

    RevisedEvent(long revision, StreamingEventKind event) {
      this.revision = revision;
      this.event = event;
    }
  }

  private final class TaskChannel {
    private final int bufferSize;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong highestDurableRevision = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<SubscriberSlot> subscribers = new CopyOnWriteArrayList<>();

    TaskChannel(int bufferSize) {
      this.bufferSize = bufferSize;
    }

    boolean isClosed() {
      return closed.get();
    }

    long nextSequence() {
      return sequence.incrementAndGet();
    }

    long highestDurableRevision() {
      return highestDurableRevision.get();
    }

    SubscriptionHandle tryOpenSubscription() {
      if (closed.get()) {
        return null;
      }
      SubscriberSlot slot = new SubscriberSlot(bufferSize);
      slot.bindRemoval(() -> subscribers.remove(slot));
      subscribers.add(slot);
      if (closed.get()) {
        subscribers.remove(slot);
        return null;
      }
      return new SubscriptionHandle(this, slot);
    }

    /**
     * @deprecated Prefer {@link #tryOpenSubscription()}; retained for older call sites that only
     *     need a Multi.
     */
    @Deprecated
    Multi<StreamingEventKind> trySubscribe() {
      SubscriptionHandle handle = tryOpenSubscription();
      return handle == null ? null : handle.live();
    }

    boolean tryPublish(RevisedEvent revised) {
      if (closed.get()) {
        return false;
      }
      highestDurableRevision.accumulateAndGet(revised.revision, Math::max);
      for (SubscriberSlot slot : List.copyOf(subscribers)) {
        slot.offer(revised);
      }
      return true;
    }

    void removeSubscriber(SubscriberSlot slot) {
      subscribers.remove(slot);
    }

    int subscriberCount() {
      return subscribers.size();
    }

    void complete() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      for (SubscriberSlot slot : List.copyOf(subscribers)) {
        slot.complete();
      }
    }
  }

  private static final class SubscriberSlot {
    private final int capacity;
    private final ArrayBlockingQueue<RevisedEvent> buffer;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong demand = new AtomicLong();
    private final AtomicLong suppressAtOrBelow = new AtomicLong(-1L);
    private volatile Flow.Subscriber<? super StreamingEventKind> subscriber;
    private volatile Runnable removal = () -> {};

    SubscriberSlot(int bufferSize) {
      this.capacity = bufferSize;
      this.buffer = new ArrayBlockingQueue<>(bufferSize);
    }

    void suppressAtOrBelow(long snapshotDurableRevision) {
      suppressAtOrBelow.set(snapshotDurableRevision);
      buffer.removeIf(envelope -> envelope.revision <= snapshotDurableRevision);
    }

    void attach(Flow.Subscriber<? super StreamingEventKind> subscriber) {
      this.subscriber = subscriber;
      drain();
    }

    void request(long n) {
      if (n == Long.MAX_VALUE) {
        demand.set(Long.MAX_VALUE);
      } else {
        demand.addAndGet(n);
      }
      drain();
    }

    void offer(RevisedEvent revised) {
      if (cancelled.get() || failed.get()) {
        return;
      }
      long suppress = suppressAtOrBelow.get();
      if (suppress >= 0L && revised.revision <= suppress) {
        return;
      }
      if (!buffer.offer(revised)) {
        failOverflow();
        return;
      }
      drain();
    }

    int retainedCount() {
      return buffer.size();
    }

    void complete() {
      completed.set(true);
      drain();
    }

    void cancel() {
      cancelled.set(true);
      close();
    }

    /** Binds the channel removal that every close path runs exactly once. */
    void bindRemoval(Runnable removal) {
      this.removal = removal;
    }

    /**
     * The single close path: drop the buffer, detach the subscriber, and leave the channel.
     * Overflow, cancellation, and terminal completion all end here, so a finished subscriber is
     * never visited by a later publication.
     */
    private void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      buffer.clear();
      subscriber = null;
      removal.run();
    }

    private void failOverflow() {
      if (!failed.compareAndSet(false, true)) {
        return;
      }
      Flow.Subscriber<? super StreamingEventKind> current = subscriber;
      if (current != null) {
        current.onError(
            new IllegalStateException("A2A subscriber buffer overflow (bound=" + capacity + ")"));
      }
      close();
    }

    void drain() {
      Flow.Subscriber<? super StreamingEventKind> current = subscriber;
      if (current == null || failed.get() || cancelled.get()) {
        return;
      }
      long suppress = suppressAtOrBelow.get();
      while (demand.get() > 0) {
        RevisedEvent next = buffer.poll();
        if (next == null) {
          break;
        }
        if (suppress >= 0L && next.revision <= suppress) {
          continue;
        }
        if (demand.get() != Long.MAX_VALUE) {
          demand.decrementAndGet();
        }
        current.onNext(next.event);
      }
      if (completed.get() && buffer.isEmpty() && !failed.get() && !cancelled.get()) {
        current.onComplete();
        close();
      }
    }
  }
}
