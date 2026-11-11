package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportOutcome;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.llm.agent.SpecificationImportReplyAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.importspec.SpecificationImportPipeline;

class ImportSpecificationScenarioTest {
  @Test
  void standaloneImportRestoresItsCardAndCompletesWithoutCreatingAChainDraft() {
    var conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/api.json"));
    var approvals = new UploadedSpecsApprovalHandler(conversations, mock(org.qubership.integration.platform.ai.storage.S3Service.class));
    var gateway = mock(CatalogMutationGateway.class);
    when(gateway.importUploadedSpec(eq("chat"), any(), eq("EXTERNAL"))).thenReturn(Uni.createFrom().item(
        new UploadedSpecImportOutcome("uploads/api.json", "system", "group", "spec", true)));
    var blobs = new InMemoryArtifactBlobStore();
    var json = new ObjectMapper().findAndRegisterModules();
    var pipeline = new SpecificationImportPipeline(gateway, blobs, json);
    var artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, json, Clock.systemUTC()));
    var replies = mock(SpecificationImportReplyAgent.class);
    when(replies.reply(any(), any())).thenReturn("\u0421\u043f\u0435\u0446\u0438\u0444\u0438\u043a\u0430\u0446\u0438\u044f \u0443\u0436\u0435 \u0435\u0441\u0442\u044c \u0432 \u043a\u0430\u0442\u0430\u043b\u043e\u0433\u0435.");
    var scenario = new ImportSpecificationScenario(pipeline, conversations, approvals, artifacts, replies);
    var request = new ChatRequest();
    request.setResolvedEffectiveUserText("\u0418\u043c\u043f\u043e\u0440\u0442\u0438\u0440\u0443\u0439 \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u043d\u0443\u044e \u0441\u043f\u0435\u0446\u0438\u0444\u0438\u043a\u0430\u0446\u0438\u044e");
    var events = scenario.handle(request, "chat", ScenarioType.IMPORT_SPECIFICATION).collect().asList().await().indefinitely();
    var decision = (ChatEvent.Decision) events.getFirst();
    verifyNoInteractions(gateway);
    var reloaded = new ImportSpecificationScenario(new SpecificationImportPipeline(gateway, blobs, json),
        conversations, approvals, artifacts, replies);
    assertEquals(decision, reloaded.openDecision("chat").orElseThrow());

    var command = new ChatDecisionCommand();
    command.setAction(ChatEvent.IMPORT_ACTION);
    command.setArtifactType(decision.artifactType());
    command.setArtifactHash("stale");
    command.setRevision(decision.revision());
    assertInstanceOf(ChatEvent.Decision.class, reloaded.approve("chat", command)
        .collect().asList().await().indefinitely().getFirst());
    verifyNoInteractions(gateway);
    command.setArtifactHash(decision.artifactHash());
    command.setSpecSystemTypes(Map.of("uploads/api.json", "EXTERNAL"));
    var imported = reloaded.approve("chat", command).collect().asList().await().indefinitely();

    assertTrue(((ChatEvent.Token) imported.getFirst()).text().contains("REUSED"));
    assertTrue(((ChatEvent.Token) imported.getFirst()).text().contains("specificationId=spec"));
    assertTrue(reloaded.openDecision("chat").isEmpty());
    assertTrue(pipeline.result("chat").orElseThrow().succeeded());
  }
}
