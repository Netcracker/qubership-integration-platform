package org.qubership.integration.platform.ai.a2a.transport;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.PolicyChoiceSelection;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.*;
import org.qubership.integration.platform.ai.plan.*;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.facade.*;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class A2aPolicyChoiceTest {
  @InjectMock CreateChainApplicationFacade facade;
  @Inject RequirementCaptureTools capture;
  @Inject RequirementDraftStore drafts;
  @Inject PolicyChoiceSelection choices;
  @Inject ConversationService conversations;
  @Inject ObjectMapper json;
  @Inject A2aDispatchCrashGate crashes;
  @Inject org.qubership.integration.platform.ai.a2a.persistence.A2aMessageReceiptRepository receipts;
  private final AtomicReference<CreateChainExecutionSnapshot> snapshot = new AtomicReference<>();
  private boolean captureOnContinue;
  private CreateChainExecutionStatus terminalStatus;
  private boolean replayEvidenceAvailable;
  private final AtomicInteger continuations = new AtomicInteger();
  private final AtomicReference<ContinueCreateChainCommand> continued = new AtomicReference<>();

  @BeforeEach
  void prepareFacade() {
    crashes.clear();
    continuations.set(0);
    continued.set(null);
    captureOnContinue = false;
    terminalStatus = null;
    replayEvidenceAvailable = true;
    when(facade.snapshot(any())).thenAnswer(ignored -> Optional.ofNullable(snapshot.get()));
    when(facade.publicState(any())).thenAnswer(ignored -> Optional.ofNullable(snapshot.get())
        .map(current -> new CreateChainApplicationFacade.PublicState(current, List.of())));
    when(facade.inputAlreadyApplied(any(), any(), any())).thenAnswer(call -> replayEvidenceAvailable
        && continued.get() != null && continued.get().taskId().equals(call.getArgument(0))
        && continued.get().commandId().equals(call.getArgument(1))
        && continued.get().clarificationText().equals(call.getArgument(2)));
    when(facade.start(any())).thenAnswer(call -> {
      StartCreateChainCommand command = call.getArgument(0);
      capturePolicyQuestion(command.taskId());
      var pending = new CreateChainPendingAction.Clarify("Choose access control.", List.of());
      snapshot.set(new CreateChainExecutionSnapshot(command.taskId(), "run-" + command.taskId(),
          CreateChainExecutionStatus.INPUT_REQUIRED, 7, pending, ""));
      return Multi.createFrom().item(new CreateChainEvent.Waiting(pending));
    });
    when(facade.continueWithInput(any())).thenAnswer(call -> {
      continued.set(call.getArgument(0));
      if (continuations.getAndIncrement() == 0 && captureOnContinue) captureSelectedPolicy(snapshot.get().taskId());
      var status = terminalStatus == null ? CreateChainExecutionStatus.WORKING : terminalStatus;
      snapshot.set(new CreateChainExecutionSnapshot(snapshot.get().taskId(), snapshot.get().runId(), status, 8, null,
          status == CreateChainExecutionStatus.FAILED ? "Original execution failure" : ""));
      if (status == CreateChainExecutionStatus.COMPLETED) return Multi.createFrom().item(new CreateChainEvent.Completed(snapshot.get()));
      if (status == CreateChainExecutionStatus.FAILED) return Multi.createFrom().item(new CreateChainEvent.Failed(
          "Original execution failure", snapshot.get()));
      return Multi.createFrom().item(new CreateChainEvent.Progress("Selected policy received"));
    });
  }

  @ParameterizedTest
  @CsvSource({"keep-requested,KEEP_REQUESTED,NONE", "use-recommended,USE_RECOMMENDED,RBAC"})
  void selectsTheAdvertisedPolicyAndDoesNotRepeatCompletedAnswers(String action, String decision, String value) throws Exception {
    String task = start();
    var card = card(task);
    assertEquals(PolicyChoiceSelection.ARTIFACT_TYPE, card.get("artifactType"));
    assertEquals(List.of("keep-requested", "use-recommended"), card.get("allowedActions"));
    String messageId = UUID.randomUUID().toString();
    String body = answer(task, messageId, action, card);
    post(body).then().statusCode(200);
    var selected = choices.trusted(task);
    assertEquals(1, selected.size());
    assertEquals(decision, selected.getFirst().selection().decision().name());
    assertEquals(value, selected.getFirst().selection().selectedValue());
    assertEquals(InputOrigin.TRUSTED, continued.get().origin());
    assertTrue(continued.get().clarificationText().contains(decision + " (" + value + ")"),
        () -> continued.get().clarificationText());
    assertTrue(continued.get().clarificationText().contains("In the same update"),
        () -> continued.get().clarificationText());
    int turns = conversations.archive().read(task).turns().size();
    post(body).then().statusCode(200);
    String opposite = action.equals("keep-requested") ? "use-recommended" : "keep-requested";
    post(answer(task, messageId, opposite, card)).then().statusCode(422);
    post(answer(task, UUID.randomUUID().toString(), action, card)).then().statusCode(200)
        .body("task.status.state", org.hamcrest.Matchers.equalTo("TASK_STATE_WORKING"));
    assertEquals(1, continuations.get());
    assertEquals(selected, choices.trusted(task));
    assertEquals(turns, conversations.archive().read(task).turns().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "FAILED"})
  void aRepeatedPolicyAnswerDoesNotReopenATerminalTask(String status) throws Exception {
    String task = start();
    var card = card(task);
    terminalStatus = CreateChainExecutionStatus.valueOf(status);
    String expectedState = "TASK_STATE_" + status;
    String body = answer(task, UUID.randomUUID().toString(), "keep-requested", card);
    post(body).then().statusCode(200).body("task.status.state", org.hamcrest.Matchers.equalTo(expectedState));
    int turns = conversations.archive().read(task).turns().size();
    post(body).then().statusCode(200).body("task.status.state", org.hamcrest.Matchers.equalTo(expectedState));
    post(answer(task, UUID.randomUUID().toString(), "keep-requested", card)).then().statusCode(400);
    given().urlEncodingEnabled(false).header("A2A-Version", "1.0").get(URI.create("/tasks/" + task))
        .then().statusCode(200).body("status.state", org.hamcrest.Matchers.equalTo(expectedState));
    if (status.equals("FAILED")) given().urlEncodingEnabled(false).header("A2A-Version", "1.0")
        .get(URI.create("/tasks/" + task)).then().statusCode(200)
        .body("status.message.parts[0].text", org.hamcrest.Matchers.equalTo("Original execution failure"));
    assertEquals(1, continuations.get());
    assertEquals(1, choices.trusted(task).size());
    assertEquals(turns, conversations.archive().read(task).turns().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"hash", "revision", "type"})
  void staleOrForeignPolicyCardsLeaveTheCurrentChoiceOpen(String mismatch) throws Exception {
    String task = start();
    var current = card(task);
    var stale = new java.util.LinkedHashMap<>(current);
    if (mismatch.equals("hash")) stale.put("artifactHash", "old-card");
    if (mismatch.equals("revision")) stale.put("revision", 6);
    if (mismatch.equals("type")) stale.put("artifactType", "other-policy");
    int turns = conversations.archive().read(task).turns().size();
    post(answer(task, UUID.randomUUID().toString(), "keep-requested", stale)).then().statusCode(200);
    assertEquals(0, continuations.get());
    assertEquals(List.of(), choices.trusted(task));
    assertEquals(turns, conversations.archive().read(task).turns().size());
    assertEquals(current.get("artifactHash"), card(task).get("artifactHash"));
    post(answer(task, UUID.randomUUID().toString(), "keep-requested", current)).then().statusCode(200);
    assertEquals(1, choices.trusted(task).size());
    assertEquals(1, continuations.get());
  }

  @Test
  void aPolicyReplyWithoutTheCardBindingIsRejected() throws Exception {
    String task = start();
    int turns = conversations.archive().read(task).turns().size();
    post(message(task, UUID.randomUUID().toString(), Map.of("action", "keep-requested"))).then().statusCode(422);
    assertEquals(List.of(), choices.trusted(task));
    assertEquals(0, continuations.get());
    assertEquals(turns, conversations.archive().read(task).turns().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"7.5", "9223372036854775808", "missing"})
  void invalidRevisionCannotBecomeAValidCardRevision(String revision) throws Exception {
    String task = start();
    var card = card(task);
    var data = new java.util.LinkedHashMap<>(card);
    data.put("action", "keep-requested");
    if (revision.equals("missing")) data.remove("revision");
    else data.put("revision", revision);
    post(message(task, UUID.randomUUID().toString(), data)).then().statusCode(422);
    assertEquals(List.of(), choices.trusted(task));
    assertEquals(0, continuations.get());
  }

  @ParameterizedTest(name = "{0}, exact application evidence={1}")
  @CsvSource({"WORKING,true", "COMPLETED,true", "FAILED,true", "COMPLETED,false", "FAILED,false"})
  void anIncompletePolicyReceiptUsesExactCommandEvidence(String status, boolean evidence) throws Exception {
    String task = start();
    var card = card(task);
    String messageId = UUID.randomUUID().toString();
    String body = answer(task, messageId, "use-recommended", card);
    terminalStatus = status.equals("WORKING") ? null : CreateChainExecutionStatus.valueOf(status);
    replayEvidenceAvailable = evidence;
    captureOnContinue = true;
    crashes.arm(A2aDispatchCrashGate.Point.AFTER_RUNTIME_COMMIT);
    post(body).then().statusCode(500);
    var selection = choices.trusted(task);
    assertEquals(1, selection.size());
    var first = continued.get();
    int turns = conversations.archive().read(task).turns().size();
    boolean terminal = !status.equals("WORKING");
    post(body).then().statusCode(terminal && !evidence ? 400 : 200);
    assertEquals(first, continued.get());
    assertEquals(selection, choices.trusted(task));
    assertEquals(turns, conversations.archive().read(task).turns().size());
    assertEquals(terminal ? 1 : 2, continuations.get());
    assertEquals(terminal && !evidence, receipts.findCallerReceipt("local", "local-user", messageId)
        .orElseThrow().incomplete());
  }

  private String start() throws Exception {
    snapshot.set(null);
    String body = json.writeValueAsString(Map.of("message", Map.of("messageId", UUID.randomUUID().toString(),
        "metadata", Map.of("skillId", "create-chain@2"), "role", "ROLE_USER", "parts",
        List.of(Map.of("text", "Create external GET /policy with NONE. Proceed without IDS.")))));
    return post(body).then().statusCode(200).extract().path("task.id");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> card(String task) {
    return given().urlEncodingEnabled(false).header("A2A-Version", "1.0")
        .get(URI.create("/tasks/" + task)).then().statusCode(200).extract()
        .path("status.message.parts.find { it.data != null }.data");
  }

  private String answer(String task, String messageId, String action, Map<String, Object> card) throws Exception {
    return message(task, messageId, Map.of("action", action,
        "artifactType", card.getOrDefault("artifactType", PolicyChoiceSelection.ARTIFACT_TYPE),
        "artifactHash", card.getOrDefault("artifactHash", "missing-card"),
        "revision", card.getOrDefault("revision", 7)));
  }

  private String message(String task, String messageId, Map<String, Object> data) throws Exception {
    return json.writeValueAsString(Map.of("message", Map.of("messageId", messageId, "taskId", task,
        "metadata", Map.of("skillId", "create-chain@2"), "role", "ROLE_USER", "parts", List.of(Map.of("data", data)))));
  }

  private static io.restassured.response.Response post(String body) {
    return given().urlEncodingEnabled(false).header("A2A-Version", "1.0").contentType(ContentType.JSON)
        .body(body).post(URI.create("/message:send"));
  }

  private void captureSelectedPolicy(String task) throws Exception {
    drafts.beginTurn(task, conversations.archive().read(task).activeTurns().getLast().message().content());
    var old = drafts.get(task).orElseThrow().authoredDraft().facts().stream()
        .filter(fact -> fact.sourceFactId().equals("policy")).findFirst().orElseThrow();
    var policy = old.policyConflict();
    var decision = choices.trusted(task).getFirst().selection().decision();
    var selected = new FactInput(old.sourceFactId(), old.interactionIds(), old.kind(), old.polarity(), old.text(),
        old.fieldMapping(), new RequirementPolicyConflict(policy.policyId(), policy.propertyKey(),
            policy.requestedValue(), policy.recommendedValue(), policy.questionId(), decision));
    var update = new DraftUpdate(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(selected),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of("policy-choice"), null);
    try (var ignored = ToolSession.open(task)) {
      var result = json.readValue(capture.updateRequirementDraft(update), RequirementCaptureResult.class);
      assertTrue(result.accepted(), result.toString());
    }
    snapshot.set(new CreateChainExecutionSnapshot(task, snapshot.get().runId(),
        CreateChainExecutionStatus.WORKING, 8, null, ""));
  }

  private void capturePolicyQuestion(String task) throws Exception {
    drafts.beginTurn(task, "Create external GET /policy with NONE. Proceed without IDS.");
    String source = conversations.archive().read(task).activeTurns().getLast().id();
    var input = new DraftInput(new FlowInput(List.of(new InteractionInput("entry", Direction.INBOUND,
        "Caller", "GET /policy", "External endpoint", null, null)), List.of()),
        List.of(new FactInput("goal", List.of("entry"), FactKind.GOAL, Polarity.POSITIVE, "Exercise policy choices."),
            new FactInput("native-entry", List.of("entry"), FactKind.PARAMETER, Polarity.POSITIVE,
                "Use the external HTTP endpoint.", null, null,
                new RequirementElementIntent("entry", "http-trigger", RequirementElementIntent.Placement.ANCHOR,
                    "entry", null, null, null, List.of(new PlanProperty("externalRoute", "true")), List.of())),
            new FactInput("policy", List.of("entry"), FactKind.CONSTRAINT, Polarity.POSITIVE,
                "Requested NONE; corporate RBAC recommendation.", null,
                new RequirementPolicyConflict("CORPORATE_CIP_STANDARDS:12.4", "accessControlType", "NONE", "RBAC", "policy-choice", null))),
        List.of(new CapabilityInput("entry", "http-trigger", HttpMode.CUSTOM, HttpMethod.GET, "/policy", null, null, null)),
        List.of(new QuestionInput("policy-choice", List.of("entry"), "Keep NONE or use recommended RBAC?",
            new AuthorQuestionEvidence("entry", "accessControlType", AuthorQuestionEvidence.Kind.POLICY_CHOICE, List.of(source)))),
        new DraftSettings(false, null));
    try (var ignored = ToolSession.open(task)) {
      var result = json.readValue(capture.captureRequirementDraft(input), RequirementCaptureResult.class);
      assertTrue(result.accepted(), result.toString());
    }
  }
}
