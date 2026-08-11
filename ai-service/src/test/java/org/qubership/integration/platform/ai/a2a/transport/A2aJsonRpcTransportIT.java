package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.a2a.persistence.A2aPersistedTask;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskRepository;
import org.qubership.integration.platform.ai.a2a.protocol.A2aProtocolConstants;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ContinueCreateChainCommand;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionSnapshot;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainExecutionStatus;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.create.facade.StartCreateChainCommand;

/**
 * JSON-RPC contract coverage over {@code POST /rpc} (the path the Agent Card advertises) and the
 * SDK default {@code POST /}.
 *
 * <p>Both dialects are exercised. {@code SendMessage} under {@code A2A-Version: 1.0} is what the
 * Java SDK speaks; {@code message/send} with no version header is what a Python {@code a2a-sdk}
 * client sends, and it must reach the same handler.
 */
@QuarkusTest
class A2aJsonRpcTransportIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @InjectMock CreateChainApplicationFacade facade;

  @InjectMock ScenarioRouter scenarioRouter;

  @Inject A2aTaskRepository taskRepository;

  @Inject ConversationService conversations;

  private final AtomicInteger startCalls = new AtomicInteger();

  @BeforeEach
  void stubCollaborators() {
    startCalls.set(0);
    when(facade.start(any(StartCreateChainCommand.class)))
        .thenAnswer(
            invocation -> {
              startCalls.incrementAndGet();
              return Multi.createFrom()
                  .items(
                      new CreateChainEvent.Progress("Working"),
                      new CreateChainEvent.Waiting(
                          new CreateChainPendingAction.Clarify(
                              "Additional input is required.", List.of())));
            });
    when(facade.continueWithInput(any(ContinueCreateChainCommand.class)))
        .thenAnswer(
            invocation ->
                Multi.createFrom()
                    .items(
                        new CreateChainEvent.Waiting(
                            new CreateChainPendingAction.Clarify(
                                "Still need more detail.", List.of()))));
    when(facade.snapshot(any()))
        .thenAnswer(
            invocation -> {
              String taskId = invocation.getArgument(0);
              return Optional.of(
                  new CreateChainExecutionSnapshot(
                      taskId,
                      "run-" + taskId,
                      CreateChainExecutionStatus.INPUT_REQUIRED,
                      1L,
                      new CreateChainPendingAction.Clarify(
                          "Additional input is required.", List.of()),
                      ""));
            });
    // Tokens arrive as LLM stream fragments carrying their own spacing, the shape
    // RequirementAnalysisCapability produces. Reassembly must be byte-exact: inserting any
    // separator here turns the answer into one word per line.
    when(scenarioRouter.route(any(ChatRequest.class), any()))
        .thenAnswer(
            invocation ->
                Multi.createFrom()
                    .items(
                        ChatEvent.token("Chains"),
                        ChatEvent.token(" live"),
                        ChatEvent.token(" in QIP.")));
  }

  private static final String EXPECTED_ANSWER = "Chains live in QIP.";

  /**
   * The path that matters once a caller sets the field the protocol defines: the conversation is
   * taken from {@code message.contextId} and echoed back unchanged.
   *
   * <p>This also pins the mechanism the resolution depends on. {@code RequestContext.Builder} mints
   * a contextId when the caller omits one and rewrites the Message with it, so the caller's own
   * value survives only in the correlation carrier bound at the request-handler boundary. If that
   * binding stops happening, every caller looks like it sent nothing and this test fails.
   */
  @Test
  void contextIdSentAsAProtocolFieldSelectsTheConversation() throws Exception {
    String sent = UUID.randomUUID().toString();

    JsonNode root =
        postJsonRpc(
            A2aProtocolConstants.JSONRPC_PATH, sendMessageWithContextId("Carry on", sent), "1.0");
    assertFalse(hasError(root), root.toString());

    ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
    verify(scenarioRouter).route(any(ChatRequest.class), conversationId.capture());
    assertEquals(sent, conversationId.getValue(), "the caller's contextId must select the run");
    assertEquals(sent, root.path("result").path("task").path("contextId").asText(), root.toString());
  }

  /**
   * A caller that writes the conversation identifier into the message text instead of setting
   * {@code message.contextId} still lands on the same conversation, and the answer echoes that
   * identifier so the caller converges instead of chasing a new one every turn.
   */
  @Test
  void contextIdQuotedInTextJoinsTheExistingConversation() throws Exception {
    String known = UUID.randomUUID().toString();
    conversations.addMessage(
        known,
        new ConversationMessage(
            ConversationMessage.Role.USER, "earlier turn", java.time.Instant.now()));

    JsonNode root =
        postJsonRpc(
            A2aProtocolConstants.JSONRPC_PATH,
            sendMessage("Continue task with contextId " + known + " and finish the chain"),
            "1.0");
    assertFalse(hasError(root), root.toString());

    ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
    verify(scenarioRouter).route(any(ChatRequest.class), conversationId.capture());
    assertEquals(known, conversationId.getValue(), "the quoted conversation must be resumed");
    assertEquals(known, root.path("result").path("task").path("contextId").asText(), root.toString());
  }

  /**
   * A caller making one non-streaming call has no channel for a live update, so the work the turn
   * did is reported on the answer instead of being dropped.
   */
  @Test
  void answerCarriesTheSkillsTheTurnUsed() throws Exception {
    when(scenarioRouter.route(any(ChatRequest.class), any()))
        .thenAnswer(
            invocation ->
                Multi.createFrom()
                    .items(
                        ChatEvent.skillStep("requirement-analysis", "running"),
                        ChatEvent.token("Done."),
                        ChatEvent.skillStep("design-planning", "running"),
                        ChatEvent.skillStep("requirement-analysis", "finished")));

    JsonNode root =
        postJsonRpc(A2aProtocolConstants.JSONRPC_PATH, sendMessage("Build something"), "1.0");
    assertFalse(hasError(root), root.toString());

    JsonNode metadata = root.path("result").path("task").path("artifacts").path(0).path("metadata");
    // Ordered by first appearance and de-duplicated: the sequence of work, not a tally.
    assertEquals(
        "requirement-analysis,design-planning", metadata.path("skillsUsed").asText(), root.toString());
    // A completed turn says nothing about an active stage.
    assertTrue(metadata.path("activeStage").isMissingNode(), root.toString());
    assertEquals("Done.", firstArtifactText(root.path("result").path("task")), root.toString());
  }

  /** An identifier this service never issued must not silently merge two conversations. */
  @Test
  void unknownQuotedContextIdStartsAFreshConversation() throws Exception {
    String stranger = UUID.randomUUID().toString();

    JsonNode root =
        postJsonRpc(
            A2aProtocolConstants.JSONRPC_PATH,
            sendMessage("Continue task with contextId " + stranger + " please"),
            "1.0");
    assertFalse(hasError(root), root.toString());

    ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
    verify(scenarioRouter).route(any(ChatRequest.class), conversationId.capture());
    assertNotEquals(stranger, conversationId.getValue(), "an unknown id must not be adopted");
  }

  @Test
  void rpcAliasCreatesCreateChainTaskWhenSkillIsPinned() throws Exception {
    String taskId =
        postCreateChainAndExtractTaskId(
            A2aProtocolConstants.JSONRPC_PATH, "Build a payment chain");
    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(taskId, persisted.conversationId());
    assertEquals(A2aTaskState.INPUT_REQUIRED, persisted.state());
    assertTrue(persisted.publicSnapshotJson().contains(taskId));
    assertEquals(1, startCalls.get());
  }

  @Test
  void rootJsonRpcPathAlsoCreatesCreateChainTask() throws Exception {
    postCreateChainAndExtractTaskId("/", "Root path create");
    assertEquals(1, startCalls.get());
  }

  /**
   * The wire method and the absent version header together are the whole DCA outbound profile: the
   * 0.3 dialect is the only one that accepts {@code message/send}, and {@code VersionRouter} picks
   * it when no header is present.
   */
  @Test
  void messageSendWithoutVersionHeaderAnswersWithText() throws Exception {
    JsonNode root = postJsonRpc(A2aProtocolConstants.JSONRPC_PATH, legacySendMessage("Where do chains live?"), null);
    assertFalse(hasError(root), root.toString());

    JsonNode task = locateTask(root);
    assertNotNull(task, "no task in: " + root);
    String text = firstArtifactText(task);
    assertEquals(EXPECTED_ANSWER, text, root.toString());
    // The 0.3 dialect spells states in kebab-case, not as TASK_STATE_* enum names.
    assertEquals("completed", task.path("status").path("state").asText(), root.toString());
    // The raw fallback in a DCA-shaped client reads result.artifacts[0].parts[0].text and never
    // unwraps result.task, so the answer has to sit directly under result.
    assertTrue(root.path("result").has("artifacts"), root.toString());
    assertEquals(0, startCalls.get(), "the conversational skill must not start a CREATE run");
  }

  /**
   * The path a Python {@code a2a-sdk} client actually takes.
   *
   * <p>Its JSON-RPC transport posts {@code SendMessage} under {@code A2A-Version: 1.0} and reads
   * {@code result.task.artifacts[*].parts[*].text}, concatenating text parts only. An answer
   * carried as structured data reaches it as an empty string.
   */
  @Test
  void sdkDialectSendMessageAnswersWithText() throws Exception {
    JsonNode root =
        postJsonRpc(A2aProtocolConstants.JSONRPC_PATH, sendMessage("Where do chains live?"), "1.0");
    assertFalse(hasError(root), root.toString());

    JsonNode task = root.path("result").path("task");
    assertFalse(task.isMissingNode(), "expected result.task in: " + root);
    assertEquals(EXPECTED_ANSWER, firstArtifactText(task), root.toString());
    assertEquals(
        "TASK_STATE_COMPLETED", task.path("status").path("state").asText(), root.toString());
    assertEquals(0, startCalls.get(), "the conversational skill must not start a CREATE run");
  }

  /** A misspelled skill is a caller mistake, not a reason to answer with the wrong skill. */
  @Test
  void unknownSkillIdIsRejected() throws Exception {
    String messageId = UUID.randomUUID().toString();
    String body =
        """
        {
          "jsonrpc": "2.0",
          "id": "%s",
          "method": "SendMessage",
          "params": {
            "message": {
              "messageId": "%s",
              "role": "ROLE_USER",
              "parts": [ { "text": "anything" } ],
              "metadata": { "skillId": "create-chain@1" }
            }
          }
        }
        """
            .formatted(messageId, messageId);

    JsonNode root = postJsonRpc(A2aProtocolConstants.JSONRPC_PATH, body, "1.0");
    assertTrue(hasError(root), root.toString());
    assertEquals(0, startCalls.get());
  }

  private String postCreateChainAndExtractTaskId(String path, String text) throws Exception {
    JsonNode root = postJsonRpc(path, sendMessageWithSkill(text), "1.0");
    assertFalse(hasError(root), root.toString());
    JsonNode task = locateTask(root);
    String taskId = task.path("id").asText(null);
    assertNotNull(taskId, "task id missing in: " + root);
    assertEquals("TASK_STATE_INPUT_REQUIRED", task.path("status").path("state").asText(), root.toString());
    return taskId;
  }

  private static JsonNode postJsonRpc(String path, String body, String version) throws Exception {
    var request =
        given().urlEncodingEnabled(false).contentType(ContentType.JSON).body(body);
    if (version != null) {
      request = request.header("A2A-Version", version);
    }
    String responseBody =
        request.when().post(URI.create(path)).then().statusCode(200).extract().asString();
    JsonNode root = MAPPER.readTree(responseBody);
    assertEquals("2.0", root.path("jsonrpc").asText(), responseBody);
    return root;
  }

  private static boolean hasError(JsonNode root) {
    return root.has("error") && !root.path("error").isNull();
  }

  /** Accepts both {@code result.task} and a bare task under {@code result}. */
  private static JsonNode locateTask(JsonNode root) {
    JsonNode result = root.path("result");
    JsonNode nested = result.path("task");
    return nested.isMissingNode() || nested.isNull() ? result : nested;
  }

  private static String firstArtifactText(JsonNode task) {
    StringBuilder text = new StringBuilder();
    for (JsonNode part : task.path("artifacts").path(0).path("parts")) {
      if (part.hasNonNull("text")) {
        text.append(part.path("text").asText());
      }
    }
    return text.toString();
  }

  private static String sendMessage(String text) {
    String messageId = UUID.randomUUID().toString();
    return """
        {
          "jsonrpc": "2.0",
          "id": "%s",
          "method": "SendMessage",
          "params": {
            "message": {
              "messageId": "%s",
              "role": "ROLE_USER",
              "parts": [ { "text": "%s" } ]
            },
            "configuration": {}
          }
        }
        """
        .formatted(messageId, messageId, text);
  }

  private static String sendMessageWithContextId(String text, String contextId) {
    String messageId = UUID.randomUUID().toString();
    return """
        {
          "jsonrpc": "2.0",
          "id": "%s",
          "method": "SendMessage",
          "params": {
            "message": {
              "messageId": "%s",
              "role": "ROLE_USER",
              "contextId": "%s",
              "parts": [ { "text": "%s" } ]
            }
          }
        }
        """
        .formatted(messageId, messageId, contextId, text);
  }

  private static String sendMessageWithSkill(String text) {
    String messageId = UUID.randomUUID().toString();
    return """
        {
          "jsonrpc": "2.0",
          "id": "%s",
          "method": "SendMessage",
          "params": {
            "message": {
              "messageId": "%s",
              "role": "ROLE_USER",
              "parts": [ { "text": "%s" } ],
              "metadata": { "skillId": "%s" }
            }
          }
        }
        """
        .formatted(messageId, messageId, text, A2aProtocolConstants.CREATE_CHAIN_SKILL_ID);
  }

  private static String legacySendMessage(String text) {
    String messageId = UUID.randomUUID().toString();
    return """
        {
          "jsonrpc": "2.0",
          "id": "%s",
          "method": "message/send",
          "params": {
            "message": {
              "kind": "message",
              "messageId": "%s",
              "role": "user",
              "parts": [ { "kind": "text", "text": "%s" } ]
            }
          }
        }
        """
        .formatted(messageId, messageId, text);
  }
}
