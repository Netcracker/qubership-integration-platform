package org.qubership.integration.platform.ai.a2a.transport;

import java.util.Objects;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;

/**
 * Picks the skill for an inbound message and delegates to its executor.
 *
 * <p>A2A carries no skill selector on {@code SendMessage}, so it travels as {@code
 * metadata.skillId} and a caller that names one gets exactly that skill. A caller that names
 * nothing gets the conversational skill, because the clients that cannot name one — a peer driving
 * a remote agent through a stock SDK — are exactly the callers who want a plain answer in one
 * round trip. Anything that drives the create-chain pipeline holds a Task across turns and
 * approves artifacts, which is deliberate enough to say so.
 */
public final class QipA2aAgentExecutor implements AgentExecutor {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(QipA2aAgentExecutor.class);

  private final CreateChainA2aAgentExecutor createChain;
  private final QipAssistA2aAgentExecutor assist;

  public QipA2aAgentExecutor(
      CreateChainA2aAgentExecutor createChain, QipAssistA2aAgentExecutor assist) {
    this.createChain = Objects.requireNonNull(createChain, "createChain");
    this.assist = Objects.requireNonNull(assist, "assist");
  }

  @Override
  public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
    select(context).execute(context, emitter);
  }

  @Override
  public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
    select(context).cancel(context, emitter);
  }

  private AgentExecutor select(RequestContext context) {
    String requested = requestedSkillId(context.getMessage());
    if (A2aProtocolConstants.ASSIST_SKILL_ID.equals(requested)) {
      return assist;
    }
    if (A2aProtocolConstants.CREATE_CHAIN_SKILL_ID.equals(requested)) {
      return createChain;
    }
    if (requested != null) {
      throw A2aProtocolErrorMapper.malformedStructuredData("Unknown skillId: " + requested);
    }
    LOG.debugf(
        "A2A skill unset taskId=%s, answering with the conversational skill", context.getTaskId());
    return assist;
  }

  private static String requestedSkillId(Message message) {
    if (message == null || message.metadata() == null) {
      return null;
    }
    Object value = message.metadata().get(A2aProtocolConstants.SKILL_ID_METADATA_KEY);
    if (value == null) {
      return null;
    }
    String skillId = String.valueOf(value).trim();
    return skillId.isEmpty() ? null : skillId;
  }
}
