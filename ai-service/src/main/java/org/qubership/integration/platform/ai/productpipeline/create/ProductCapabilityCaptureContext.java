package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Context;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Binds the active product-pipeline capability so capture tools return candidate payloads instead of
 * advancing the legacy conversation stores alone.
 */
public final class ProductCapabilityCaptureContext {

  static final String CONTEXT_KEY = "product-capability-capture-context";

  private static final ThreadLocal<Binding> THREAD_BINDING = new ThreadLocal<>();
  private static final ThreadLocal<Context> THREAD_CONTEXT = new ThreadLocal<>();

  private ProductCapabilityCaptureContext() {}

  public enum Mode {
    DISCOVERY,
    ANALYSIS
  }

  public record Binding(
      Mode mode,
      String runId,
      String conversationId,
      RequirementDraft approvedDraft,
      AtomicReference<RequirementDraft> draftCandidate,
      AtomicReference<RequirementBrief> briefCandidate,
      Consumer<Object> onCandidate) {}

  public static Context bindDiscovery(
      String runId, String conversationId, Consumer<Object> onCandidate) {
    Binding binding =
        new Binding(
            Mode.DISCOVERY,
            runId,
            conversationId,
            null,
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
            new AtomicReference<>(),
            new AtomicReference<>(),
            onCandidate);
    Context context = Context.of(CONTEXT_KEY, binding);
    install(binding, context);
    return context;
  }

  public static void unbind() {
    THREAD_BINDING.remove();
    THREAD_CONTEXT.remove();
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

  public static Optional<RequirementDraft> draftCandidate() {
    return current().map(binding -> binding.draftCandidate().get()).filter(draft -> draft != null);
  }

  public static Optional<RequirementBrief> briefCandidate() {
    return current().map(binding -> binding.briefCandidate().get()).filter(brief -> brief != null);
  }

  public static void offerDraft(RequirementDraft draft) {
    current()
        .ifPresent(
            binding -> {
              if (binding.mode() != Mode.DISCOVERY) {
                return;
              }
              binding.draftCandidate().set(draft);
              if (binding.onCandidate() != null) {
                binding.onCandidate().accept(draft);
              }
            });
  }

  public static void offerBrief(RequirementBrief brief) {
    current()
        .ifPresent(
            binding -> {
              if (binding.mode() != Mode.ANALYSIS) {
                return;
              }
              binding.briefCandidate().set(brief);
              if (binding.onCandidate() != null) {
                binding.onCandidate().accept(brief);
              }
            });
  }

  public static Context attachedContext() {
    Context context = THREAD_CONTEXT.get();
    return context != null ? context : Context.empty();
  }

  private static void install(Binding binding, Context context) {
    THREAD_BINDING.set(binding);
    THREAD_CONTEXT.set(context);
  }
}
