package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Context;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Binds the active product-pipeline capability so capture tools return candidate payloads instead of
 * advancing the legacy conversation stores alone.
 */
public final class ProductCapabilityCaptureContext {

  static final String CONTEXT_KEY = "product-capability-capture-context";

  private static final ThreadLocal<Binding> THREAD_BINDING = new ThreadLocal<>();
  private static final ThreadLocal<Context> THREAD_CONTEXT = new ThreadLocal<>();

  /**
   * Bindings by conversation id. LangChain4j runs a blocking {@code @Tool} on a pooled worker
   * thread that did not call {@code bind*}, and that thread may still carry an earlier stage's
   * binding, so a thread-local alone is both lossy and unsafe at the tool boundary.
   */
  private static final Map<String, Binding> BY_CONVERSATION = new ConcurrentHashMap<>();

  private ProductCapabilityCaptureContext() {}

  public enum Mode {
    DISCOVERY,
    ANALYSIS,
    DESIGN
  }

  public record Binding(
      Mode mode,
      String runId,
      String conversationId,
      RequirementDraft approvedDraft,
      RequirementBrief approvedBrief,
      AtomicReference<RequirementDraft> draftCandidate,
      AtomicReference<RequirementBrief> briefCandidate,
      AtomicReference<ChainSemanticRevision> semanticCandidate,
      Consumer<Object> onCandidate) {}

  public static Context bindDiscovery(
      String runId, String conversationId, Consumer<Object> onCandidate) {
    Binding binding =
        new Binding(
            Mode.DISCOVERY,
            runId,
            conversationId,
            null,
            null,
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            onCandidate);
    Context context = Context.of(CONTEXT_KEY, binding);
    install(binding, context);
    return context;
  }

  public static Context bindAnalysis(
      String runId,
      String conversationId,
      RequirementDraft approvedDraft,
      Consumer<Object> onCandidate) {
    Binding binding =
        new Binding(
            Mode.ANALYSIS,
            runId,
            conversationId,
            approvedDraft,
            null,
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            onCandidate);
    Context context = Context.of(CONTEXT_KEY, binding);
    install(binding, context);
    return context;
  }

  public static Context bindDesign(
      String runId,
      String conversationId,
      RequirementBrief approvedBrief,
      Consumer<Object> onCandidate) {
    Binding binding =
        new Binding(
            Mode.DESIGN,
            runId,
            conversationId,
            null,
            approvedBrief,
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            onCandidate);
    Context context = Context.of(CONTEXT_KEY, binding);
    install(binding, context);
    return context;
  }

  public static void unbind() {
    Binding binding = THREAD_BINDING.get();
    if (binding != null && binding.conversationId() != null) {
      BY_CONVERSATION.remove(binding.conversationId(), binding);
    }
    THREAD_BINDING.remove();
    THREAD_CONTEXT.remove();
  }

  /**
   * Releases a design binding by id. A capability that binds and releases on different threads
   * cannot rely on {@link #unbind()}, which only sees the thread it runs on.
   */
  public static void unbind(String conversationId) {
    if (conversationId != null && !conversationId.isBlank()) {
      BY_CONVERSATION.remove(conversationId.trim());
    }
    unbind();
  }

  /**
   * Binding for one conversation.
   *
   * <p>A pooled worker thread can still hold a binding from an earlier stage of the same
   * conversation. The conversation registry is therefore authoritative whenever an id is known.
   */
  public static Optional<Binding> binding(String conversationId) {
    String id = conversationId == null ? "" : conversationId.trim();
    if (!id.isEmpty()) {
      return Optional.ofNullable(BY_CONVERSATION.get(id));
    }
    return Optional.ofNullable(THREAD_BINDING.get());
  }

  public static Optional<Binding> designBinding(String conversationId) {
    return binding(conversationId).filter(found -> found.mode() == Mode.DESIGN);
  }

  /** Approved draft for one conversation, immune to a stale binding on this worker thread. */
  public static Optional<RequirementDraft> approvedDraft(String conversationId) {
    return binding(conversationId).map(Binding::approvedDraft).filter(draft -> draft != null);
  }

  public static boolean isBound(String conversationId) {
    return binding(conversationId).isPresent();
  }

  public static boolean isBound() {
    return current().isPresent();
  }

  public static Optional<Binding> current() {
    return Optional.ofNullable(THREAD_BINDING.get());
  }

  public static Optional<RequirementDraft> approvedDraft() {
    return current().map(Binding::approvedDraft).filter(draft -> draft != null);
  }

  public static Optional<RequirementBrief> approvedBrief() {
    return current().map(Binding::approvedBrief).filter(brief -> brief != null);
  }

  public static Optional<RequirementDraft> draftCandidate() {
    return current().map(binding -> binding.draftCandidate().get()).filter(draft -> draft != null);
  }

  public static Optional<RequirementBrief> briefCandidate() {
    return current().map(binding -> binding.briefCandidate().get()).filter(brief -> brief != null);
  }

  public static Optional<ChainSemanticRevision> semanticCandidate() {
    return current()
        .map(binding -> binding.semanticCandidate().get())
        .filter(revision -> revision != null);
  }

  public static void offerDraft(RequirementDraft draft) {
    current().ifPresent(binding -> offerDraft(binding, draft));
  }

  /** Offers against an explicit binding, for a tool that resolved it by conversation id. */
  public static void offerDraft(Binding binding, RequirementDraft draft) {
    if (binding == null || binding.mode() != Mode.DISCOVERY) {
      return;
    }
    binding.draftCandidate().set(draft);
    if (binding.onCandidate() != null) {
      binding.onCandidate().accept(draft);
    }
  }

  public static void offerBrief(RequirementBrief brief) {
    current().ifPresent(binding -> offerBrief(binding, brief));
  }

  /** Offers against an explicit binding, for a tool that resolved it by conversation id. */
  public static void offerBrief(Binding binding, RequirementBrief brief) {
    if (binding == null || binding.mode() != Mode.ANALYSIS) {
      return;
    }
    binding.briefCandidate().set(brief);
    if (binding.onCandidate() != null) {
      binding.onCandidate().accept(brief);
    }
  }

  public static void offerSemantic(ChainSemanticRevision revision) {
    current().ifPresent(binding -> offerSemantic(binding, revision));
  }

  /** Offers against an explicit binding, for a tool that resolved it by conversation id. */
  public static void offerSemantic(Binding binding, ChainSemanticRevision revision) {
    if (binding == null || binding.mode() != Mode.DESIGN) {
      return;
    }
    binding.semanticCandidate().set(revision);
    if (binding.onCandidate() != null) {
      binding.onCandidate().accept(revision);
    }
  }

  public static Context attachedContext() {
    Context context = THREAD_CONTEXT.get();
    return context != null ? context : Context.empty();
  }

  private static void install(Binding binding, Context context) {
    THREAD_BINDING.set(binding);
    THREAD_CONTEXT.set(context);
    if (binding.conversationId() != null && !binding.conversationId().isBlank()) {
      BY_CONVERSATION.put(binding.conversationId(), binding);
    }
  }
}
