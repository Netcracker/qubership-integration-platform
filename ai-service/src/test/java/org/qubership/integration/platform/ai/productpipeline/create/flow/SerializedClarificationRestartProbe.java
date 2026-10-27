package org.qubership.integration.platform.ai.productpipeline.create.flow;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.qubership.integration.platform.ai.a2a.transport.A2aCommandId;
import org.qubership.integration.platform.ai.a2a.transport.A2aSseTestSupport;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModelProducer;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.AmendmentInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.ClaimEvidenceInput;
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
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.FileCheckpointBlobStore;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeQueryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence.Kind;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

/**
 * Child process for one clarification restart. Production discovery stays in place. The gather
 * model is scripted, locale and routing replies are fixed, and the file blob store stops after
 * the answer is durable.
 */
@QuarkusTest
@TestProfile(SerializedClarificationRestartProbe.Profile.class)
public class SerializedClarificationRestartProbe {
  static final String SOURCE =
      "Create an external HTTP trigger at POST /orders with RBAC. I have not chosen the role or the entry"
          + " description. Proceed without IDS.";
  static final String ROLE_QUESTION = "Which roles may call this external HTTP trigger?";
  static final String DESCRIPTION_QUESTION = "Which description should the entry use?";
  static final String ANSWER_MESSAGE = "clarification-answer";
  private static final AtomicReference<ScriptedStreamingChatModel> SCRIPT = new AtomicReference<>();
  private static volatile ConversationService archives;
  private static volatile boolean armed;
  private static volatile String answerTaskId;
  private static volatile String answerOfferId;

  @Inject ConversationService conversations;
  @Inject ObjectMapper json;
  @Inject ProductPipelineRunStore runs;
  @Inject RequirementDraftStore drafts;
  @Inject CreateChainApplicationFacade facade;

  static Path directory() {
    return CreateChainServerRestartProbe.directory();
  }

  static boolean prepare() {
    return CreateChainServerRestartProbe.prepare();
  }

