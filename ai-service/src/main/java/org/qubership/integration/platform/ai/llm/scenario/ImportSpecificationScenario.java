package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecAttachment;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.SpecificationImportReplyAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.importspec.SpecificationImportPipeline;

@ApplicationScoped
@ForScenario(ScenarioType.IMPORT_SPECIFICATION)
public class ImportSpecificationScenario implements ScenarioHandler {
  private final SpecificationImportPipeline pipeline;
  private final ConversationService conversations;
  private final UploadedSpecsApprovalHandler approvals;
  private final ProductPipelineArtifactStore artifacts;
  private final SpecificationImportReplyAgent replies;

  @Inject
  public ImportSpecificationScenario(SpecificationImportPipeline pipeline, ConversationService conversations,
      UploadedSpecsApprovalHandler approvals, ProductPipelineArtifactStore artifacts, SpecificationImportReplyAgent replies) {
    this.pipeline = pipeline;
    this.conversations = conversations;
    this.approvals = approvals;
    this.artifacts = artifacts;
    this.replies = replies;
  }

  @Override
  public Multi<ChatEvent> handle(ChatRequest request, String conversationId, ScenarioType scenarioType) {
    if (request.getDecision() != null) return approve(conversationId, request.getDecision());
    String hash = approvals.attachmentHash(conversationId);
    if (hash == null) {
      return Multi.createFrom().item(ChatEvent.token(replies.reply(request.getEffectiveUserText(),
          "No API specification is attached. Ask the user to attach an OpenAPI or AsyncAPI file.")));
    }
    pipeline.prepareStandalone(conversationId, hash, request.getEffectiveUserText());
    return Multi.createFrom().item(approvals.createDecision(conversationId));
  }

  public Optional<ChatEvent.Decision> openDecision(String conversationId) {
    return pipeline.standaloneRequest(conversationId)
        .filter(request -> !request.completed())
        .filter(request -> approvals.attachmentHash(conversationId) != null)
        .map(request -> approvals.createDecision(conversationId));
  }

  public Multi<ChatEvent> approve(String conversationId, ChatDecisionCommand command) {
    var pending = openDecision(conversationId);
    if (pending.isEmpty()) return Multi.createFrom().empty();
    var decision = pending.get();
    if (!decision.artifactType().equals(command.getArtifactType())
        || !decision.artifactHash().equals(command.getArtifactHash())
        || command.getRevision() != decision.revision()
        || (!ChatEvent.isImportAction(command.getAction()) && !ChatEvent.APPROVE_ACTION.equals(command.getAction()))) {
      return Multi.createFrom().item(decision);
    }
    var request = pipeline.standaloneRequest(conversationId).orElseThrow();
    List<String> keys = conversations.getAllowedAttachmentKeys(conversationId);
    var types = approvals.resolveSpecSystemTypes(command.getAction(), command.getSpecSystemTypes(), keys);
    approvals.appendApprovalRecord(conversationId + "-import-specification-" + decision.artifactHash(),
        conversationId, decision, "user", command.getAction(), command.getSpecSystemTypes(), artifacts);
    var attachments = keys.stream().map(key -> new UploadedSpecAttachment(key, key.substring(key.lastIndexOf('/') + 1))).toList();
    return pipeline.execute(conversationId, attachments, types, "INTERNAL")
        .map(result -> {
          pipeline.completeStandalone(conversationId, decision.artifactHash());
          String evidence = "Import run: " + conversationId + "\nResult: "
              + (result.succeeded() ? "SUCCEEDED" : "FAILED") + "\n"
              + result.items().stream().map(item -> item.imported() == null
                  ? item.s3Key() + " — FAILED: " + item.error()
                  : item.s3Key() + " — " + (item.imported().reused() ? "REUSED" : "IMPORTED")
                      + ": systemId=" + item.imported().systemId() + ", specificationId=" + item.imported().specificationId())
                  .collect(java.util.stream.Collectors.joining("\n"));
          String reply;
          try {
            reply = replies.reply(request.userText(), evidence);
          } catch (RuntimeException error) {
            reply = "Specification import " + (result.succeeded() ? "completed." : "failed.");
          }
          return (ChatEvent) ChatEvent.token(reply + "\n\n```text\n" + evidence + "\n```");
        }).toMulti();
  }
}
