package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.storage.S3Service;

class UploadedSpecOperationsTest {
  @ParameterizedTest
  @ValueSource(strings = {
      "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Salesforce WFM\"},\"paths\":{\"/Task\":{\"post\":{\"operationId\":\"createTask\"}}},\"components\":{\"examples\":{\"ignored\":{\"value\":{\"operationId\":\"onTaskResult\"}}}}}",
      "openapi: 3.0.3\ninfo:\n  title: Salesforce WFM\npaths:\n  /Task:\n    post:\n      operationId: createTask\n",
      "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"Salesforce WFM\"},\"channels\":{\"Task\":{\"publish\":{\"operationId\":\"createTask\"}}}}",
      "{\"asyncapi\":\"3.0.0\",\"info\":{\"title\":\"Salesforce WFM\"},\"operations\":{\"createTask\":{\"action\":\"send\"}}}"
  })
  void matchesTheUploadedApiAndOperationAndExcludesAlreadyBoundCalls(String document) {
    var conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/salesforce.yaml", "uploads/mapping.md"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes(anyString())).thenReturn(document.getBytes(StandardCharsets.UTF_8));
    var draft = new RequirementDraft(false, "Draft").withFlow(new RequirementFlow(List.of(
        new RequirementFlow.Interaction("salesforce", RequirementFlow.Direction.OUTBOUND, "Salesforce WFM", "createTask", ""),
        new RequirementFlow.Interaction("other-api", RequirementFlow.Direction.OUTBOUND, "OM", "createTask", ""),
        new RequirementFlow.Interaction("other-operation", RequirementFlow.Direction.OUTBOUND, "Salesforce WFM", "onTaskResult", "")), List.of()));

    assertEquals(Set.of("salesforce"), new UploadedSpecOperations(conversations, s3).pendingInteractionIds("chat", draft));
    var bound = draft.withBoundInteraction("salesforce", new CatalogBindingHint("3", "salesforce", "salesforce", "createTask",
        "sf", "group", "spec", "operation", "http", "POST", "/Task", "1", Instant.EPOCH, "catalog-read"));
    assertEquals(Set.of(), new UploadedSpecOperations(conversations, s3).pendingInteractionIds("chat", bound));
    verify(s3, never()).readObjectBytes("uploads/mapping.md");
  }

  @ParameterizedTest
  @ValueSource(strings = {"createTask", "POST /Task"})
  void matchesLegacyServiceCallFactsByDeclaredOperationOrRoute(String operation) {
    var conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/salesforce.json"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes(anyString())).thenReturn("""
        {"info":{"title":"Salesforce WFM"},"paths":{"/Task":{"post":{"operationId":"createTask"}}}}
        """.getBytes(StandardCharsets.UTF_8));
    var draft = new RequirementDraft(false, "Legacy draft").withFacts(List.of(new RequirementFact(
        "legacy-call", RequirementFactPolarity.POSITIVE, RequirementFactKind.SERVICE_CALL, "", operation,
        "Salesforce WFM", operation, "", "", "", "legacy-call")));

    assertEquals(Set.of("legacy-call"), new UploadedSpecOperations(conversations, s3).pendingInteractionIds("chat", draft));
  }
}
