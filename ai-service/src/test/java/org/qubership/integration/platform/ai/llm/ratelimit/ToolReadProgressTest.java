package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
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
  private void check(ToolReadProgress guard, ChatRequest request) { guard.check(request, chat, OWNER, "run"); }
  private void begin() { feedback.beginScope(chat, OWNER, List.of("unchanged native inputs")); }
  private ChatRequest read(String id, String args, String result) {
    var call = ToolExecutionRequest.builder().id(id).name("describeElementProperty").arguments(args).build();
    return ChatRequest.builder().messages(UserMessage.from("Current producer instruction " + id),
        AiMessage.from(call), ToolExecutionResultMessage.from(call, result)).build();
  }

  @Test
  void documentationContinuationMakesProgressButRepeatingTheSamePageStops() {
    begin();
    var guard = guard();
    for (int page = 0; page < 4; page++) {
      var call = ToolExecutionRequest.builder().id("page-" + page).name("readDocumentation")
          .arguments("{\"id\":\"guide.md#0\",\"cursor\":\"page-" + page + "\"}").build();
      check(guard, ChatRequest.builder().messages(UserMessage.from("Read the remaining guide"),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, "{\"text\":\"page-" + page + "\"}")).build());
    }
    for (int repeat = 0; repeat < 3; repeat++) {
      var call = ToolExecutionRequest.builder().id("repeat-" + repeat).name("readDocumentation")
          .arguments("{\"id\":\"guide.md#0\",\"cursor\":\"same-page\"}").build();
      var request = ChatRequest.builder().messages(UserMessage.from("Read the guide"),
          AiMessage.from(call), ToolExecutionResultMessage.from(call, "{\"text\":\"same\"}")).build();
      if (repeat < 2) check(guard, request);
      else assertThrows(ReadNoProgressException.class, () -> check(guard, request));
    }
  }

  @Test
  void persistedReadsStopAcrossHistoryTruncationAndRewordedInstructionsWithoutCaptureCredits() {
    begin();
    check(guard(), read("one", ARGS, "{\"ok\":true,\"data\":{\"enum\":[\"a\",\"b\"]}}"));
    check(guard(), read("two", "{\"propertyPath\":\"after\",\"elementType\":\"service-call\"}",
        "{\"data\":{\"enum\":[\"a\",\"b\"]},\"ok\":true}"));
    var stopped = assertThrows(ReadNoProgressException.class, () ->
        check(guard(), read("three", ARGS, "{\"ok\":true,\"data\":{\"enum\":[\"a\",\"b\"]}}")));
    var outcome = StageOutcome.invocationFailure(new RuntimeException(stopped));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.recoveryCause().noProgress());
    assertNull(outcome.recoveryCause().captureFailure(), "Successful reads must not impersonate capture rejections.");
    assertTrue(stopped.getMessage().contains("a") && stopped.getMessage().contains("three"));
    assertEquals(0, credits.softCreditsUsed(chat));
    assertTrue(feedback.lastPatchFailure(chat, OWNER).isEmpty());
    assertTrue(graphs.get(chat).isEmpty());
    assertEquals(3, artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS).size());
    check(guard(), ChatRequest.builder().messages(UserMessage.from("Use the retained result and take another action.")).build());
    assertThrows(ReadNoProgressException.class, () -> check(guard(), read("four", ARGS,
        "{\"ok\":true,\"data\":{\"enum\":[\"a\",\"b\"]}}")));
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
    assertThrows(ReadNoProgressException.class, () -> check(guard(), ChatRequest.builder().messages(messages).build()));
  }

  @Test
  void savedKimiReadSequenceReachesDiagnosisBeforeItsFortySevenRepeatedReads() throws Exception {
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
        var failure = assertThrows(ReadNoProgressException.class, () -> check(guard(), request));
        assertTrue(failure.getMessage().contains("functions.describeElementProperty:6"));
        assertTrue(failure.getMessage().contains("detailsAvailable"));
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
  void unmatchedAndUnexecutedCallsAreNotProgressEvidence() {
    begin();
    var call = ToolExecutionRequest.builder().id("pending").name("describeElementProperty").arguments(ARGS).build();
    for (int i = 0; i < 5; i++) check(guard(), ChatRequest.builder().messages(UserMessage.from("Read"),
        AiMessage.from(call), ToolExecutionResultMessage.from("foreign-" + i, call.name(), "{}")).build());
    assertTrue(artifacts.history(sessions.active(chat), CompilationArtifacts.Kind.TOOL_READ_PROGRESS).isEmpty());
  }
}
