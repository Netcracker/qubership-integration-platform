package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;

class ToolReadProgressTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
  private final CompilationArtifacts artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
  private final CompilationSessions sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
  private final ToolCallFingerprintStore credits = new ToolCallFingerprintStore(json, artifacts, sessions);
  private final CaptureAttemptFeedbackStore feedback = new CaptureAttemptFeedbackStore(credits, artifacts, sessions);
  private final ChainPlanStore graphs = new ChainPlanStore(artifacts, sessions);
  private final String chat = "read-progress-" + UUID.randomUUID();
  private static final String OWNER = "cip-service-call-generator";
  private static final String ARGS = "{\"elementType\":\"service-call\",\"propertyPath\":\"after\"}";

  private ToolReadProgress guard() { return new ToolReadProgress(artifacts, sessions, feedback, graphs, json); }
  private void check(ToolReadProgress guard, ChatRequest request) { guard.prepare(request, chat, OWNER, "run"); }
  private void begin() { feedback.beginScope(chat, OWNER, List.of("unchanged native inputs")); }

  @Test
  void catalogQueriesWithNoNewSystemsShareProgressAcrossDifferentSearchTerms() {
    begin();
    for (int attempt = 0; attempt < 5; attempt++) {
      var call = ToolExecutionRequest.builder().id("catalog-" + attempt).name("searchCatalogSystems")
          .arguments("{\"searchCondition\":\"term-" + attempt + "\"}").build();
      var request = ChatRequest.builder().messages(UserMessage.from("Resolve the required catalog operation."),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, "{\"ok\":true,\"data\":[]}")).build();
      var prepared = guard().prepare(request, chat, OWNER, "run");
      if (attempt < 2) assertSame(request, prepared);
      else assertTrue(prepared.messages().toString().contains("catalog dependency"), prepared.messages().toString());
    }
    var call = ToolExecutionRequest.builder().id("new-system").name("searchCatalogSystems")
        .arguments("{\"searchCondition\":\"new\"}").build();
    var request = ChatRequest.builder().messages(UserMessage.from("Resolve the required catalog operation."),
        AiMessage.from(call), ToolExecutionResultMessage.from(call,
            "{\"ok\":true,\"data\":[{\"id\":\"new-system\"}]}")).build();
    assertDoesNotThrow(() -> guard().prepare(request, chat, OWNER, "run"));
    assertEquals(0, artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS)
        .orElseThrow().payload().path("stalledSearches").asInt());
  }

  @Test
  void rephrasedKnowledgeSearchesReturningTheSameContentTriggerGuidance() {
    begin();
    for (int attempt = 0; attempt < 4; attempt++) {
      var call = ToolExecutionRequest.builder().id("knowledge-" + attempt).name("searchRequirementKnowledge")
          .arguments("{\"question\":\"question-" + attempt + "\"}").build();
      var request = ChatRequest.builder().messages(UserMessage.from("Resolve the platform question."),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, "QIP knowledge context:\n\nSame source"))
          .build();
      var prepared = guard().prepare(request, chat, OWNER, "run");
      if (attempt < 3) assertSame(request, prepared);
      else assertTrue(prepared.messages().toString().contains("Retained tool results"), prepared.toString());
    }
    var newSource = ToolExecutionRequest.builder().id("new-source").name("searchRequirementKnowledge")
        .arguments("{\"question\":\"different\"}").build();
    guard().prepare(ChatRequest.builder().messages(UserMessage.from("Find the missing contract."),
        AiMessage.from(newSource), ToolExecutionResultMessage.from(newSource,
            "QIP knowledge context:\n\nNew source")).build(), chat, OWNER, "run");
    assertEquals(0, artifacts.latest(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS)
        .orElseThrow().payload().path("stalledSearches").asInt());
  }

  @Test
  void discoveryReadsUseTheBoundProductOwnerWithoutCompilerMdc() {
    feedback.beginScope(chat, "requirement-discovery", List.of("author sources"));
    org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
        .bindDiscovery("run", chat, ignored -> {});
    try {
      guard().prepare(read("one", ARGS, "{}"), chat, null, "run");
      guard().prepare(read("two", ARGS, "{}"), chat, null, "run");
      var prepared = guard().prepare(read("three", ARGS, "{}"), chat, "stale-worker-owner", "run");
      assertTrue(prepared.messages().toString().contains("Retained tool results"), prepared.messages().toString());
    } finally {
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind(chat);
    }
  }
  private ChatRequest read(String id, String args, String result) {
    var call = ToolExecutionRequest.builder().id(id).name("describeElementProperty").arguments(args).build();
    return ChatRequest.builder().messages(UserMessage.from("Current producer instruction " + id),
        AiMessage.from(call), ToolExecutionResultMessage.from(call, result)).build();
  }

  @Test
  void documentationContinuationMakesProgressAndRepeatedPageGetsALocalTurnBeforeStopping() {
    begin();
    var guard = guard();
    for (int page = 0; page < 4; page++) {
      var call = ToolExecutionRequest.builder().id("page-" + page).name("readDocumentation")
          .arguments("{\"id\":\"guide.md#0\",\"cursor\":\"page-" + page + "\"}").build();
      check(guard, ChatRequest.builder().messages(UserMessage.from("Read the remaining guide"),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, "{\"text\":\"page-" + page + "\"}")).build());
    }
    for (int repeat = 0; repeat < 4; repeat++) {
      var call = ToolExecutionRequest.builder().id("repeat-" + repeat).name("readDocumentation")
          .arguments("{\"id\":\"guide.md#0\",\"cursor\":\"same-page\"}").build();
      var request = ChatRequest.builder().messages(UserMessage.from("Read the guide"),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, "{\"text\":\"same\"}")).build();
      if (repeat < 3) check(guard, request);
      else assertThrows(ReadNoProgressException.class, () -> check(guard, request));
    }
  }

  @Test
  void persistedReadsStopAcrossHistoryTruncationAndRewordedInstructionsWithoutCaptureCredits() {
    begin();
    check(guard(), read("one", ARGS, "{\"ok\":true,\"data\":{\"enum\":[\"a\",\"b\"]}}"));
    check(guard(), read("two", "{\"propertyPath\":\"after\",\"elementType\":\"service-call\"}",
        "{\"data\":{\"enum\":[\"a\",\"b\"]},\"ok\":true}"));
    var local = guard().prepare(read("three", ARGS, "{\"ok\":true,\"data\":{\"enum\":[\"a\",\"b\"]}}"), chat, OWNER, "run");
    assertTrue(local.messages().toString().contains("Retained tool results"), local.messages().toString());
    var stopped = assertThrows(ReadNoProgressException.class, () ->
        check(guard(), read("four", ARGS, "{\"ok\":true,\"data\":{\"enum\":[\"a\",\"b\"]}}")));
    var outcome = StageOutcome.invocationFailure(new RuntimeException(stopped));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.recoveryCause().noProgress());
    assertNull(outcome.recoveryCause().captureFailure(), "Successful reads must not impersonate capture rejections.");
    assertTrue(stopped.getMessage().contains("a") && stopped.getMessage().contains("four"));
    assertEquals(0, credits.softCreditsUsed(chat));
    assertTrue(feedback.lastPatchFailure(chat, OWNER).isEmpty());
    assertTrue(graphs.get(chat).isEmpty());
    assertEquals(4, artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS).size());
    check(guard(), ChatRequest.builder().messages(UserMessage.from("Use the retained result and take another action.")).build());
    assertThrows(ReadNoProgressException.class, () -> check(guard(), read("five", ARGS,
        "{\"ok\":true,\"data\":{\"enum\":[\"a\",\"b\"]}}")));
  }

  @Test
  void distinctStalledReadsShareTheRecoveryIdentityUntilCompilerInputsChange() {
    begin();
    var after = repeat("after", ARGS);
    var before = repeat("before", "{\"elementType\":\"service-call\",\"propertyPath\":\"before\"}");
    var moreAfter = assertThrows(ReadNoProgressException.class, () ->
        check(guard(), read("new-call-id", ARGS, "{}")));
    feedback.beginScope(chat, OWNER, List.of("changed approved compiler inputs"));
    var changed = repeat("changed", ARGS);

    assertAll(
        () -> assertEquals(RecoveryAttemptLedger.evidenceIdentity(after.recoveryCause()),
            RecoveryAttemptLedger.evidenceIdentity(before.recoveryCause())),
        () -> assertEquals(RecoveryAttemptLedger.evidenceIdentity(after.recoveryCause()),
            RecoveryAttemptLedger.evidenceIdentity(moreAfter.recoveryCause())),
        () -> assertNotEquals(RecoveryAttemptLedger.evidenceIdentity(after.recoveryCause()),
            RecoveryAttemptLedger.evidenceIdentity(changed.recoveryCause())));
  }

  @Test
  void recoveryOffersRetainedResultsAndLeavesTheCaptureAndUnreadToolsAvailable() {
    begin();
    check(guard(), read("one", ARGS, "{\"schema\":\"retained-result\"}"));
    check(guard(), read("two", ARGS, "{\"schema\":\"retained-result\"}"));
    check(guard(), read("three", ARGS, "{\"schema\":\"retained-result\"}"));
    var tools = List.of("describeElementProperty", "readDocumentation", "captureGraphPatch").stream()
        .map(name -> ToolSpecification.builder().name(name).build()).toList();
    var next = ChatRequest.builder().messages(UserMessage.from("Approved plan"))
        .parameters(ChatRequestParameters.builder().toolSpecifications(tools).build()).build();
    var recovered = guard().prepare(next, chat, OWNER, "run");

    assertEquals(List.of("describeElementProperty", "readDocumentation", "captureGraphPatch"), recovered.parameters().toolSpecifications()
        .stream().map(ToolSpecification::name).toList());
    String text = assertInstanceOf(UserMessage.class, recovered.messages().getLast()).singleText();
    assertEquals(next.messages(), recovered.messages().subList(0, next.messages().size()));
    assertTrue(text.contains("\"schema\":\"retained-result\"") && text.contains("\"propertyPath\":\"after\""), text);
    feedback.beginScope(chat, OWNER, List.of("new graph inputs"));
    assertSame(next, guard().prepare(next, chat, OWNER, "run"));
  }

  @Test
  void oversizedReadsDoNotHideTheToolThatCouldRecoverThem() {
    begin();
    repeat("small", ARGS);
    check(guard(), read("large", "{\"propertyPath\":\"before\"}",
        "{\"text\":\"" + "x".repeat(33_000) + "\"}"));
    var tools = List.of("describeElementProperty", "captureGraphPatch").stream()
        .map(name -> ToolSpecification.builder().name(name).build()).toList();
    var next = ChatRequest.builder().messages(UserMessage.from("Approved plan"))
        .parameters(ChatRequestParameters.builder().toolSpecifications(tools).build()).build();

    var recovered = guard().prepare(next, chat, OWNER, "run");

    assertEquals(tools, recovered.parameters().toolSpecifications());
    String text = assertInstanceOf(UserMessage.class, recovered.messages().getLast()).singleText();
    assertTrue(text.contains("\"propertyPath\":\"after\"") && !text.contains("xxxxx"), text);
  }

  @Test
  void olderReadArtifactsCannotReplayFailedToolResultsAsEvidence() throws Exception {
    begin();
    var scope = org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        List.of("run", OWNER, feedback.inputScope(chat, OWNER), ""));
    var ids = List.of("one", "two", "three");
    var rejected = java.util.Map.of("tool", "describeElementProperty", "arguments", json.readTree(ARGS),
        "result", json.readTree("{\"ok\":false}"), "callIds", ids);
    artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active(chat),
        CompilationArtifacts.Kind.TOOL_READ_PROGRESS, "1", "tool-read-progress", "1",
        java.util.Map.of("scope", scope, "reads", java.util.Map.of("legacy-key", rejected), "seenCallIds", ids),
        List.of(), null));
    var next = ChatRequest.builder().messages(UserMessage.from("Approved plan")).build();

    var recovered = guard().prepare(next, chat, OWNER, "run");
    String text = recovered.messages().toString();
    assertTrue(text.contains("Retained tool results") && text.contains("failed-read"), text);
    assertFalse(text.contains("\"result\":{\"ok\":false}"), text);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void repeatedFailedReadsStillStopWithoutBeingReplayed(boolean encoded) throws Exception {
    begin();
    String failure = "{\"ok\":false,\"message\":\"Documentation unavailable\"}";
    String error = encoded ? json.writeValueAsString(failure) : failure;
    check(guard(), read("error-one", ARGS, error));
    check(guard(), read("error-two", ARGS, error));
    var local = guard().prepare(read("error-three", ARGS, error), chat, OWNER, "run");
    assertTrue(local.messages().toString().contains("failed-read"), local.messages().toString());
    assertThrows(ReadNoProgressException.class, () -> check(guard(), read("error-four", ARGS, error)));
    var next = ChatRequest.builder().messages(UserMessage.from("Approved plan")).build();

    var recovered = guard().prepare(next, chat, OWNER, "run");
    assertTrue(recovered.messages().toString().contains("failed-read"), recovered.messages().toString());
    assertFalse(recovered.messages().toString().contains("Documentation unavailable"), recovered.messages().toString());
  }

  @Test
  void aNewReadResultReplacesTheOldEvidenceInTheRecoveryRequest() {
    begin();
    repeat("before", ARGS);
    var updated = read("updated", ARGS, "{\"schema\":\"latest\"}");
    var request = guard().prepare(updated, chat, OWNER, "run");

    String text = assertInstanceOf(UserMessage.class, request.messages().getLast()).singleText();
    assertTrue(text.contains("\"result\":{\"schema\":\"latest\"}") && !text.contains("\"result\":{}"), text);
  }

  private ReadNoProgressException repeat(String prefix, String args) {
    check(guard(), read(prefix + "-one", args, "{}"));
    check(guard(), read(prefix + "-two", args, "{}"));
    check(guard(), read(prefix + "-three", args, "{}"));
    return assertThrows(ReadNoProgressException.class, () -> check(guard(), read(prefix + "-four", args, "{}")));
  }

  @Test
  void newResultNewPathAndNewEffectiveInputsRemainAvailable() {
    begin();
    for (int i = 0; i < 5; i++) check(guard(), read("changed-" + i, ARGS, "{\"version\":" + i + "}"));
    check(guard(), read("other-path", "{\"elementType\":\"service-call\",\"propertyPath\":\"before\"}", "{}"));
    feedback.beginScope(chat, OWNER, List.of("new native inputs"));
    check(guard(), read("fresh-one", ARGS, "{\"version\":4}"));
    check(guard(), read("fresh-two", ARGS, "{\"version\":4}"));
  }

  @Test
  void replayingPairedHistoryDoesNotCountCallsAgainOrHideALaterIdenticalRead() {
    begin();
    var first = read("first", ARGS, "{\"version\":1}");
    check(guard(), first);
    var messages = new java.util.ArrayList<>(first.messages());
    messages.addAll(read("second", ARGS, "{\"version\":2}").messages().subList(1, 3));
    var history = ChatRequest.builder().messages(messages).build();
    check(guard(), history);
    check(guard(), history);
    messages.addAll(read("third", ARGS, "{\"version\":2}").messages().subList(1, 3));
    check(guard(), ChatRequest.builder().messages(messages).build());
    messages.addAll(read("fourth", ARGS, "{\"version\":2}").messages().subList(1, 3));
    var local = guard().prepare(ChatRequest.builder().messages(messages).build(), chat, OWNER, "run");
    assertTrue(local.messages().toString().contains("Retained tool results"), local.messages().toString());
    check(guard(), ChatRequest.builder().messages(messages).build());
    messages.addAll(read("fifth", ARGS, "{\"version\":2}").messages().subList(1, 3));
    assertThrows(ReadNoProgressException.class, () -> check(guard(), ChatRequest.builder().messages(messages).build()));
  }

  @Test
  void savedKimiReadSequenceGetsALocalTurnBeforeNoProgressEscalation() throws Exception {
    begin();
    var fixture = json.readTree(getClass().getResourceAsStream("/recovery/saved-schema-read-loop.json"));
    int seen = 0;
    for (var row : fixture.path("reads")) {
      seen++;
      var captured = row.path("call");
      var call = ToolExecutionRequest.builder().id(captured.path("id").asText())
          .name(captured.path("name").asText()).arguments(captured.path("arguments").asText()).build();
      var request = ChatRequest.builder().messages(UserMessage.from("Retained producer inputs"),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, row.path("result").toString())).build();
      if (seen < 6) check(guard(), request);
      else {
        var local = guard().prepare(request, chat, OWNER, "run");
        assertTrue(local.messages().toString().contains("detailsAvailable"), local.messages().toString());
        var followup = ToolExecutionRequest.builder().id("functions.describeElementProperty:7")
            .name(call.name()).arguments(call.arguments()).build();
        var repeated = ChatRequest.builder().messages(UserMessage.from("Retained producer inputs"),
            AiMessage.from(followup), ToolExecutionResultMessage.from(followup, row.path("result").toString())).build();
        var failure = assertThrows(ReadNoProgressException.class, () -> check(guard(), repeated));
        assertTrue(failure.getMessage().contains("functions.describeElementProperty:7"), failure.getMessage());
      }
    }
    assertEquals(6, seen);
    assertEquals(0, credits.softCreditsUsed(chat));
  }

  @Test
  void aChangedAcceptedGraphSeparatesReadProgressWithoutErasingEarlierEvidence() {
    begin();
    check(guard(), read("first", ARGS, "{}"));
    check(guard(), read("second", ARGS, "{}"));
    var graph = new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph("1.0",
        new org.qubership.integration.platform.ai.plan.model.ChainSection("Accepted graph", "Preserved"), List.of(), List.of());
    graphs.put(chat, graph);
    check(guard(), read("next-first", ARGS, "{}"));
    check(guard(), read("next-second", ARGS, "{}"));
    assertEquals(graph, graphs.get(chat).orElseThrow());
    assertEquals(4, artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS).size());
  }

  @Test
  void aBatchCrossingTheThresholdGetsOneLocalTurnAndReplaysDoNotEscalate() {
    begin();
    var messages = new java.util.ArrayList<>(List.<dev.langchain4j.data.message.ChatMessage>of(UserMessage.from("Approved plan")));
    for (String id : List.of("one", "two", "three", "four")) {
      messages.addAll(read(id, ARGS, "{}").messages().subList(1, 3));
    }
    var batch = ChatRequest.builder().messages(messages).build();
    var local = guard().prepare(batch, chat, OWNER, "run");
    check(guard(), batch);
    check(guard(), batch);
    messages.addAll(read("five", ARGS, "{}").messages().subList(1, 3));

    assertAll(
        () -> assertTrue(local.messages().toString().contains("Retained tool results"), local.messages().toString()),
        () -> assertThrows(ReadNoProgressException.class, () ->
            check(guard(), ChatRequest.builder().messages(messages).build())));
  }

  @Test
  void aLegacyThresholdStateGetsLocalFeedbackBeforeANewReadCanEscalate() throws Exception {
    begin();
    var scope = org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        List.of("run", OWNER, feedback.inputScope(chat, OWNER), ""));
    var ids = List.of("one", "two", "three");
    var retained = java.util.Map.of("tool", "describeElementProperty", "arguments", json.readTree(ARGS),
        "result", json.readTree("{\"schema\":\"legacy\"}"), "callIds", ids, "successful", true);
    artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active(chat),
        CompilationArtifacts.Kind.TOOL_READ_PROGRESS, "2", "tool-read-progress", "1",
        java.util.Map.of("scope", scope, "reads", java.util.Map.of(
            org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
                List.of("describeElementProperty", json.readTree(ARGS))), retained), "seenCallIds", ids),
        List.of(), null));
    var next = ChatRequest.builder().messages(UserMessage.from("Approved plan")).build();
    var local = guard().prepare(next, chat, OWNER, "run");
    var replay = guard().prepare(next, chat, OWNER, "run");

    assertAll(
        () -> assertTrue(local.messages().toString().contains("\"schema\":\"legacy\""), local.messages().toString()),
        () -> assertEquals(local.messages(), replay.messages()),
        () -> assertEquals(2, artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS).size()),
        () -> assertThrows(ReadNoProgressException.class, () ->
            check(guard(), read("four", ARGS, "{\"schema\":\"legacy\"}"))));
  }

  @Test
  void localFeedbackPreservesMultipleUserContents() {
    begin();
    repeat("cached", ARGS);
    var contents = List.<dev.langchain4j.data.message.Content>of(dev.langchain4j.data.message.TextContent.from("Approved plan"),
        dev.langchain4j.data.message.TextContent.from("Required response fields"));
    var request = ChatRequest.builder().messages(UserMessage.builder().contents(contents).build()).build();

    var recovered = guard().prepare(request, chat, OWNER, "run");
    var user = assertInstanceOf(UserMessage.class, recovered.messages().getFirst());

    assertAll(
        () -> assertEquals(contents, user.contents()),
        () -> assertTrue(assertInstanceOf(UserMessage.class, recovered.messages().getLast()).singleText()
            .contains("Retained tool results"), recovered.messages().toString()));
  }

  @Test
  void localFeedbackIsAddedWhenTheRequestHasNoUserMessage() {
    begin();
    repeat("cached", ARGS);
    var request = ChatRequest.builder().messages(AiMessage.from("Continue the generator")).build();

    var recovered = guard().prepare(request, chat, OWNER, "run");

    assertAll(
        () -> assertEquals(request.messages().getFirst(), recovered.messages().getFirst()),
        () -> assertTrue(assertInstanceOf(UserMessage.class, recovered.messages().getLast()).singleText()
            .contains("Retained tool results"), recovered.messages().toString()));
  }

  @Test
  void schemaThreeFeedbackStateSurvivesTheSearchProgressUpgrade() throws Exception {
    begin();
    var scope = org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        List.of("run", OWNER, feedback.inputScope(chat, OWNER), ""));
    var ids = List.of("one", "two", "three");
    var key = org.qubership.integration.platform.ai.compiler.capture.CaptureProposal.digest(
        List.of("describeElementProperty", json.readTree(ARGS)));
    var retained = java.util.Map.of("tool", "describeElementProperty", "arguments", json.readTree(ARGS),
        "result", json.readTree("{\"schema\":\"accepted legacy evidence\"}"), "callIds", ids,
        "successful", true, "feedbackProvided", true);
    artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active(chat),
        CompilationArtifacts.Kind.TOOL_READ_PROGRESS, "3", "tool-read-progress", "1",
        java.util.Map.of("scope", scope, "reads", java.util.Map.of(key, retained), "seenCallIds", ids), List.of(), null));
    var next = ChatRequest.builder().messages(UserMessage.from("Approved task")).build();
    var recovered = guard().prepare(next, chat, OWNER, "run");
    var replay = guard().prepare(read("three", ARGS, "{\"schema\":\"accepted legacy evidence\"}"), chat, OWNER, "run");
    assertTrue(replay.messages().toString().contains("accepted legacy evidence"), replay.messages().toString());
    var loaded = artifacts.payload(artifacts.latest(sessions.active(chat),
        CompilationArtifacts.Kind.TOOL_READ_PROGRESS).orElseThrow(), ToolReadProgress.State.class);
    assertEquals(0, loaded.stalledSearches());
    assertEquals(ids, loaded.reads().get(key).callIds());

    assertAll(
        () -> assertTrue(recovered.messages().toString().contains("accepted legacy evidence"), recovered.messages().toString()),
        () -> assertEquals(1, artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS).size()),
        () -> assertThrows(ReadNoProgressException.class, () -> check(guard(),
            read("four", ARGS, "{\"schema\":\"accepted legacy evidence\"}"))));
    for (int attempt = 0; attempt < 3; attempt++) {
      check(guard(), search("legacy-search-" + attempt, "query " + attempt, "corpus", "section"));
      var updated = artifacts.payload(artifacts.latest(sessions.active(chat),
          CompilationArtifacts.Kind.TOOL_READ_PROGRESS).orElseThrow(), ToolReadProgress.State.class);
      assertEquals(attempt, updated.stalledSearches());
    }
  }

  private ChatRequest search(String id, String query, String checksum, String section) {
    var call = ToolExecutionRequest.builder().id(id).name("searchDocumentation")
        .arguments("{\"query\":\"" + query + "\"}").build();
    String result = "{\"checksum\":\"" + checksum + "\",\"results\":[{\"id\":\"" + section
        + "\",\"snippet\":\"excerpt for " + query + "\"}]}";
    return ChatRequest.builder().messages(UserMessage.from("Current generator task"), AiMessage.from(call),
        ToolExecutionResultMessage.from(call, result)).build();
  }

  @Test
  void rewordedSearchesForKnownSectionsGetEarlyGuidanceWithoutToolRestrictionsOrAStop() {
    begin();
    var tools = List.of("searchDocumentation", "readDocumentation", "captureGraphPatch").stream()
        .map(name -> ToolSpecification.builder().name(name).build()).toList();
    for (int attempt = 0; attempt < 8; attempt++) {
      var request = search("search-" + attempt, "different query " + attempt, "corpus", "service-call#response")
          .toBuilder().parameters(ChatRequestParameters.builder().toolSpecifications(tools).build()).build();
      var prepared = guard().prepare(request, chat, OWNER, "run");
      if (attempt < 3) assertSame(request, prepared);
      else assertTrue(prepared.messages().toString().contains("Your next step should be the best supported capture"), prepared.messages().toString());
      assertEquals(tools, prepared.parameters().toolSpecifications());
    }
  }

  @Test
  void newSectionsAndCorpusVersionsResetSearchStagnation() {
    begin();
    for (int attempt = 0; attempt < 8; attempt++) {
      var request = search("new-section-" + attempt, "query", "corpus", "section-" + attempt);
      assertSame(request, guard().prepare(request, chat, OWNER, "run"));
    }
    check(guard(), search("same-one", "one", "corpus", "section-7"));
    check(guard(), search("same-two", "two", "corpus", "section-7"));
    var changed = search("new-corpus", "query", "updated-corpus", "section-7");
    assertSame(changed, guard().prepare(changed, chat, OWNER, "run"));
    var repeat = search("after-corpus-update", "other query", "updated-corpus", "section-7");
    assertSame(repeat, guard().prepare(repeat, chat, OWNER, "run"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"readDocumentation", "captureGraphPatch"})
  void readingASectionOrAttemptingCaptureResetsSearchStagnation(String action) {
    begin();
    check(guard(), search("discover", "find", "corpus", "section"));
    check(guard(), search(action + "-one", "one", "corpus", "section"));
    check(guard(), search(action + "-two", "two", "corpus", "section"));
    var call = ToolExecutionRequest.builder().id(action).name(action).arguments("{}").build();
    var request = ChatRequest.builder().messages(UserMessage.from("Task"), AiMessage.from(call),
        ToolExecutionResultMessage.from(call, "{\"ok\":false,\"message\":\"Concrete feedback\"}")).build();
    check(guard(), request);
    var resumed = search(action + "-resumed", "resumed", "corpus", "section");
    assertSame(resumed, guard().prepare(resumed, chat, OWNER, "run"));
  }

  @Test
  void emptySearchesGetGuidanceAndReplayingTheSameHistoryDoesNotSpendProgressAgain() {
    begin();
    for (int attempt = 0; attempt < 3; attempt++) {
      var call = ToolExecutionRequest.builder().id("empty-" + attempt).name("searchDocumentation")
          .arguments("{\"query\":\"query " + attempt + "\"}").build();
      var request = ChatRequest.builder().messages(UserMessage.from("Task"), AiMessage.from(call),
          ToolExecutionResultMessage.from(call, "{\"checksum\":\"corpus\",\"results\":[]}")).build();
      var prepared = guard().prepare(request, chat, OWNER, "run");
      var replay = guard().prepare(request, chat, OWNER, "run");
      assertEquals(prepared.messages(), replay.messages());
      assertEquals(attempt >= 2, prepared.messages().toString().contains("Retained tool results"));
    }
  }

  @Test
  void recoveryGuidanceFollowsCompletedToolHistoryWithoutChangingTheGeneratorTask() {
    begin();
    check(guard(), read("one", ARGS, "{}"));
    check(guard(), read("two", ARGS, "{}"));
    check(guard(), read("three", ARGS, "{}"));
    var request = read("new-detail", "{\"propertyPath\":\"before\"}", "{\"schema\":\"current detail\"}");
    var recovered = guard().prepare(request, chat, OWNER, "run");

    assertAll(
        () -> assertEquals(request.messages(), recovered.messages().subList(0, request.messages().size())),
        () -> assertTrue(assertInstanceOf(UserMessage.class, recovered.messages().getLast()).singleText()
            .contains("Retained tool results"), recovered.messages().toString()));
  }

  @Test
  void overlappingSearchResultsKeepRecoveryVisibleAcrossNewSectionsAndTargetedReads() throws Exception {
    begin();
    check(guard(), search("discovery", "initial", "corpus", "service-call#response"));
    for (int attempt = 0; attempt < 3; attempt++) {
      var call = ToolExecutionRequest.builder().id("overlap-" + attempt).name("searchDocumentation")
          .arguments(json.writeValueAsString(java.util.Map.of("query", "reworded " + attempt))).build();
      var result = json.writeValueAsString(java.util.Map.of("checksum", "corpus", "results", List.of(
          java.util.Map.of("id", "service-call#response"), java.util.Map.of("id", "incidental-" + attempt))));
      var request = ChatRequest.builder().messages(UserMessage.from("Configure the assigned sender"),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, result)).build();
      var prepared = guard().prepare(request, chat, OWNER, "run");
      assertEquals(attempt >= 2, prepared.messages().size() > request.messages().size(), prepared.messages().toString());
    }
    for (int update = 0; update < 2; update++) {
      var refreshed = search("changed-result-" + update, "reworded 2", "corpus", "new-section-" + update);
      var prepared = guard().prepare(refreshed, chat, OWNER, "run");
      assertTrue(prepared.messages().size() > refreshed.messages().size(), prepared.messages().toString());
    }
    var detail = read("targeted-read", "{\"propertyPath\":\"before\"}", "{\"schema\":\"new detail\"}");
    var prepared = guard().prepare(detail, chat, OWNER, "run");
    String guidance = assertInstanceOf(UserMessage.class, prepared.messages().getLast()).singleText();
    assertAll(
        () -> assertEquals(detail.messages(), prepared.messages().subList(0, detail.messages().size())),
        () -> assertTrue(guidance.contains("new detail"), guidance),
        () -> assertTrue(guidance.endsWith("tool restriction.\n"), guidance));
    feedback.beginScope(chat, OWNER, List.of("changed approved inputs"));
    assertSame(detail, guard().prepare(detail, chat, OWNER, "run"));
  }

  @Test
  void unmatchedAndUnexecutedCallsAreNotProgressEvidence() {
    begin();
    var call = ToolExecutionRequest.builder().id("pending").name("describeElementProperty").arguments(ARGS).build();
    for (int i = 0; i < 5; i++) check(guard(), ChatRequest.builder().messages(UserMessage.from("Read"),
        AiMessage.from(call), ToolExecutionResultMessage.from("foreign-" + i, call.name(), "{}")).build());
    assertTrue(artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS).isEmpty());
  }
}
