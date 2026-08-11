package org.qubership.integration.platform.ai.harness;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.llm.agent.HarnessSkillAgent;

/** Runs one generator skill against an existing catalog chain via catalog tools. */
@ApplicationScoped
public class SkillHarnessService {

  private static final Logger LOG = Logger.getLogger(SkillHarnessService.class);
  private static final int SKILL_BODY_MAX_CHARS = 24_000;

  private final CompilerSkillDocumentService documentService;
  private final HarnessSkillAgent harnessSkillAgent;

  @Inject
  public SkillHarnessService(
      CompilerSkillDocumentService documentService, HarnessSkillAgent harnessSkillAgent) {
    this.documentService = documentService;
    this.harnessSkillAgent = harnessSkillAgent;
  }

  public SkillHarnessResponse run(SkillHarnessRequest request) {
    String conversationId = resolveConversationId(request.conversationId());
    try {
      CompilerSkillDocument document = documentService.loadByCapabilityId(request.skillId());
      String userMessage = buildUserMessage(document, request);
      String message = drainAgentResponse(conversationId, userMessage);
      return new SkillHarnessResponse(conversationId, SkillHarnessStatus.COMPLETED, message);
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Skill harness run failed conversationId=%s chainId=%s skillId=%s",
          conversationId,
          request.chainId(),
          request.skillId());
      return new SkillHarnessResponse(
          conversationId, SkillHarnessStatus.FAILED, failureMessage(e));
    }
  }

  private static String resolveConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return UUID.randomUUID().toString();
    }
    return conversationId.trim();
  }

  private static String buildUserMessage(CompilerSkillDocument document, SkillHarnessRequest request) {
    StringBuilder message = new StringBuilder();
    message.append("## Skill harness run\n\n");
    message.append("chainId=").append(request.chainId().trim()).append('\n');
    message.append("skillId=").append(request.skillId().trim()).append('\n');
    message.append("sourcePath=").append(document.sourcePath()).append("\n\n");
    message.append("## Harness prompt\n\n");
    message.append(request.prompt().trim()).append("\n\n");
    message.append("## Skill instructions\n\n");
    message.append(skillBody(document.markdown()));
    message.append("\n\nConfigure the chain using catalog tools only. ");
    message.append("Do not create or modify a ChainPlanGraph.");
    return message.toString();
  }

  private static String skillBody(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "(Skill markdown is empty.)";
    }
    if (markdown.length() <= SKILL_BODY_MAX_CHARS) {
      return markdown;
    }
    return markdown.substring(0, SKILL_BODY_MAX_CHARS)
        + "\n\n...(skill body truncated for harness run)";
  }

  private String drainAgentResponse(String conversationId, String userMessage) {
    List<String> tokens =
        harnessSkillAgent
            .chat(conversationId, userMessage)
            .collect()
            .asList()
            .await()
            .indefinitely();
    if (tokens.isEmpty()) {
      return "Skill harness completed with no agent text.";
    }
    StringBuilder rendered = new StringBuilder();
    for (String token : tokens) {
      rendered.append(token == null ? "" : token);
    }
    return rendered.toString();
  }

  private static String failureMessage(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }
}
