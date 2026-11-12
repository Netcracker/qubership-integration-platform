package org.qubership.integration.platform.ai.productpipeline.create;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.qubership.integration.platform.ai.a2a.transport.A2aCommandId;
import org.qubership.integration.platform.ai.a2a.transport.A2aSseTestSupport;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModel;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.AmendmentInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClarificationDispositionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftUpdate;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FactKind;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMethod;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.HttpMode;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.Polarity;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.TransitionInput;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementPolicyConflict;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.clarification.ClarificationTransition.Disposition;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;

/**
 * Public create-chain clarification behavior with one scripted gather model.
 *
 * <p>The browser and A2A entry points are the assertion seam. The gather agent is the same
 * production agent, backed by {@link ScriptedStreamingChatModel}.
 */
@QuarkusTest
@TestProfile(SerializedClarificationFlowTest.Profile.class)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("serialized-clarification-script")
class SerializedClarificationFlowTest {
  static final String ROLE_QUESTION = "Which roles may call this external HTTP trigger?";
  static final String DESCRIPTION_QUESTION = "Which description should the entry use?";
  static final String SOURCE =
      "Create an external HTTP trigger at POST /orders with RBAC. I have not chosen the role or the entry"
          + " description. Proceed without IDS.";
  private static final Path EVIDENCE = Path.of("target", "serialized-clarifications", "evidence.json");

  @InjectMock RateLimitStreamingChatModel streaming;
  @InjectMock RateLimitChatModel synchronous;
  @InjectMock @RestClient CatalogRestClient catalog;
  @InjectMock CatalogFolders folders;
  @Inject ObjectMapper json;
  @InjectSpy ConversationService conversations;
  @Inject RequirementDraftStore drafts;
  @Inject ProductPipelineRunStore runs;
  @Inject CreateChainApplicationFacade facade;

  private final AtomicReference<ScriptedStreamingChatModel> script = new AtomicReference<>();
  /** A2A does not archive the opening prompt. Seed that turn when discovery opens the conversation. */
  private final AtomicBoolean seedOpeningSource = new AtomicBoolean();