  public static void main(String[] args) {
    SummaryGeneratingListener listener = new SummaryGeneratingListener();
    try (var session = LauncherFactory.openSession()) {
      var launcher = session.getLauncher();
      launcher.registerTestExecutionListeners(listener);
      launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
          org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(
              SerializedClarificationRestartProbe.class.getName())).build());
    }
    listener.getSummary().printTo(new java.io.PrintWriter(System.out, true));
    listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out, true));
    if (listener.getSummary().getTestsSucceededCount() != 1) {
      System.exit(1);
    }
  }

  @Test
  void restartAfterTheDurableClarificationAnswer() throws Exception {
    if (prepare()) {
      var opened = post(A2aSseTestSupport.textMessageBody("opening-message", null, SOURCE));
      assertEquals(200, opened.statusCode(), opened.asString());
      String taskId = opened.jsonPath().getString("task.id");
      var pending = (CreateChainPendingAction.Clarify) facade.snapshot(taskId).orElseThrow().pendingAction();
      assertEquals(ROLE_QUESTION, PipelineGates.strip(pending.reason()), opened.asString());
      assertFalse(PipelineGates.strip(pending.reason()).contains(DESCRIPTION_QUESTION), opened.asString());
      String offerId = pending.clarificationOfferId();
      assertFalse(offerId.isBlank(), pending.toString());
      String runId = runs.loadByConversation(taskId).orElseThrow().run().runId();
      Files.writeString(directory().resolve("task"), taskId);
      Files.writeString(directory().resolve("offer-id"), offerId);
      Files.writeString(directory().resolve("run-id"), runId);
      String body = json.writeValueAsString(Map.of("message", Map.of(
          "metadata", Map.of("skillId", "create-chain@2"),
          "messageId", ANSWER_MESSAGE,
          "taskId", taskId,
          "role", "ROLE_USER",
          "parts", List.of(Map.of("text", "abc"),
              Map.of("data", Map.of("action", "clarify", "clarificationOfferId", offerId))))));
      Files.writeString(directory().resolve("request.json"), body);
      answerTaskId = taskId;
      answerOfferId = offerId;
      armed = true;
      new Thread(() -> {
        try {
          var response = post(body);
          Files.writeString(directory().resolve("http-response"), response.statusCode() + "\n" + response.asString());
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }).start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!Files.exists(directory().resolve("checkpoint.ready")) && System.nanoTime() < deadline) {
        Thread.sleep(25);
      }
      assertTrue(Files.exists(directory().resolve("checkpoint.ready")), "the answer must be durable before discovery mutates");
      String saved = json.writeValueAsString(drafts.get(taskId).orElseThrow());
      assertFalse(saved.contains("abc"), saved);
      String turnId = ConversationArchive.deterministicAnswerTurnId(offerId, "abc");
      assertTrue(json.writeValueAsString(conversations.archive().read(taskId)).contains(turnId));
      Files.writeString(directory().resolve("ready"), "clarification answer committed");
      new CountDownLatch(1).await();
      return;
    }
    String taskId = Files.readString(directory().resolve("task")).trim();
    String offerId = Files.readString(directory().resolve("offer-id")).trim();
    String runId = Files.readString(directory().resolve("run-id")).trim();
    String body = Files.readString(directory().resolve("request.json"));
    var response = post(body);
    assertEquals(200, response.statusCode(), response.asString());
    assertTrue(response.asString().contains(DESCRIPTION_QUESTION), response.asString());
    assertFalse(response.asString().contains(ROLE_QUESTION), response.asString());
    var run = runs.loadByConversation(taskId).orElseThrow();
    assertEquals(runId, run.run().runId());
    String commandId = A2aCommandId.derive("local", "local-user", ANSWER_MESSAGE) + ":accept-input";
    assertEquals(1L, run.transitions().stream().filter(transition -> commandId.equals(transition.commandId())).count());
    String saved = json.writeValueAsString(drafts.get(taskId).orElseThrow());
    assertTrue(saved.contains("abc"), saved);
    assertEquals(DESCRIPTION_QUESTION, drafts.get(taskId).orElseThrow().authoredDraft().openQuestions().getFirst().text());
    assertEquals(1, drafts.get(taskId).orElseThrow().authoredDraft().openQuestions().size());
    String turnId = ConversationArchive.deterministicAnswerTurnId(offerId, "abc");
    assertEquals(1L, conversations.archive().read(taskId).turns().stream()
        .filter(turn -> turnId.equals(turn.id())).count());
    Files.writeString(directory().resolve("result"), "COMPLETED");
  }

  private static io.restassured.response.Response post(String body) {
    return given().config(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.socket.timeout", 120_000)
            .setParam("http.connection.timeout", 10_000)))
        .urlEncodingEnabled(false).header("A2A-Version", "1.0").contentType(ContentType.JSON).body(body)
        .post(java.net.URI.create("/message:send"));
  }

  private static ScriptedStreamingChatModel script() {
    ScriptedStreamingChatModel current = SCRIPT.get();
    if (current != null) {
      return current;
    }
    synchronized (SCRIPT) {
      if (SCRIPT.get() == null) {
        SCRIPT.set(prepare() ? questionScript() : answerScript());
      }
      return SCRIPT.get();
    }
  }

  private static ScriptedStreamingChatModel questionScript() {
    return new ScriptedStreamingChatModel(
        new Turn("capture", request -> assertTrue(request.parameters().toolSpecifications().stream()
            .anyMatch(spec -> "captureRequirementDraft".equals(spec.name()))), handler -> {
          String chat = ToolSession.resolveConversationId();
          String turn = sourceTurn(chat);
          emit(handler, "capture", "captureRequirementDraft", Map.of("draft", openingDraft(turn)));
        }),
        Turn.tool("stay", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("ask", request -> {}, ROLE_QUESTION));
  }

  private static ScriptedStreamingChatModel answerScript() {
    return new ScriptedStreamingChatModel(
        new Turn("apply", request -> assertTrue(request.parameters().toolSpecifications().stream()
            .anyMatch(spec -> "updateRequirementDraft".equals(spec.name()))), handler -> {
          try {
            String offer = Files.readString(directory().resolve("offer-id")).trim();
            String chat = Files.readString(directory().resolve("task")).trim();
            String source = sourceTurn(chat);
            String answer = ConversationArchive.deterministicAnswerTurnId(offer, "abc");
            emit(handler, "apply", "updateRequirementDraft", Map.of("changes", applyRole(source, answer)));
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        }),
        Turn.tool("stay", request -> {}, "finishRequirementDiscoveryTurn", "{\"directive\":\"STAY\"}"),
        Turn.text("next", request -> {}, DESCRIPTION_QUESTION));
  }

  private static boolean gatherTurn(ChatRequest request) {
    return request.parameters() != null && request.parameters().toolSpecifications() != null
        && request.parameters().toolSpecifications().stream().anyMatch(spec ->
            "captureRequirementDraft".equals(spec.name()) || "updateRequirementDraft".equals(spec.name()));
  }

  /** A2A leaves the opening prompt unarchived and a clarification answer RECEIVED. */
  private static void prepareA2aSource(String chat) {
    sourceTurn(chat);
    var archive = archives.archive();
    var document = archive.read(chat);
    for (var turn : document.turns()) {
      if (turn.message().role().name().equals("USER") && "RECEIVED".equals(turn.status())
          && turn.metadata() != null && turn.metadata().has("clarificationOfferId")) {
        archive.resolveInput(chat, new ConversationArchive.Input(turn.id(), document.generation(),
            document.activeTurnIds().indexOf(turn.id())), turn.rawText());
        document = archive.read(chat);
      }
    }
  }

  private static String sourceTurn(String chat) {
    var archive = archives.archive();
    var existing = archive.read(chat).activeTurns().stream()
        .filter(turn -> turn.rawText() != null && turn.rawText().contains("POST /orders"))
        .findFirst();
    if (existing.isPresent()) {
      return existing.get().id();
    }
    var input = archive.beginInput(chat, SOURCE, null);
    archive.resolveInput(chat, input, SOURCE);
    return input.turnId();
  }

  private static DraftInput openingDraft(String turn) {
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
        List.of(question("entry-roles", "roles", ROLE_QUESTION, List.of(turn)),
            question("entry-description", "correlationIdName", DESCRIPTION_QUESTION, List.of(turn))),
        new DraftSettings(false, null));
  }

  private static DraftUpdate applyRole(String sourceTurn, String answerTurn) {
    return new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new FactInput("native-entry", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
            "Use the selected http-trigger element.", null, null,
            new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
                "entry", null, null, null, null, List.of(new PlanProperty("accessControlType", "RBAC"),
                    new PlanProperty("externalRoute", "true"),                     new PlanProperty("roles", "[\"abc\"]")),
                List.of()),
            new ClaimEvidenceInput(answerTurn, "abc"))),
        List.of(), List.of(), List.of(), List.of(),
        List.of(question("entry-description", "correlationIdName", DESCRIPTION_QUESTION,
            List.of(sourceTurn, answerTurn))),
        List.of("entry-roles"), null,
        List.of(new AmendmentInput("/facts/native-entry/elementIntent/requestedProperties", answerTurn, "abc")));
  }

  private static FactInput anchor(String interaction, String type, String turn, String quote) {
    return new FactInput("native-" + interaction, List.of(interaction), FactKind.PARAMETER, Polarity.POSITIVE,
        "Use the selected " + type + " element.", null, null,
        new RequirementElementIntent(interaction, type, RequirementElementIntent.Placement.ANCHOR, interaction,
            null, null, null, null, List.of(), List.of()),
        new ClaimEvidenceInput(turn, quote));
  }

  private static QuestionInput question(String id, String property, String text, List<String> turns) {
    return new QuestionInput(id, List.of("entry"), text,
        new AuthorQuestionEvidence("entry", property, Kind.MISSING_VALUE, turns));
  }

  private static void emit(StreamingChatResponseHandler handler, String id, String name, Object arguments) {
    try {
      ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
      handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(
          ToolExecutionRequest.builder().id(id).name(name).arguments(mapper.writeValueAsString(arguments)).build()))
          .build());
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(Harness.class, FixtureKnowledge.class, ClarificationArchive.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.datasource.jdbc.url", System.getProperty("recovery.probe.jdbc"),
          "quarkus.datasource.username", "recovery",
          "quarkus.datasource.password", "recovery",
          "quarkus.datasource.devservices.enabled", "false",
          "quarkus.http.test-port", "0",
          "qip.ai.a2a.dispatch-lease", "PT2S",
          "qip.ai.a2a.dispatch-heartbeat-interval", "PT0.25S");
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

  /**
   * A2A does not archive the opening prompt, and it leaves a clarification answer RECEIVED.
   * Resolving that answer here happens before discovery snapshots the archive.
   */
  @ApplicationScoped
  @Alternative
  public static class ClarificationArchive extends ConversationService {
    @Inject
    ClarificationArchive(AppConfig appConfig, ConversationArchive archive) {
      super(appConfig, archive);
      archives = this;
    }

    @Override
    public void getOrCreate(String conversationId) {
      super.getOrCreate(conversationId);
      prepareA2aSource(conversationId);
    }
  }

  /** Upstream models the production rate-limit wrappers delegate to. Live before flow restore. */
  @ApplicationScoped
  @Alternative
  public static class Harness {
    @Produces
    @ApplicationScoped
    @ModelName(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME)
    StreamingChatModel scriptedGatherModel() {
      return new StreamingChatModel() {
        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
          if (!gatherTurn(request)) {
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("en")).build());
            return;
          }
          script().chat(request, handler);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
          return ChatRequestParameters.builder().build();
        }
      };
    }

    @Produces
    @ApplicationScoped
    @ModelName(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME)
    ChatModel scriptedSyncModel() {
      return new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
          String prompt = request.messages().stream().filter(UserMessage.class::isInstance)
              .map(UserMessage.class::cast).map(UserMessage::singleText)
              .reduce((first, second) -> second).orElse("");
          String reply = prompt.contains("scenario type name") ? "CREATE_CHAIN_PLAN" : "en";
          return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
      };
    }

    @Produces
    @ApplicationScoped
    ArtifactBlobStore blobs(ObjectMapper mapper) {
      var delegate = FileCheckpointBlobStore.open(directory().resolve("blobs"));
      return new ArtifactBlobStore() {
        @Override
        public void put(String key, byte[] content) {
          delegate.put(key, content);
        }

        @Override
        public Optional<byte[]> get(String key) {
          return delegate.get(key);
        }

        @Override
        public List<String> list(String prefix) {
          return delegate.list(prefix);
        }

        @Override
        public Optional<VersionedBlob> getVersioned(String key) {
          return delegate.getVersioned(key);
        }

        @Override
        public void putIfVersion(String key, byte[] content, String version) {
          delegate.putIfVersion(key, content, version);
          if (!prepare() || !armed || !key.startsWith("product-pipeline-runs/") || answerOfferId == null) {
            return;
          }
          String document = new String(content, StandardCharsets.UTF_8);
          String commandId = A2aCommandId.derive("local", "local-user", ANSWER_MESSAGE) + ":accept-input";
          if (!document.contains(commandId) || !document.contains(answerOfferId)) {
            return;
          }
          String archiveKey = "conversation-archive/" + Base64.getUrlEncoder().withoutPadding()
              .encodeToString(answerTaskId.getBytes(StandardCharsets.UTF_8)) + "/source.json";
          String turnId = ConversationArchive.deterministicAnswerTurnId(answerOfferId, "abc");
          String source = delegate.get(archiveKey).map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("");
          if (!source.contains(turnId)) {
            return;
          }
          try {
            mapper.readValue(content, ProductPipelineRunDocument.class);
            Files.writeString(directory().resolve("checkpoint.ready"), "committed");
            new CountDownLatch(1).await();
          } catch (Exception failure) {
            throw new IllegalStateException("Clarification restart fixture failed", failure);
          }
        }
      };
    }
  }
}
