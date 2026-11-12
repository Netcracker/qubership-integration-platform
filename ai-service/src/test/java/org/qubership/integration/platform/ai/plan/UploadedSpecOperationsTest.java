package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.storage.S3Service;

class UploadedSpecOperationsTest {
  @Test
  void savedBindingSurvivesReloadAndPreservesTheAuthoredOperationText() {
    var conversations = new ConversationService();
    conversations.archive().beginInput("chat", "Call POST /orders in the uploaded Orders API.", null);
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/orders.yaml"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes("uploads/orders.yaml")).thenReturn(ordersSpec());
    var mapper = new ObjectMapper().findAndRegisterModules();
    var clock = Clock.systemUTC();
    var blobs = new InMemoryArtifactBlobStore();
    var store = new RequirementDraftStore(new CompilationArtifacts(blobs, mapper, clock),
        new CompilationSessions(blobs, mapper, clock));
    var operations = new UploadedSpecOperations(conversations, s3);
    var input = orderCall("Create an order");

    var resolved = operations.resolveOpenApi("chat", input);
    store.put("chat", RequirementCaptureProjection.toDraft(input, null, List.of(), "1", "sha", Map.of(), resolved));
    var restarted = new RequirementDraftStore(new CompilationArtifacts(blobs, mapper, clock),
        new CompilationSessions(blobs, mapper, clock));
    var restored = restarted.get("chat").orElseThrow();
    var unavailableStorage = mock(S3Service.class);

    assertEquals(Map.of("orders", new UploadedSpecOperations.Binding("uploads/orders.yaml", "POST", "/orders")),
        restored.uploadedSpecBindings());
    assertEquals(input, restored.authoredDraft());
    assertEquals("POST /orders", restored.flow().interaction("orders").orElseThrow().operation());
    assertEquals(Set.of("orders"),
        new UploadedSpecOperations(conversations, unavailableStorage).pendingInteractionIds("chat", restored));
    verifyNoInteractions(unavailableStorage);
  }

  @Test
  void doesNotChooseBetweenAttachmentsDeclaringTheSameApiAndRoute() {
    var conversations = new ConversationService();
    conversations.archive().beginInput("chat", "Call POST /orders in the uploaded Orders API.", null);
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/orders-v1.yaml", "uploads/orders-v2.yaml"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes(anyString())).thenReturn(ordersSpec());

    var resolved = new UploadedSpecOperations(conversations, s3).resolveOpenApi("chat", orderCall("POST /orders"));

    assertEquals(Map.of(), resolved);
  }

  @Test
  void doesNotTreatAPathPrefixAsAuthorEvidenceForAnotherOperation() {
    var conversations = new ConversationService();
    conversations.archive().beginInput("chat", "Call POST /orders/preview in the uploaded Orders API.", null);
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/orders.yaml"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes("uploads/orders.yaml")).thenReturn(ordersSpec());

    var resolved = new UploadedSpecOperations(conversations, s3).resolveOpenApi("chat", orderCall("Create an order"));

    assertEquals(Map.of(), resolved);
  }

  @Test
  void resolvesAnExactRouteAgainstTheAttachmentOnTheAuthorTurnWithoutUsingTheCapturedName() {
    var conversations = new ConversationService();
    var metadata = new ObjectMapper().createObjectNode();
    metadata.putArray("attachmentObjectKeys").add("uploads/orders.yaml");
    conversations.archive().beginInput("chat", "Call POST /orders.", metadata);
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/orders.yaml"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes("uploads/orders.yaml")).thenReturn(ordersSpec());

    var resolved = new UploadedSpecOperations(conversations, s3).resolveOpenApi("chat",
        orderCall("Order Management", "POST /orders"));

    assertEquals(Map.of("orders", new UploadedSpecOperations.Binding("uploads/orders.yaml", "POST", "/orders")),
        resolved);
  }

  @Test
  void anUnusedAttachmentDoesNotOverrideAnExistingCatalogRoute() {
    var conversations = new ConversationService();
    conversations.archive().beginInput("chat", "Use the existing Orders API in the catalog.", null);
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/orders.yaml"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes("uploads/orders.yaml")).thenReturn(ordersSpec());

    var resolved = new UploadedSpecOperations(conversations, s3).resolveOpenApi("chat", orderCall("POST /orders"));

    assertEquals(Map.of(), resolved);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Create an order", "POST /orders (createOrder)"})
  void routesTheSavedAuthorOperationToImport(String capturedOperation) {
    var conversations = new ConversationService();
    conversations.archive().beginInput("chat", "Call POST /orders in the uploaded Orders API.", null);
    conversations.registerAllowedAttachmentKeys("chat", List.of("uploads/orders.yaml"));
    var s3 = mock(S3Service.class);
    when(s3.readObjectBytes("uploads/orders.yaml")).thenReturn(ordersSpec());
    var draft = new RequirementDraft(false, "Draft").withFlow(new RequirementFlow(List.of(
        new RequirementFlow.Interaction("orders", RequirementFlow.Direction.OUTBOUND,
            "Orders API", capturedOperation, "")), List.of()));

    assertEquals(Set.of("orders"), new UploadedSpecOperations(conversations, s3).pendingInteractionIds("chat", draft));
  }

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
  private static RequirementCaptureInput.DraftInput orderCall(String operation) {
    return orderCall("Orders API", operation);
  }

  private static RequirementCaptureInput.DraftInput orderCall(String participant, String operation) {
    var interaction = new RequirementCaptureInput.InteractionInput(
        "orders", RequirementFlow.Direction.OUTBOUND, participant, operation, null, null, null);
    return new RequirementCaptureInput.DraftInput(new RequirementCaptureInput.FlowInput(List.of(interaction), List.of()),
        List.of(), List.of(), List.of(), new RequirementCaptureInput.DraftSettings(null, null));
  }

  private static byte[] ordersSpec() {
    return """
        openapi: 3.0.3
        info:
          title: Orders API
        paths:
          /orders:
            post:
              operationId: createOrder
        """.getBytes(StandardCharsets.UTF_8);
  }

}