  @BeforeEach
  void installModels() {
    when(catalog.getElementsByType(anyString(), anyString())).thenReturn(List.of(
        trigger("orders-trigger", "orders-chain", "Orders"),
        trigger("invoices-trigger", "invoices-chain", "Invoices")));
    when(folders.tree()).thenReturn(List.of());
    seedOpeningSource.set(false);
    doAnswer(call -> {
      call.callRealMethod();
      if (seedOpeningSource.get()) {
        prepareA2aSource(call.getArgument(0));
      }
      return null;
    }).when(conversations).getOrCreate(anyString());
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      String prompt = request.messages().stream().filter(UserMessage.class::isInstance)
          .map(UserMessage.class::cast).map(UserMessage::singleText).reduce((first, second) -> second).orElse("");
      String reply = prompt.contains("scenario type name") ? "CREATE_CHAIN_PLAN" : "en";
      return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
    }).when(synchronous).chat(any(ChatRequest.class));
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      StreamingChatResponseHandler handler = call.getArgument(1);
      if (!gatherTurn(request)) {
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("en")).build());
        return null;
      }
      ScriptedStreamingChatModel current = script.get();
      assertTrue(current != null, "A gather script must be installed before the model is called");
      current.chat(request, handler);
      return null;
    }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  @Test
  void externalTriggerAsksOneRoleQuestionAndAcceptsAbc() throws Exception {
    String chat = chat();
    JsonNode asked = ask(chat, SOURCE);
    assertEquals(ROLE_QUESTION, asked.path("question").asText());
    assertFalse(asked.path("question").asText().contains(DESCRIPTION_QUESTION));
    String offer = asked.path("id").asText();
    String sourceTurn = sourceTurn(chat);
    script.set(applyRole(sourceTurn, ConversationArchive.deterministicAnswerTurnId(offer, "abc")));
    String answered = postChat(chat, "abc", offer);
    assertFalse(visibleQuestions(answered).contains(ROLE_QUESTION), answered);
    assertTrue(visibleQuestions(answered).contains(DESCRIPTION_QUESTION), answered);
    assertEquals(1, countAnswer(chat, offer, "abc"));
    assertTrue(json.writeValueAsString(draft(chat)).contains("[\\\"abc\\\"]")
        || json.writeValueAsString(draft(chat)).contains("[\"abc\"]"), json.writeValueAsString(draft(chat)));
    script.get().assertComplete();
  }

  @Test
  void twoMissingPropertiesAppearOneAtATime() throws Exception {
    String chat = chat();
    JsonNode first = ask(chat, SOURCE);
    assertEquals(ROLE_QUESTION, first.path("question").asText());
    assertFalse(first.path("question").asText().contains(DESCRIPTION_QUESTION));
    String sourceTurn = sourceTurn(chat);
    String offer = first.path("id").asText();
    script.set(applyRole(sourceTurn, ConversationArchive.deterministicAnswerTurnId(offer, "abc")));
    String second = postChat(chat, "abc", offer);
    assertEquals(List.of(DESCRIPTION_QUESTION), visibleQuestions(second));
    script.get().assertComplete();
  }

  @Test
  void oneAnswerResolvesBothClearValues() throws Exception {
    String chat = chat();
    JsonNode asked = ask(chat, SOURCE);
    String offer = asked.path("id").asText();
    String answer = "abc. The entry description is Orders intake.";
    script.set(applyBoth(sourceTurn(chat), ConversationArchive.deterministicAnswerTurnId(offer, answer)));
    String done = postChat(chat, answer, offer);
    assertFalse(done.contains(ROLE_QUESTION), done);
    assertFalse(done.contains(DESCRIPTION_QUESTION), done);
    String saved = json.writeValueAsString(draft(chat));
    assertTrue(saved.contains("abc"), saved);
    assertTrue(saved.contains("Orders intake"), saved);
    assertNotEquals("requirement-discovery", runs.loadByConversation(chat).orElseThrow().run().currentStageId());
    script.get().assertComplete();
  }

  @Test
  void ambiguousLaterValuePreservesTheAppliedAnswer() throws Exception {
    String chat = chat();
    String source = "Create an external HTTP trigger at POST /orders with RBAC. The entry description is either"
        + " Orders or Invoices. Proceed without IDS.";
    JsonNode asked = ask(chat, source);
    String offer = asked.path("id").asText();
    String answer = "abc. The description is either Orders or Invoices.";
    script.set(applyRoleLeaveAmbiguousDescription(sourceTurn(chat),
        ConversationArchive.deterministicAnswerTurnId(offer, answer)));
    String next = postChat(chat, answer, offer);
    assertEquals(List.of(DESCRIPTION_QUESTION), visibleQuestions(next));
    String saved = json.writeValueAsString(draft(chat));
    assertTrue(saved.contains("abc"), saved);
    assertTrue(saved.contains("Orders"), saved);
    assertTrue(saved.contains("Invoices"), saved);
    script.get().assertComplete();
  }

  @Test
  void focusedRepairAppliesAMisclassifiedAnswerBeforeAnyRepeat() throws Exception {
    String chat = chat();
    String runId = null;
    JsonNode asked = ask(chat, SOURCE);
    runId = runs.loadByConversation(chat).orElseThrow().run().runId();
    String offer = asked.path("id").asText();
    String answerTurn = ConversationArchive.deterministicAnswerTurnId(offer, "abc");
    script.set(misclassifyThenRepair(sourceTurn(chat), answerTurn));
    String next = postChat(chat, "abc", offer);
    assertFalse(next.contains("Which RBAC role should I use"), next);
    assertEquals(List.of(DESCRIPTION_QUESTION), visibleQuestions(next));
    assertEquals(1, countAnswer(chat, offer, "abc"));
    assertEquals(runId, runs.loadByConversation(chat).orElseThrow().run().runId());
    String saved = json.writeValueAsString(draft(chat));
    assertTrue(saved.contains("abc"), saved);
    script.get().assertComplete();
    writeEvidence(chat, asked, next, offer, runId);
  }

  @Test
  void nonAnswerAppliesAnUnrelatedFactAndKeepsTheRoleQuestion() throws Exception {
    String chat = chat();
    JsonNode asked = ask(chat, SOURCE);
    String offer = asked.path("id").asText();
    String reply = "The caller is the billing service.";
    String answerTurn = ConversationArchive.deterministicAnswerTurnId(offer, reply);
    script.set(applyNonAnswer(sourceTurn(chat), answerTurn, reply));
    String next = postChat(chat, reply, offer);
    JsonNode offeredAgain = openDecision(chat);
    assertEquals(ROLE_QUESTION, offeredAgain.path("question").asText(), offeredAgain.toString());
    assertNotEquals(offer, offeredAgain.path("id").asText(), offeredAgain.toString());
    assertFalse(offeredAgain.path("id").asText().isBlank(), offeredAgain.toString());
    assertFalse(next.contains("CLARIFICATION_ANSWER_NO_PROGRESS"), next);
    String saved = json.writeValueAsString(draft(chat));
    assertTrue(saved.contains(reply), saved);
    assertFalse(saved.contains("\"abc\""), saved);
  }

  @Test
  void runIdIsStableIntoTheNextStage() throws Exception {
    String chat = chat();
    JsonNode asked = ask(chat, SOURCE);
    String runId = runs.loadByConversation(chat).orElseThrow().run().runId();
    String offer = asked.path("id").asText();
    String answerTurn = ConversationArchive.deterministicAnswerTurnId(offer, "abc");
    script.set(applyRole(sourceTurn(chat), answerTurn));
    String secondSse = postChat(chat, "abc", offer);
    JsonNode second = decisions(secondSse).getFirst();
    script.set(applyDescription(sourceTurn(chat), answerTurn,
        ConversationArchive.deterministicAnswerTurnId(second.path("id").asText(), "Orders intake")));
    postChat(chat, "Orders intake", second.path("id").asText());
    var run = runs.loadByConversation(chat).orElseThrow();
    assertEquals(runId, run.run().runId());
    assertNotEquals("requirement-discovery", run.run().currentStageId(), run.toString());
  }

  @Test
  void browserRetryAppendsOneTurnAndOneMutation() throws Exception {
    String chat = chat();
    JsonNode asked = ask(chat, SOURCE);
    String offer = asked.path("id").asText();
    script.set(applyRole(sourceTurn(chat), ConversationArchive.deterministicAnswerTurnId(offer, "abc")));
    postChat(chat, "abc", offer);
    int turns = conversations.archive().read(chat).turns().size();
    String after = json.writeValueAsString(draft(chat));
    postChat(chat, "abc", offer);
    assertEquals(turns, conversations.archive().read(chat).turns().size());
    assertEquals(after, json.writeValueAsString(draft(chat)));
    assertEquals(1, countAnswer(chat, offer, "abc"));
  }

  @Test
  void a2aRetryStaysPinnedToTheOriginalOffer() throws Exception {
    String body = A2aSseTestSupport.textMessageBody("a2a-open", null, SOURCE);
    script.set(captureQuestions());
    seedOpeningSource.set(true);
    try {
      var opened = postA2a(body);
      assertEquals(200, opened.statusCode(), opened.asString());
      String task = opened.jsonPath().getString("task.id");
      var pending = (CreateChainPendingAction.Clarify) facade.snapshot(task).orElseThrow().pendingAction();
      assertEquals(ROLE_QUESTION, PipelineGates.strip(pending.reason()));
      String offer = pending.clarificationOfferId();
      String answer = answerBody("a2a-answer", task, offer, "abc");
      script.set(applyRole(sourceTurn(task), ConversationArchive.deterministicAnswerTurnId(offer, "abc")));
      var first = postA2a(answer);
      assertEquals(200, first.statusCode(), first.asString());
      var continued = (CreateChainPendingAction.Clarify) facade.snapshot(task).orElseThrow().pendingAction();
      assertEquals(DESCRIPTION_QUESTION, PipelineGates.strip(continued.reason()));
      assertNotEquals(offer, continued.clarificationOfferId());
      assertTrue(json.writeValueAsString(draft(task)).contains("abc"), json.writeValueAsString(draft(task)));
      int turns = conversations.archive().read(task).turns().size();
      String saved = json.writeValueAsString(draft(task));
      var retry = postA2a(answer);
      assertEquals(200, retry.statusCode(), retry.asString());
      assertEquals(turns, conversations.archive().read(task).turns().size());
      assertEquals(saved, json.writeValueAsString(draft(task)));
      assertEquals(1, countAnswer(task, offer, "abc"));
      String command = A2aCommandId.derive("local", "local-user", "a2a-answer") + ":accept-input";
      assertEquals(1L, runs.loadByConversation(task).orElseThrow().transitions().stream()
          .filter(transition -> command.equals(transition.commandId())).count());
    } finally {
      seedOpeningSource.set(false);
    }
  }

  @Test
  void staleAnswerCannotMutateTheNextQuestion() throws Exception {
    String chat = chat();
    JsonNode asked = ask(chat, SOURCE);
    String firstOffer = asked.path("id").asText();
    script.set(applyRole(sourceTurn(chat), ConversationArchive.deterministicAnswerTurnId(firstOffer, "abc")));
    String secondSse = postChat(chat, "abc", firstOffer);
    String secondOffer = decisions(secondSse).getFirst().path("id").asText();
    String before = json.writeValueAsString(draft(chat));
    int turns = conversations.archive().read(chat).turns().size();
    String stale = postChat(chat, "xyz", firstOffer);
    assertTrue(stale.contains(DESCRIPTION_QUESTION), stale);
    assertFalse(stale.contains("xyz"), stale);
    assertEquals(before, json.writeValueAsString(draft(chat)));
    assertEquals(turns, conversations.archive().read(chat).turns().size());
    assertNotEquals(firstOffer, secondOffer);
  }

  @Test
  void idsPolicyAndFolderGatesDoNotConsumeAGenericOffer() throws Exception {
    assertForeignOfferLeavesTheGate(idsChat(), "ids-path-choice",
        "Show the Integration Design Specification (IDS) for review?", false);
    assertForeignOfferLeavesTheGate(policyChat(), "requirement-policy-choice",
        "Keep NONE or use recommended RBAC?", true);
    assertForeignOfferLeavesTheGate(folderChat(), PipelineGates.TARGET_FOLDER_CHOICE,
        "Which Salesforce folder should the chain use?", false);
  }

  @Test
  void chainCallChoiceWritesTargetReference() throws Exception {
    String chat = chat();
    script.set(captureChainCall());
    JsonNode asked = decisions(postChat(chat, "Call the Orders chain from POST /call. Proceed without IDS.", null))
        .getFirst();
    assertFalse(asked.path("question").asText().isBlank(), asked.toString());
    String offer = asked.path("id").asText();
    script.set(new ScriptedStreamingChatModel(Turn.text("unused", request -> {
      throw new AssertionError("An exact chain-call answer is applied before another gather turn");
    }, "unused")));
    String next = postChat(chat, "Orders", offer);
    String saved = json.writeValueAsString(draft(chat));
    assertTrue(saved.contains("orders-trigger"), saved);
    assertFalse(saved.contains("invoices-trigger"), saved);
    assertFalse(next.contains(asked.path("question").asText()), next);
  }

  private void assertForeignOfferLeavesTheGate(String chat, String marker, String question,
      boolean genericOfferMustStayUnanswered) throws Exception {
    String before = drafts.get(chat).map(draft -> {
      try {
        return json.writeValueAsString(draft.authoredDraft());
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    }).orElse("");
    String genericOffer = openOfferId(chat);
    if (genericOfferMustStayUnanswered) {
      assertFalse(genericOffer.isBlank(), marker);
    }
    script.set(reask(question));
    String sse = postChat(chat, "abc", "offer-not-on-this-gate");
    if ("requirement-policy-choice".equals(marker)) {
      assertTrue(sse.contains(marker), sse);
    }
    String after = drafts.get(chat).map(draft -> {
      try {
        return json.writeValueAsString(draft.authoredDraft());
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    }).orElse("");
    assertEquals(before, after, marker + "\n" + sse);
    assertEquals(0, countAnswer(chat, "offer-not-on-this-gate", "abc"));
    if (!genericOffer.isBlank()) {
      assertEquals(0, countAnswer(chat, genericOffer, "abc"), genericOffer);
    }
    assertVisibleGate(chat, marker);
  }

  private ScriptedStreamingChatModel reask(String question) {
    return new ScriptedStreamingChatModel(
        Turn.tool("stay", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask", request -> {}, question),
        Turn.tool("stay-again", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask-again", request -> {}, question),
        Turn.tool("stay-third", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask-third", request -> {}, question));
  }

  private String idsChat() throws Exception {
    String chat = chat();
    script.set(captureReady(new DraftSettings(null, null)));
    String sse = observed(chat, postChat(chat, SOURCE, null));
    assertTrue(sse.contains("ids-path-choice"), sse);
    return chat;
  }

  private String policyChat() throws Exception {
    String chat = chat();
    script.set(capturePolicy());
    String sse = observed(chat, postChat(chat,
        "Create external POST /orders with externalRoute=true and accessControlType=NONE. If RBAC is selected use"
            + " exactly role qip-viewer. Proceed without IDS.",
        null));
    assertTrue(sse.contains("requirement-policy-choice"), sse);
    return chat;
  }

  private String folderChat() throws Exception {
    when(folders.tree()).thenReturn(List.of(
        new CatalogFolders.Folder("one", "Salesforce", null, "Salesforce"),
        new CatalogFolders.Folder("two", "Salesforce", "parent", "Parent/Salesforce")));
    String chat = chat();
    script.set(captureFolder());
    String sse = observed(chat, postChat(chat, "Create POST /orders in folder Salesforce. Proceed without IDS.", null));
    assertTrue(sse.contains(PipelineGates.TARGET_FOLDER_CHOICE), sse);
    return chat;
  }

  private JsonNode ask(String chat, String source) throws Exception {
    script.set(captureQuestions());
    String sse = postChat(chat, source, null);
    JsonNode decision = decisions(sse).getFirst();
    assertEquals("clarify", decision.path("kind").asText(), sse);
    assertEquals(ROLE_QUESTION, decision.path("question").asText(), sse);
    return decision;
  }

  private ScriptedStreamingChatModel captureQuestions() {
    return capture(null);
  }

  private ScriptedStreamingChatModel captureFolder() {
    return new ScriptedStreamingChatModel(
        new Turn("capture-folder", request -> assertAdvertised(request, "captureRequirementDraft"), handler -> {
          String conversation = org.qubership.integration.platform.ai.chat.ToolSession.resolveConversationId();
          String turn = sourceTurn(conversation);
          emit(handler, "capture-folder", "captureRequirementDraft",
              Map.of("draft", openingDraft(turn, turn, List.of(), new DraftSettings(false, null, "Salesforce"))));
        }),
        Turn.tool("stay-folder", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask-folder", request -> {}, "Which Salesforce folder should the chain use?"),
        Turn.tool("stay-folder-again", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask-folder-again", request -> {}, "Which Salesforce folder should the chain use?"));
  }

  private ScriptedStreamingChatModel captureReady(DraftSettings settings) {
    return new ScriptedStreamingChatModel(
        new Turn("capture-ready", request -> assertAdvertised(request, "captureRequirementDraft"), handler -> {
          String conversation = org.qubership.integration.platform.ai.chat.ToolSession.resolveConversationId();
          String turn = sourceTurn(conversation);
          emit(handler, "capture-ready", "captureRequirementDraft",
              Map.of("draft", openingDraft(turn, turn, List.of(), settings)));
        }),
        Turn.tool("stay-ready", request -> assertAcceptedIfPresent(request), "finishRequirementDiscoveryTurn",
            "{\"directive\":\"STAY\"}"),
        Turn.text("gate", request -> {}, "Choose the next gate."),
        Turn.tool("remain", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("gate-again", request -> {}, "Choose the next gate."));
  }

  private ScriptedStreamingChatModel capture(DraftInput fixed) {
    return new ScriptedStreamingChatModel(
        new Turn("capture", request -> assertAdvertised(request, "captureRequirementDraft"), handler -> {
          String chat = org.qubership.integration.platform.ai.chat.ToolSession.resolveConversationId();
          String turn = sourceTurn(chat);
          DraftInput draft = fixed == null ? openingDraft(turn, turn, List.of(
              question("entry-roles", "entry", "roles", ROLE_QUESTION, Kind.MISSING_VALUE, List.of(turn), null),
              question("entry-description", "entry", "correlationIdName", DESCRIPTION_QUESTION, Kind.MISSING_VALUE,
                  List.of(turn), null)), new DraftSettings(false, null)) : withReviewed(fixed, turn);
          emit(handler, "capture", "captureRequirementDraft", Map.of("draft", draft));
        }),
        Turn.tool("stay", request -> assertAcceptedIfPresent(request), "finishRequirementDiscoveryTurn",
            "{\"directive\":\"STAY\"}"),
        Turn.text("ask", request -> {}, ROLE_QUESTION));
  }

  private ScriptedStreamingChatModel capturePolicy() {
    return new ScriptedStreamingChatModel(
        new Turn("capture-policy", request -> assertAdvertised(request, "captureRequirementDraft"), handler -> {
          String chat = org.qubership.integration.platform.ai.chat.ToolSession.resolveConversationId();
          String turn = sourceTurn(chat);
          emit(handler, "capture-policy", "captureRequirementDraft", Map.of("draft", policyDraft(turn)));
        }),
        Turn.tool("stay", request -> assertAcceptedIfPresent(request), "finishRequirementDiscoveryTurn",
            "{\"directive\":\"STAY\"}"),
        Turn.text("ask-policy", request -> {}, "Keep NONE or use recommended RBAC?"),
        Turn.tool("remain", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("gate-again", request -> {}, "Choose the next gate."));
  }

  private ScriptedStreamingChatModel captureChainCall() {
    return new ScriptedStreamingChatModel(
        new Turn("capture-chain", request -> assertAdvertised(request, "captureRequirementDraft"), handler -> {
          String chat = org.qubership.integration.platform.ai.chat.ToolSession.resolveConversationId();
          emit(handler, "capture-chain", "captureRequirementDraft", Map.of("draft", chainCallDraft(sourceTurn(chat))));
        }),
        Turn.tool("stay", request -> assertTrue(lastTool(request).path("accepted").asBoolean(),
            lastTool(request).toString()), "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask-chain", request -> {}, "Which chain trigger should the call use?"));
  }

  private ScriptedStreamingChatModel applyRole(String sourceTurn, String answerTurn) {
    return stay(updateRole(sourceTurn, answerTurn, true));
  }

  private ScriptedStreamingChatModel applyBoth(String sourceTurn, String answerTurn) {
    DraftUpdate changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(entryFact(answerTurn, "abc", "Orders intake")),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of("entry-roles", "entry-description"), null,
        List.of(new AmendmentInput("/facts/native-entry/elementIntent/requestedProperties", answerTurn, "Orders intake")));
    return continueAfter(changes);
  }

  private ScriptedStreamingChatModel applyDescription(String sourceTurn, String roleAnswer, String answerTurn) {
    DraftUpdate changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(entryFact(answerTurn, "abc", "Orders intake")), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of("entry-description"), null,
        List.of(new AmendmentInput("/facts/native-entry/elementIntent/requestedProperties", answerTurn,
            "Orders intake")));
    return continueAfter(changes);
  }

  private ScriptedStreamingChatModel applyRoleLeaveAmbiguousDescription(String sourceTurn, String answerTurn) {
    QuestionInput ambiguous = question("entry-description", "entry", "correlationIdName", DESCRIPTION_QUESTION,
        Kind.AMBIGUOUS_ANSWER, List.of(sourceTurn, answerTurn),
        new AuthorQuestionEvidence.AmbiguityEvidence(answerTurn, "either Orders or Invoices",
            List.of("Orders", "Invoices")));
    DraftUpdate changes = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(roleFact(sourceTurn, answerTurn)), List.of(), List.of(), List.of(), List.of(),
        List.of(ambiguous), List.of("entry-roles"), null,
        List.of(new AmendmentInput("/facts/native-entry/elementIntent/requestedProperties", answerTurn, "abc")),
        null, new ClarificationDispositionInput("entry-description", Disposition.AMBIGUOUS, answerTurn,
            "either Orders or Invoices", List.of("Orders", "Invoices")));
    return stay(changes);
  }

  private ScriptedStreamingChatModel misclassifyThenRepair(String sourceTurn, String answerTurn) {
    QuestionInput reworded = question("entry-roles", "entry", "roles",
        "Which RBAC role should I use for this trigger?", Kind.MISSING_VALUE,
        List.of(sourceTurn, answerTurn), null);
    DraftUpdate wording = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(reworded), List.of(), null, List.of());
    return new ScriptedStreamingChatModel(
        new Turn("repeat-wording", request -> assertAdvertised(request, "updateRequirementDraft"),
            handler -> emit(handler, "repeat-wording", "updateRequirementDraft", Map.of("changes", wording))),
        new Turn("apply-focused", request -> assertTrue(lastTool(request).toString().contains(
            "CLARIFICATION_ANSWER_NO_PROGRESS"), lastTool(request).toString()),
            handler -> emit(handler, "apply-focused", "updateRequirementDraft",
                Map.of("changes", updateRole(sourceTurn, answerTurn, true)))),
        Turn.tool("stay", request -> assertTrue(lastTool(request).path("accepted").asBoolean(),
            lastTool(request).toString()), "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("second", request -> {}, DESCRIPTION_QUESTION));
  }

  private ScriptedStreamingChatModel applyNonAnswer(String sourceTurn, String answerTurn, String reply) {
    QuestionInput reworded = question("entry-roles", "entry", "roles",
        "Which RBAC role should I use for this trigger?", Kind.MISSING_VALUE, List.of(sourceTurn, answerTurn), null);
    DraftUpdate wording = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(reworded), List.of(), null, List.of());
    QuestionInput stillOpen = question("entry-roles", "entry", "roles", ROLE_QUESTION, Kind.MISSING_VALUE,
        List.of(sourceTurn, answerTurn), null);
    FactInput caller = new FactInput("caller", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE, reply,
        null, null, null, new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput(
            answerTurn, reply));
    DraftUpdate confirmed = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(caller),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(stillOpen), List.of(), null, List.of(), null,
        new ClarificationDispositionInput("entry-roles", Disposition.NON_ANSWER, answerTurn, reply, List.of()));
    return new ScriptedStreamingChatModel(
        new Turn("reword", request -> assertAdvertised(request, "updateRequirementDraft"),
            handler -> emit(handler, "reword", "updateRequirementDraft", Map.of("changes", wording))),
        new Turn("confirm", request -> assertTrue(lastTool(request).toString().contains(
            "CLARIFICATION_ANSWER_NO_PROGRESS"), lastTool(request).toString()),
            handler -> emit(handler, "confirm", "updateRequirementDraft", Map.of("changes", confirmed))),
        Turn.tool("stay", request -> assertTrue(lastTool(request).path("accepted").asBoolean(),
            lastTool(request).toString()), "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("again", request -> {}, ROLE_QUESTION));
  }

  private ScriptedStreamingChatModel stay(DraftUpdate changes) {
    return new ScriptedStreamingChatModel(
        new Turn("apply", request -> assertAdvertised(request, "updateRequirementDraft"),
            handler -> emit(handler, "apply", "updateRequirementDraft", Map.of("changes", changes))),
        Turn.tool("stay", request -> assertTrue(lastTool(request).path("accepted").asBoolean(),
            lastTool(request).toString()), "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("next", request -> {}, DESCRIPTION_QUESTION));
  }

  private ScriptedStreamingChatModel continueAfter(DraftUpdate changes) {
    return new ScriptedStreamingChatModel(
        new Turn("apply", request -> assertAdvertised(request, "updateRequirementDraft"),
            handler -> emit(handler, "apply", "updateRequirementDraft", Map.of("changes", changes))),
        Turn.tool("continue", request -> assertTrue(lastTool(request).path("accepted").asBoolean(),
            lastTool(request).toString()), "finishRequirementDiscoveryTurn", "{\"directive\":\"CONTINUE\"}"),
        Turn.text("done", request -> {}, "Requirements are complete."));
  }

  private DraftUpdate updateRole(String sourceTurn, String answerTurn, boolean keepDescription) {
    List<QuestionInput> updates = keepDescription ? List.of(question("entry-description", "entry", "correlationIdName",
        DESCRIPTION_QUESTION, Kind.MISSING_VALUE, List.of(sourceTurn, answerTurn), null)) : List.of();
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(roleFact(sourceTurn, answerTurn)), List.of(), List.of(), List.of(), List.of(), updates,
        List.of("entry-roles"), null,
        List.of(new AmendmentInput("/facts/native-entry/elementIntent/requestedProperties", answerTurn, "abc")));
  }

  private DraftInput openingDraft(String sourceTurn, String reviewed, List<QuestionInput> questions,
      DraftSettings settings) {
    String turn = sourceTurn == null ? "pending" : sourceTurn;
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /orders", null, null, null),
        new InteractionInput("submit", Direction.OUTBOUND, "Orders API", "submit", null, null, null)),
        List.of(new TransitionInput("entry", "submit"))),
        List.of(new FactInput("goal", List.of("submit"), FactKind.GOAL, Polarity.POSITIVE, "Accept one order."),
            anchor("entry", "http-trigger", turn, "POST /orders"),
            anchor("submit", "http-sender", turn, "Proceed without IDS.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/orders", null, null, null),
            new CapabilityInput("submit", "http-sender", null, HttpMethod.POST, "https://example.com/orders",
                null, null, null)),
        questions, settings);
  }

  private DraftInput withReviewed(DraftInput fixed, String turn) {
    if (fixed.openQuestions() == null || fixed.openQuestions().isEmpty() || !"pending".equals(
        fixed.facts().stream().filter(fact -> fact.evidence() != null).findFirst().map(fact -> "cited").orElse(""))) {
      return fixed;
    }
    return fixed;
  }

  private DraftInput policyDraft(String turn) {
    return new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND, "Caller",
        "POST /orders", null, null, null)), List.of()),
        List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Check policy handling."),
            new FactInput("access", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
                "External route uses NONE.", null, null, new RequirementElementIntent("entry", "http-trigger",
                    RequirementElementIntent.Placement.ANCHOR, "entry", null, null, null, null,
                    List.of(new PlanProperty("externalRoute", "true"), new PlanProperty("accessControlType", "NONE"),
                        new PlanProperty("roles", "[\"qip-viewer\"]")), List.of("NONE"))),
            new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
                "Requested NONE; corporate RBAC recommendation.", null,
                new RequirementPolicyConflict(CompilerSecurityValidator.EXTERNAL_RBAC_POLICY, "accessControlType",
                    "NONE", "RBAC", "policy-choice", null), null)),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/orders",
            null, null, null)),
        List.of(question("policy-choice", "entry", "accessControlType", "Keep NONE or use recommended RBAC?",
            Kind.POLICY_CHOICE, List.of(turn), null)),
        new DraftSettings(false, null));
  }

  private DraftInput chainCallDraft(String turn) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("entry", Direction.INBOUND, "Caller", "POST /call", null, null, null),
        new InteractionInput("call", Direction.OUTBOUND, "Orders", "Invoke", null, null, null)),
        List.of(new TransitionInput("entry", "call"))),
        List.of(new FactInput("goal", List.of("call"), FactKind.GOAL, Polarity.POSITIVE, "Call the Orders chain."),
            anchor("entry", "http-trigger", turn, "POST /call"),
            anchor("call", "chain-call-2", turn, "Proceed without IDS.")),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.POST, "/call", null, null, null),
            new CapabilityInput("call", "chain-call-2", null, null, null, null, null, null)),
        List.of(question("call-target", "call", "targetReference", "Which chain trigger should the call use?",
            Kind.CATALOG_CHOICE, List.of(turn), null)),
        new DraftSettings(false, null));
  }

  private static FactInput anchor(String interaction, String type, String turn) {
    return anchor(interaction, type, turn, "POST /orders");
  }

  private static FactInput anchor(String interaction, String type, String turn, String quote) {
    return new FactInput("native-" + interaction, List.of(interaction), FactKind.PARAMETER, Polarity.POSITIVE,
        "Use the selected " + type + " element.", null, null,
        new RequirementElementIntent(interaction, type, RequirementElementIntent.Placement.ANCHOR, interaction,
            null, null, null, null, List.of(), List.of()),
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput(turn, quote));
  }

  private static FactInput entryFact(String answerTurn, String role, String description) {
    List<PlanProperty> properties = new ArrayList<>();
    properties.add(new PlanProperty("accessControlType", "RBAC"));
    properties.add(new PlanProperty("externalRoute", "true"));
    properties.add(new PlanProperty("roles", "[\"" + role + "\"]"));
    if (description != null) {
      properties.add(new PlanProperty("correlationIdName", description));
    }
    return new FactInput("native-entry", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
        "Use the selected http-trigger element.", null, null,
        new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR, "entry",
            null, null, null, null, properties, List.of()),
        new org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput(answerTurn,
            description == null ? role : description));
  }

  private static FactInput roleFact(String sourceTurn, String answerTurn) {
    return entryFact(answerTurn, "abc", null);
  }

  private static QuestionInput question(String id, String interaction, String property, String text, Kind kind,
      List<String> turns, AuthorQuestionEvidence.AmbiguityEvidence ambiguity) {
    return new QuestionInput(id, List.of(interaction), text,
        new AuthorQuestionEvidence(interaction, property, kind, turns, ambiguity));
  }

  private String postChat(String chat, String message, String offer) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("conversationId", chat);
    body.put("message", message);
    body.put("scenarioHint", "CREATE_CHAIN_PLAN");
    if (offer != null) {
      body.put("clarificationOfferId", offer);
    }
    return given().contentType(ContentType.JSON).body(json.writeValueAsString(body))
        .post("/api/v1/chat").then().statusCode(200).extract().asString();
  }

  private io.restassured.response.Response postA2a(String body) {
    return given().urlEncodingEnabled(false).header("A2A-Version", "1.0").contentType(ContentType.JSON).body(body)
        .post(java.net.URI.create("/message:send"));
  }

  private String answerBody(String messageId, String taskId, String offer, String text) throws Exception {
    return json.writeValueAsString(Map.of("message", Map.of(
        "metadata", Map.of("skillId", "create-chain@2"),
        "messageId", messageId,
        "taskId", taskId,
        "role", "ROLE_USER",
        "parts", List.of(Map.of("text", text),
            Map.of("data", Map.of("action", "clarify", "clarificationOfferId", offer))))));
  }

  private List<JsonNode> decisions(String sse) throws Exception {
    List<JsonNode> found = readDecisions(sse);
    assertFalse(found.isEmpty(), sse);
    return found;
  }

  private List<JsonNode> readDecisions(String sse) throws Exception {
    List<JsonNode> found = new ArrayList<>();
    String[] lines = sse.replace("\r\n", "\n").split("\n", -1);
    for (int index = 0; index < lines.length; index++) {
      if (!"event: decision".equals(unwrapSseData(lines[index]))) {
        continue;
      }
      for (int next = index + 1; next < lines.length; next++) {
        String payload = unwrapSseData(lines[next]);
        if (payload == null || payload.isBlank()) {
          continue;
        }
        if (payload.startsWith("event:")) {
          break;
        }
        if (payload.startsWith("{")) {
          found.add(json.readTree(payload));
        }
        break;
      }
    }
    return found;
  }

  private List<String> visibleQuestions(String sse) throws Exception {
    List<String> questions = new ArrayList<>();
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (JsonNode decision : readDecisions(sse)) {
      String question = decision.path("question").asText("");
      if (!question.isBlank() && seen.add(question)) {
        questions.add(question);
      }
    }
    return questions;
  }

  private JsonNode openDecision(String chat) throws Exception {
    String body = given().get("/api/v1/chat/{id}/decision", chat).asString();
    assertFalse(body == null || body.isBlank() || "null".equals(body), body);
    return json.readTree(body);
  }

  /**
   * The open card's gate, not question text a model may repeat after the gate is left.
   * The public reason is the stripped prompt, so the marker lives on the pending gate id.
   */
  private void assertVisibleGate(String chat, String marker) throws Exception {
    var pending = facade.snapshot(chat).orElseThrow().pendingAction();
    assertTrue(pending instanceof CreateChainPendingAction.Clarify, String.valueOf(pending));
    var clarify = (CreateChainPendingAction.Clarify) pending;
    if ("requirement-policy-choice".equals(marker)) {
      assertEquals(marker, openDecision(chat).path("artifactType").asText(), String.valueOf(pending));
      assertEquals(PipelineGates.STAGE_CLARIFICATION, clarify.gateId(), String.valueOf(pending));
      return;
    }
    assertEquals(marker, clarify.gateId(), String.valueOf(pending));
  }

  private String openOfferId(String chat) {
    var document = runs.loadByConversation(chat).orElse(null);
    if (document == null) {
      return "";
    }
    String open = "";
    for (var transition : document.transitions()) {
      var clarification = transition.clarification();
      if (clarification == null) {
        continue;
      }
      if (clarification.answer() != null && !open.isEmpty()
          && open.equals(clarification.answer().offerId())) {
        open = "";
      }
      if (clarification.offer() != null && clarification.offer().offerId() != null) {
        open = clarification.offer().offerId();
      }
    }
    return open;
  }

  private String observed(String chat, String sse) {
    String decision = given().get("/api/v1/chat/{id}/decision", chat).asString();
    String pending = String.valueOf(facade.snapshot(chat).map(snapshot -> snapshot.pendingAction()).orElse(null));
    return sse + "\n" + decision + "\n" + pending;
  }

  /** RestAssured receives Quarkus SSE lines, which prefix each authored frame line with {@code data:}. */
  private static String unwrapSseData(String line) {
    String payload = line == null ? "" : line.trim();
    boolean wrapped = false;
    while (payload.startsWith("data:")) {
      wrapped = true;
      payload = payload.substring("data:".length()).trim();
    }
    return wrapped ? payload : payload.isEmpty() ? null : payload;
  }

  /**
   * A2A does not archive the opening author message. Production resolves a clarification answer,
   * so this seed does not mark those turns ready.
   */
  private void prepareA2aSource(String chat) {
    sourceTurn(chat);
  }

  private String sourceTurn(String chat) {
    var existing = conversations.archive().read(chat).activeTurns().stream()
        .filter(turn -> turn.message().role().name().equals("USER"))
        .map(turn -> turn.id()).findFirst();
    if (existing.isPresent()) {
      return existing.get();
    }
    var input = conversations.archive().beginInput(chat, SOURCE, null);
    conversations.archive().resolveInput(chat, input, SOURCE);
    return input.turnId();
  }

  private Object draft(String chat) {
    return drafts.get(chat).orElseThrow().authoredDraft();
  }

  private int countAnswer(String chat, String offer, String text) {
    String turnId = ConversationArchive.deterministicAnswerTurnId(offer, text);
    return (int) conversations.archive().read(chat).turns().stream().filter(turn -> turnId.equals(turn.id())).count();
  }

  private void writeEvidence(String chat, JsonNode asked, String nextSse, String offer, String runId) throws Exception {
    Files.createDirectories(EVIDENCE.getParent());
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("runId", runId);
    evidence.put("roleQuestion", ROLE_QUESTION);
    evidence.put("secondQuestion", DESCRIPTION_QUESTION);
    evidence.put("firstDecision", asked);
    evidence.put("answerSse", nextSse);
    evidence.put("offerId", offer);
    evidence.put("archive", conversations.archive().read(chat));
    evidence.put("draft", draft(chat));
    evidence.put("run", runs.loadByConversation(chat).orElseThrow());
    json.writerWithDefaultPrettyPrinter().writeValue(EVIDENCE.toFile(), evidence);
  }

  private static void assertAdvertised(ChatRequest request, String tool) {
    assertTrue(request.parameters().toolSpecifications().stream().anyMatch(spec -> tool.equals(spec.name())), tool);
  }

  private static boolean gatherTurn(ChatRequest request) {
    if (request.parameters() == null || request.parameters().toolSpecifications() == null) {
      return false;
    }
    return request.parameters().toolSpecifications().stream().anyMatch(spec ->
        "captureRequirementDraft".equals(spec.name()) || "updateRequirementDraft".equals(spec.name()));
  }

  private void assertAcceptedIfPresent(ChatRequest request) {
    if (request.messages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance)) {
      assertTrue(lastTool(request).path("accepted").asBoolean(), lastTool(request).toString());
    }
  }

  private JsonNode lastTool(ChatRequest request) {
    try {
      return json.readTree(request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
          .map(ToolExecutionResultMessage.class::cast).reduce((first, second) -> second).orElseThrow().text());
    } catch (Exception error) {
      throw new AssertionError(request.messages().toString(), error);
    }
  }

  private void emit(StreamingChatResponseHandler handler, String id, String name, Object arguments) {
    try {
      handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(
          dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id(id).name(name)
              .arguments(json.writeValueAsString(arguments)).build())).build());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static CatalogElementResponseDto trigger(String id, String chainId, String name) {
    CatalogElementResponseDto trigger = new CatalogElementResponseDto();
    trigger.id = id;
    trigger.chainId = chainId;
    trigger.chainName = name;
    trigger.name = name;
    trigger.type = "chain-trigger-2";
    return trigger;
  }

  private static String chat() {
    return "serialized-" + UUID.randomUUID();
  }

  public static class Profile extends TestArtifactBlobStoreProducer.Profile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.rest-client.catalog-api.scope",
          "jakarta.enterprise.context.ApplicationScoped");
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      Set<Class<?>> enabled = new java.util.LinkedHashSet<>(super.getEnabledAlternatives());
      enabled.add(FixtureKnowledge.class);
      return enabled;
    }
  }

  @ApplicationScoped
  @Alternative
  public static class FixtureKnowledge implements KnowledgeContextProvider {
    private final FakeKnowledgeClient client = FakeKnowledgeClient.defaultFixture();

    @Override
    public KnowledgeQueryContext forConversation(String conversationId) {
      return client.forConversation(conversationId);
    }
  }
}
