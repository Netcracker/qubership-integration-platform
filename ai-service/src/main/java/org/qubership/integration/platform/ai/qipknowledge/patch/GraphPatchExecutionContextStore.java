package org.qubership.integration.platform.ai.qipknowledge.patch;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMdc;

/**
 * Request-scoped graph patch execution context.
 *
 * <p>Keyed by conversation + capability (not {@link ThreadLocal}) because LangChain4j tool
 * callbacks often run on a different worker thread than the planning spine that binds the
 * context. Thread-local binding skipped ownership checks at capture and failed later at harvest.
 */
@ApplicationScoped
public class GraphPatchExecutionContextStore {

  private final ConcurrentHashMap<String, GraphPatchExecutionContext> byKey =
      new ConcurrentHashMap<>();

  public void set(String conversationId, String capabilityId, GraphPatchExecutionContext context) {
    byKey.put(requireKey(conversationId, capabilityId), Objects.requireNonNull(context, "context"));
  }

  /** Prefer {@link #set(String, String, GraphPatchExecutionContext)}; resolves keys from MDC. */
  public void set(GraphPatchExecutionContext context) {
    Objects.requireNonNull(context, "context");
    String conversationId = MDC.get(ChatMdc.CONVERSATION_ID);
    String capabilityId =
        context.skillId() != null && !context.skillId().isBlank()
            ? context.skillId()
            : MDC.get(CompilerSkillMdc.CAPABILITY_ID);
    set(conversationId, capabilityId, context);
  }

  public Optional<GraphPatchExecutionContext> get(String conversationId, String capabilityId) {
    if (conversationId == null
        || conversationId.isBlank()
        || capabilityId == null
        || capabilityId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byKey.get(key(conversationId, capabilityId)));
  }

  public Optional<GraphPatchExecutionContext> current() {
    return get(MDC.get(ChatMdc.CONVERSATION_ID), MDC.get(CompilerSkillMdc.CAPABILITY_ID));
  }

  public void clear(String conversationId, String capabilityId) {
    if (conversationId == null
        || conversationId.isBlank()
        || capabilityId == null
        || capabilityId.isBlank()) {
      return;
    }
    byKey.remove(key(conversationId, capabilityId));
  }

  /** Clears the binding for the current MDC conversation/capability pair. */
  public void clear() {
    String conversationId = MDC.get(ChatMdc.CONVERSATION_ID);
    String capabilityId = MDC.get(CompilerSkillMdc.CAPABILITY_ID);
    clear(conversationId, capabilityId);
  }

  private static String requireKey(String conversationId, String capabilityId) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId is required");
    }
    if (capabilityId == null || capabilityId.isBlank()) {
      throw new IllegalArgumentException("capabilityId is required");
    }
    return key(conversationId, capabilityId);
  }

  private static String key(String conversationId, String capabilityId) {
    return conversationId.trim() + '\u0000' + capabilityId.trim();
  }
}
