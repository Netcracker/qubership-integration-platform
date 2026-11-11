package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.productpipeline.create.ApproveCandidateTool;

/**
 * Reads a typed reply to an open gate and, when it approves, says so by calling a tool.
 *
 * <p>Replaces a classifier whose verdict word the code had to trust. An approval now costs the
 * model a call naming the artifact type, hash, and revision, which the caller validates against the
 * gate before anything advances. A reply the model does not act on is treated as input for the
 * stage, which is what a change request should be.
 *
 * <p>The reply arrives in the language of the conversation, or relayed by another agent as a
 * sentence. Neither has to match an English token any more.
 *
 * <p>Tool calling requires chat memory, so the caller passes a memory id of its own rather than
 * sharing the one the conversation agents use.
 */
@RegisterAiService(tools = ApproveCandidateTool.class, maxSequentialToolInvocations = 2)
@ApplicationScoped
public interface GateReplyAgent {

  @UserMessage(
      """
A pipeline is waiting for approval of one candidate:

artifactType: {artifactType}
artifactHash: {artifactHash}
revision: {revision}

Below is the reply it received. It may be in any language, and it may come from an agent relaying \
a person's decision rather than from the person.

Call approveCandidate with exactly the three values above when, and only when, the reply accepts \
that candidate as it stands. A reply that accepts and then asks to continue or describes later \
work still accepts the candidate. A reply that asks for the candidate itself to change — a \
requirement added, removed, corrected, or challenged — does not: do not call the tool, and say in \
one sentence what the reader wants changed.

Never invent a hash or a revision, and never approve anything other than the candidate above.

Reply:
---
{reply}
---\
""")
  String interpretReply(
      @MemoryId String gateMemoryId,
      @V("artifactType") String artifactType,
      @V("artifactHash") String artifactHash,
      @V("revision") long revision,
      @V("reply") String reply);
}
