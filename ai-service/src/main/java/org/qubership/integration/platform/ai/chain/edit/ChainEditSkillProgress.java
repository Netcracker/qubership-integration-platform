package org.qubership.integration.platform.ai.chain.edit;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;

/**
 * Maps compiler skill progress onto the same chat {@code kind=skill} steps CREATE uses, and nests
 * tool steps under the skill that is running.
 */
public final class ChainEditSkillProgress {

  public static final String INTENT_SKILL_ID = "chain-edit-intent";

  private ChainEditSkillProgress() {}

  public static BiConsumer<String, String> toChat(Consumer<ChatEvent> emit) {
    Consumer<ChatEvent> sink = emit == null ? event -> {} : emit;
    return (skillId, status) -> {
      if ("running".equals(status)) {
        SkillActivitySupport.bindParents(skillId);
      } else {
        SkillActivitySupport.clearParents();
      }
      sink.accept(ChatEvent.skillStep(skillId, status));
    };
  }

  static BiConsumer<String, String> orNoop(BiConsumer<String, String> skillProgress) {
    return skillProgress == null ? (skillId, status) -> {} : skillProgress;
  }

  static <T> T call(BiConsumer<String, String> skillProgress, String skillId, Supplier<T> action) {
    BiConsumer<String, String> progress = orNoop(skillProgress);
    progress.accept(skillId, "running");
    try {
      T result = action.get();
      progress.accept(skillId, "completed");
      return result;
    } catch (RuntimeException e) {
      progress.accept(skillId, "error");
      throw e;
    }
  }
}
