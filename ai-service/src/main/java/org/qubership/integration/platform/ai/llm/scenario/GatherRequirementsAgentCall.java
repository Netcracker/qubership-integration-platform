package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;

/** Shared gather-agent stream used by product requirement discovery. */
public final class GatherRequirementsAgentCall {

  private GatherRequirementsAgentCall() {}

  public static Multi<ChatEvent> run(
      GatherRequirementsAgent gatherRequirementsAgent, String conversationId, String agentInput) {
    Objects.requireNonNull(gatherRequirementsAgent, "gatherRequirementsAgent");
    return gatherRequirementsAgent
        .chat(conversationId, agentInput == null ? "" : agentInput)
        .onItem()
        .transform(ChatEvent::token);
  }
}
