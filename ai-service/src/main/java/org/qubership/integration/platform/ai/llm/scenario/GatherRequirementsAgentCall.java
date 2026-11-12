package org.qubership.integration.platform.ai.llm.scenario;

import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.smallrye.mutiny.Multi;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.llm.agent.GatherRequirementsAgent;

/** Shared gather-agent stream used by product requirement discovery. */
public final class GatherRequirementsAgentCall {

  private GatherRequirementsAgentCall() {}

  public static Multi<ChatEvent> run(
      GatherRequirementsAgent gatherRequirementsAgent, String conversationId, String agentInput,
      dev.langchain4j.invocation.InvocationParameters parameters) {
    return run(gatherRequirementsAgent, conversationId, agentInput, parameters, false);
  }

  /** The server requires a first tool action on automatic repair turns; native checks still decide completion. */
  public static Multi<ChatEvent> run(
      GatherRequirementsAgent gatherRequirementsAgent, String conversationId, String agentInput,
      dev.langchain4j.invocation.InvocationParameters parameters, boolean requireFirstTool) {
    Objects.requireNonNull(gatherRequirementsAgent, "gatherRequirementsAgent");
    String input = agentInput == null ? "" : agentInput;
    Multi<String> stream = requireFirstTool
        ? gatherRequirementsAgent.chat(conversationId, input, parameters,
            ChatRequestParameters.builder().toolChoice(ToolChoice.REQUIRED).build())
        : gatherRequirementsAgent.chat(conversationId, input, parameters);
    return stream.onItem().transform(ChatEvent::token);
  }
}
