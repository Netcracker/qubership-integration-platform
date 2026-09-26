package org.qubership.integration.platform.ai.plan.workdocument.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.CheckpointRequest;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.CheckpointSession;
import org.qubership.integration.platform.ai.plan.workdocument.checkpoint.WorkCheckpointHarness;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskModel;

/**
 * Filling checkpoint through WorkCheckpointHarness. The driver calls the shared filling interface.
 * These tests do not open a provider or a live catalog.
 */
class WorkFillingHarnessTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void fillingReachesWaitingForInputFromPreservedSource() throws Exception {
    Path storeDir = temp.resolve("store");
    Path report = temp.resolve("wait.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(storeDir);
    AtomicInteger sessionCalls = new AtomicInteger();

    int exit =
        WorkCheckpointHarness.run(
            request("om-progressive", "run-wait", report, store, false, null, 40, null, true),
            session(sessionCalls));

    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(3, exit, Files.readString(report));
    assertEquals(0, sessionCalls.get());
    assertEquals("filling", body.path("checkpoint").asText());
    assertEquals("WAITING_FOR_INPUT", body.path("outcome").asText());
    assertEquals("G2P", body.path("gate").asText());
    assertFalse(body.path("documentReference").asText().isBlank());
    assertTrue(body.path("durable").asBoolean());
    assertFalse(body.path("materialized").asBoolean());
    assertTrue(body.path("syntheticSchemaCount").asInt() > 0);
    assertEquals(
        body.path("mappingModelCalls").asInt(), body.path("mappingCallsWithOneTransfer").asInt());
    assertTrue(body.path("mappingModelCalls").asInt() >= 1);
    assertEquals(1, body.path("questions").size());
    assertEquals("FIELD_RELATIONSHIP", body.path("questions").get(0).path("choiceKind").asText());
    assertTrue(body.path("sourceHash").asText().length() >= 64);
    String trace = Files.readString(report.resolveSibling("task-trace.jsonl"));
    assertTrue(trace.contains("advance-"));
    assertTrue(trace.contains("\"type\":\"dispatch\""));
    assertTrue(trace.contains("\"type\":\"result\""));
    assertTrue(sameCommandHasDispatchAndResult(trace), trace);
    assertTrue(trace.contains("\"dependencyKeys\""));
    assertTrue(trace.contains("\"inputFingerprint\""));
    assertTrue(trace.contains("\"passageRefs\""));
    assertTrue(trace.contains("\"baseRevision\""));
    assertTrue(trace.contains("\"producedRecordIds\""));
    String stages = Files.readString(report.resolveSibling("stage-assertions.jsonl"));
    assertTrue(stages.contains("\"field\":\"sourcePaths\""));
    assertTrue(stages.contains("\"field\":\"retainedIds\""));
    assertTrue(stages.contains("\"field\":\"taskStates\""));
    JsonNode call = body.path("invocations").get(0);
    assertTrue(call.has("assignedRecordIds"));
    assertTrue(call.has("dependencyKeys"));
    assertTrue(call.has("inputFingerprint"));
    assertTrue(call.has("portHashes"));
    JsonNode waited = JSON.readTree(report.resolveSibling("final-document.json").toFile());
    assertTrue(sourcePaths(waited).contains("$.woOrderType"), sourcePaths(waited).toString());
    assertTrue(sourcePaths(waited).contains("$.orderType"));
    assertTrue(sourcePaths(waited).contains("$.executionId"));
    assertTrue(sourcePaths(waited).contains("$.executionNumber"));
    assertTrue(sourcePaths(waited).contains("$.parameters"));
    assertTrue(retainedPaths(waited).contains("$.executionId"), retainedPaths(waited).toString());
    assertTrue(retainedPaths(waited).contains("$.orderId"));
    assertTrue(retainedPaths(waited).contains("$.processInstanceId"));
    assertTrue(retainedPaths(waited).contains("$.executionNumber"));
    assertTrue(retainedPaths(waited).contains("$.taskId"));
    assertTrue(expectedOf(body, "description-names").contains("$.woOrderType"));
    assertTrue(Files.exists(report.resolveSibling("final-document.json")));
    assertTrue(Files.exists(report.resolveSibling("contract-provenance.json")));
    assertTrue(Files.exists(report.resolveSibling("stage-assertions.jsonl")));
    JsonNode question = body.path("questions").get(0);
    assertEquals("$.processInstanceId", question.path("source").path("fieldPath").asText());
    assertEquals("$.processId", question.path("target").path("fieldPath").asText());
    assertEquals(
        body.path("caseBindings").path("triggerStepId").asText(),
        question.path("source").path("stepId").asText());
    assertEquals("payload", body.path("caseBindings").path("triggerPayloadPort").asText());
    assertEquals(
        body.path("caseBindings").path("replyStepId").asText(),
        question.path("target").path("stepId").asText());
    assertEquals("request", body.path("caseBindings").path("replyRequestPort").asText());
  }

  @Test
  void resumeAcceptsTheProcessIdAnswerAndReachesReady() throws Exception {
    Path storeDir = temp.resolve("ready-store");
    Path waitingReport = temp.resolve("ready-wait.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(storeDir);
    AtomicInteger sessionCalls = new AtomicInteger();

    int waiting =
        WorkCheckpointHarness.run(
            request("om-progressive", "run-ready", waitingReport, store, false, null, 40, null, true),
            session(sessionCalls));
    JsonNode asked = JSON.readTree(waitingReport.toFile());
    assertEquals(3, waiting, Files.readString(waitingReport));
    List<String> requestRuleIds = requestRuleIds(JSON.readTree(waitingReport.resolveSibling("final-document.json").toFile()));
    assertEquals(5, requestRuleIds.size(), requestRuleIds.toString());

    Path input = temp.resolve("input.json");
    ObjectNode answer = JSON.createObjectNode();
    answer.put("inputId", "process-id-answer-1");
    answer.put("questionId", asked.path("questions").get(0).path("id").asText());
    answer.put("text", processIdAnswer());
    JSON.writeValue(input.toFile(), answer);
    Path report = temp.resolve("ready.json");
    int exit =
        WorkCheckpointHarness.run(
            request("om-progressive", "run-ready", report, store, true, input, 40, null, true),
            session(sessionCalls));

    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(0, exit, Files.readString(report));
    assertEquals(0, sessionCalls.get());
    assertEquals("READY_FOR_PRESENTATION", body.path("outcome").asText());
    assertEquals("G2P", body.path("gate").asText());
    assertTrue(body.path("responseModel").isNull());
    assertEquals("configured-model", body.path("effectiveModel").asText());
    assertEquals(0, body.path("openQuestions").asInt());
    assertEquals(0, body.path("pendingTasks").asInt());
    assertEquals(0, body.path("uncoveredRequirements").asInt());
    assertTrue(body.path("mappingModelCalls").asInt() >= 3);
    assertEquals(
        body.path("mappingModelCalls").asInt(), body.path("mappingCallsWithOneTransfer").asInt());
    assertTrue(body.path("syntheticSchemaCount").asInt() > 0);
    assertTrue(body.path("attempts").asInt() > 0);
    assertFalse(body.path("documentReference").asText().isBlank());
    assertFalse(body.path("documentRevision").asText().isBlank());
    assertTrue(body.path("durable").asBoolean());
    assertFalse(body.path("materialized").asBoolean());
    JsonNode document = JSON.readTree(report.resolveSibling("final-document.json").toFile());
    assertEquals(2, document.path("schemaVersion").asInt());
    assertEquals(3, document.path("flow").path("steps").size());
    assertFalse(labels(document).contains("Salesforce result"));
    assertTrue(document.path("sources").path(0).path("content").asText().contains("Subject = name"));
    assertFalse(document.path("sources").path(0).path("content").asText().contains("<run-id>"));
    assertTrue(targets(document).contains("$.processId"));
    for (String ruleId : requestRuleIds) {
      assertTrue(ruleIds(document).contains(ruleId), ruleId);
    }
    String trace = Files.readString(report.resolveSibling("task-trace.jsonl"));
    assertTrue(trace.contains("\"type\":\"answer\""));
    assertTrue(trace.contains("advance-"));
    assertTrue(
        body.path("invocations").size() > asked.path("invocations").size(),
        body.path("invocations").size() + " calls after resume, " + asked.path("invocations").size() + " before");
    ArtifactBlobStore reopened = WorkCheckpointHarness.openDurableStore(storeDir);
    assertTrue(reopened.list("").size() > 0);
  }

  @Test
  void secondStartWithoutResumeReportsRunExists() throws Exception {
    Path storeDir = temp.resolve("exists-store");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(storeDir);
    AtomicInteger sessionCalls = new AtomicInteger();
    int first =
        WorkCheckpointHarness.run(
            request(
                "om-progressive",
                "run-exists",
                temp.resolve("exists-first.json"),
                store,
                false,
                null,
                40,
                null,
                true),
            session(sessionCalls));
    assertEquals(3, first);
    Path report = temp.resolve("exists-second.json");
    int exit =
        WorkCheckpointHarness.run(
            request("om-progressive", "run-exists", report, store, false, null, 40, null, true),
            session(sessionCalls));
    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(2, exit, Files.readString(report));
    assertEquals("RUN_EXISTS", body.path("failureCode").asText());
    assertEquals(0, sessionCalls.get());
  }

  @Test
  void expiredDeadlineKeepsTheIntakenSource() throws Exception {
    Path report = temp.resolve("deadline.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(temp.resolve("deadline-store"));
    AtomicInteger sessionCalls = new AtomicInteger();
    int exit =
        WorkCheckpointHarness.run(
            request(
                "om-progressive",
                "run-deadline",
                report,
                store,
                false,
                null,
                40,
                Instant.EPOCH,
                true),
            session(sessionCalls));
    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(1, exit, Files.readString(report));
    assertEquals(0, sessionCalls.get());
    assertEquals("DEADLINE", body.path("failureCode").asText());
    assertTrue(body.path("durable").asBoolean());
    assertTrue(body.path("attempts").asInt() > 0);
    assertFalse(body.path("documentReference").asText().isBlank());
    JsonNode document = JSON.readTree(report.resolveSibling("final-document.json").toFile());
    assertEquals(0, document.path("flow").path("steps").size());
    assertTrue(document.path("sources").path(0).path("content").asText().contains("Subject = name"));
  }

  @Test
  void modelCallLimitPreservesTheCommittedDocument() throws Exception {
    Path report = temp.resolve("limit.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(temp.resolve("limit-store"));
    int exit =
        WorkCheckpointHarness.run(
            request("om-progressive", "run-limit", report, store, false, null, 1, null, true),
            session(new AtomicInteger()));
    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(1, exit, Files.readString(report));
    assertEquals("MODEL_CALL_LIMIT", body.path("failureCode").asText());
    assertTrue(body.path("attempts").asInt() > 0);
    assertTrue(body.path("providerDeliveryReservations").asInt() >= 1);
    assertFalse(body.path("documentReference").asText().isBlank());
    assertTrue(body.path("durable").asBoolean());
  }

  @Test
  void fillingRefusesWhenFacadesAreOff() throws Exception {
    Path report = temp.resolve("facades.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(temp.resolve("facades-store"));
    AtomicInteger sessionCalls = new AtomicInteger();
    int exit =
        WorkCheckpointHarness.run(
            request(
                "om-progressive",
                "run-facades",
                report,
                store,
                false,
                null,
                40,
                null,
                true,
                false),
            session(sessionCalls));
    JsonNode body = JSON.readTree(report.toFile());
    assertEquals(2, exit, Files.readString(report));
    assertEquals(0, sessionCalls.get());
    assertEquals("REFUSED", body.path("outcome").asText());
    assertEquals("LIVE_NOT_ENABLED", body.path("failureCode").asText());
  }

  @Test
  void configuredSessionModelIsUsedWhenSequentialFakeIsOff() throws Exception {
    Path report = temp.resolve("session-model.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(temp.resolve("session-store"));
    AtomicInteger sessionCalls = new AtomicInteger();
    int exit =
        WorkCheckpointHarness.run(
            request("om-progressive", "run-session", report, store, false, null, 40, null, false),
            session(sessionCalls));
    JsonNode body = JSON.readTree(report.toFile());
    assertTrue(sessionCalls.get() > 0);
    assertEquals(1, exit, Files.readString(report));
    assertTrue(body.path("responseModel").isNull());
    assertTrue(body.path("attempts").asInt() > 0);
    assertFalse(body.path("documentReference").asText().isBlank());
    assertTrue(body.path("durable").asBoolean());
  }

  @Test
  void controlledRecoveryRepairsTheCorruptedSourceRef() throws Exception {
    Path storeDir = temp.resolve("controlled-store");
    Path report = temp.resolve("controlled.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(storeDir);
    AtomicInteger sessionCalls = new AtomicInteger();
    int exit =
        WorkCheckpointHarness.run(
            request(
                "om-controlled-recovery",
                "run-controlled",
                report,
                store,
                false,
                null,
                40,
                null,
                true),
            session(sessionCalls));
    JsonNode body = JSON.readTree(report.toFile());
    if (exit == 3) {
      Path input = temp.resolve("controlled-input.json");
      writeAnswer(input, body);
      exit =
          WorkCheckpointHarness.run(
              request(
                  "om-controlled-recovery",
                  "run-controlled",
                  report,
                  store,
                  true,
                  input,
                  40,
                  null,
                  true),
              session(sessionCalls));
      body = JSON.readTree(report.toFile());
    }
    JsonNode document = JSON.readTree(report.resolveSibling("final-document.json").toFile());
    assertEquals(1, exit, Files.readString(report));
    assertEquals(0, sessionCalls.get());
    assertEquals("READY_FOR_PRESENTATION", body.path("outcome").asText());
    assertEquals("FIELD_ASSERTION", body.path("failureCode").asText());
    assertEquals(1, body.path("pendingTasks").asInt());
    assertTrue(openTasks(document).contains("DESCRIBE_CONTEXT PENDING"));
    assertEquals("detected-and-repaired", body.path("faultVerdict").asText());
    JsonNode fault = JSON.readTree(report.resolveSibling("fault-report.json").toFile());
    assertTrue(fault.path("injectionApplied").asBoolean());
    assertEquals("rules.sourceRef", fault.path("changedField").asText());
    assertTrue(fault.path("correctiveModelCalls").asInt() > 0);
    assertTrue(fault.path("productionDetection").asBoolean());
    assertFalse(fault.path("scriptedSemanticDetection").asBoolean());
    assertTrue(fault.path("preservedRecordsRemain").asBoolean());
    Path validationFile = Path.of(fault.path("validationInput").asText());
    Path actualFile = Path.of(fault.path("actualModelOutput").asText());
    assertTrue(Files.isRegularFile(validationFile), fault.path("validationInput").asText());
    assertTrue(Files.isRegularFile(actualFile), fault.path("actualModelOutput").asText());
    assertTrue(Files.readString(validationFile).contains("missing-step/payload"));
    assertFalse(Files.readString(actualFile).contains("missing-step/payload"));
    assertEquals("MAP_TRANSFER", fault.path("actualRepairKind").asText());
    assertTrue(fault.path("recheckedConsumers").isArray());
    String controlledTrace = Files.readString(report.resolveSibling("task-trace.jsonl"));
    assertTrue(controlledTrace.contains("\"type\":\"recovery\""));
    assertTrue(controlledTrace.contains("\"causeKey\""));
    assertTrue(controlledTrace.contains("\"repairCharges\""));
    assertTrue(controlledTrace.contains("\"preservedSiblingIds\""));
  }

  @Test
  void upstreamRecoveryDistinguishesReturnFromEarlyPrevention() throws Exception {
    Path report = temp.resolve("upstream.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(temp.resolve("upstream-store"));
    int exit =
        WorkCheckpointHarness.run(
            request(
                "om-upstream-recovery",
                "run-upstream",
                report,
                store,
                false,
                null,
                40,
                null,
                true),
            session(new AtomicInteger()));
    JsonNode body = JSON.readTree(report.toFile());
    JsonNode fault = JSON.readTree(report.resolveSibling("fault-report.json").toFile());
    assertTrue(fault.path("injectionApplied").asBoolean(), Files.readString(report));
    assertFalse(fault.path("productionDetection").asBoolean());
    assertFalse(fault.path("scriptedSemanticDetection").asBoolean());
    assertFalse(fault.path("lateDiscoveryProvenSeparately").asBoolean());
    assertTrue(
        fault
            .path("lateDiscoveryInterfaceTest")
            .asText()
            .contains("WorkFillingFaultInjectionTest"));
    String verdict = fault.path("verdict").asText();
    if ("routed-to-outline-owner".equals(verdict)) {
      assertTrue(fault.path("downstreamReturn").asBoolean());
      assertEquals("DEFINE_TRANSFERS", fault.path("actualRepairKind").asText());
    } else {
      assertEquals("early-prevention", verdict, Files.readString(report));
      assertTrue(fault.path("earlyPrevention").asBoolean());
    }
    assertEquals(1, exit, Files.readString(report));
    assertEquals("HALTED", body.path("outcome").asText(), Files.readString(report));
    JsonNode haltedDocument = JSON.readTree(report.resolveSibling("final-document.json").toFile());
    boolean missingRetainedRemains = false;
    for (JsonNode finding : haltedDocument.path("progress").path("findings")) {
      if ("MISSING_RETAINED".equals(finding.path("issueCategory").asText())) {
        missingRetainedRemains = true;
      }
    }
    assertTrue(
        missingRetainedRemains,
        "10E halt: filling leaves MISSING_RETAINED open after the outline repair. " + Files.readString(report));
    JsonNode faultBlob = JSON.readTree(store.get("harness/fault-run-upstream").orElseThrow());
    assertTrue(faultBlob.path("reportedMissing").asBoolean(), faultBlob.toString());
    assertFalse(body.path("documentReference").asText().isBlank());
  }

  @Test
  void semanticRecoveryFailsWhenThePriorityBranchStaysUndetected() throws Exception {
    Path report = temp.resolve("semantic.json");
    ArtifactBlobStore store = WorkCheckpointHarness.openDurableStore(temp.resolve("semantic-store"));
    int exit =
        WorkCheckpointHarness.run(
            request(
                "om-semantic-recovery",
                "run-semantic",
                report,
                store,
                false,
                null,
                40,
                null,
                true),
            session(new AtomicInteger()));
    JsonNode body = JSON.readTree(report.toFile());
    JsonNode fault = JSON.readTree(report.resolveSibling("fault-report.json").toFile());
    assertEquals(1, exit, Files.readString(report));
    assertEquals("UNDETECTED_SEMANTIC", body.path("failureCode").asText());
    assertEquals("undetected", body.path("faultVerdict").asText());
    assertTrue(fault.path("injectionApplied").asBoolean());
    assertEquals("rules.behavior", fault.path("changedField").asText());
    assertFalse(fault.path("productionDetection").asBoolean());
    assertFalse(fault.path("scriptedSemanticDetection").asBoolean());
    Path semanticActual = Path.of(fault.path("actualModelOutput").asText());
    Path semanticValidation = Path.of(fault.path("validationInput").asText());
    assertTrue(Files.isRegularFile(semanticActual), fault.path("actualModelOutput").asText());
    assertTrue(Files.isRegularFile(semanticValidation), fault.path("validationInput").asText());
    assertTrue(Files.readString(semanticActual).contains("low"));
    assertFalse(Files.readString(semanticValidation).contains("low"));
    assertFalse(body.path("documentReference").asText().isBlank());
    assertTrue(body.path("attempts").asInt() > 0);
  }

  @Test
  void helpAndInvalidFillingArgumentsExitWithoutAProvider() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PrintStream original = System.out;
    System.setOut(new PrintStream(buffer));
    int help;
    try {
      help = WorkCheckpointHarness.execute(new String[] {"--help"});
    } finally {
      System.setOut(original);
    }
    assertEquals(0, help);
    String usage = buffer.toString();
    assertTrue(usage.contains("filling"));
    assertTrue(usage.contains("--run-id"));
    assertTrue(usage.contains("--resume"));
    assertTrue(usage.contains("--input-file"));
    assertTrue(usage.contains("--max-model-calls"));

    Path unknown = temp.resolve("unknown.json");
    int unknownExit =
        WorkCheckpointHarness.execute(
            fillingArgs("not-a-case", "run-unknown", unknown, null, false));
    assertEquals(2, unknownExit);
    assertEquals("UNKNOWN_CASE", JSON.readTree(unknown.toFile()).path("failureCode").asText());

    Path input = temp.resolve("orphan-input.json");
    Files.writeString(input, "{\"inputId\":\"a\",\"questionId\":\"b\",\"text\":\"c\"}");
    Path orphan = temp.resolve("orphan.json");
    int orphanExit =
        WorkCheckpointHarness.execute(fillingArgs("om-progressive", "run-orphan", orphan, input, false));
    assertEquals(2, orphanExit);
    assertEquals("INVALID_ARGUMENT", JSON.readTree(orphan.toFile()).path("failureCode").asText());

    Path missing = temp.resolve("missing-run.json");
    int missingExit =
        WorkCheckpointHarness.execute(
            new String[] {
              "--checkpoint",
              "filling",
              "--case",
              "om-progressive",
              "--report",
              missing.toString(),
              "--fixtures",
              fixtureRoot().toString()
            });
    assertEquals(2, missingExit);
    assertEquals("INVALID_ARGUMENT", JSON.readTree(missing.toFile()).path("failureCode").asText());

    Path relative = Path.of("filling-relative-" + System.nanoTime() + ".json");
    int relativeExit =
        WorkCheckpointHarness.execute(
            new String[] {
              "--checkpoint",
              "filling",
              "--case",
              "om-progressive",
              "--run-id",
              "run-relative",
              "--report",
              relative.toString(),
              "--fixtures",
              fixtureRoot().toString()
            });
    assertEquals(2, relativeExit);
    Path written = Path.of("").toAbsolutePath().resolve(relative);
    assertEquals("INVALID_ARGUMENT", JSON.readTree(written.toFile()).path("failureCode").asText());
    Files.deleteIfExists(written);
  }

  @Test
  void fillingHelpAndMissingRunIdDoNotStartMaven() throws Exception {
    Path bin = temp.resolve("filling-bin");
    Files.createDirectories(bin);
    Path touched = temp.resolve("filling-tool-ran");
    Files.writeString(bin.resolve("curl"), "#!/bin/sh\ntouch '" + touched + "'\nexit 0\n");
    Files.writeString(bin.resolve("mvn"), "#!/bin/sh\ntouch '" + touched + "'\nexit 0\n");
    Files.writeString(bin.resolve("mvnw"), "#!/bin/sh\ntouch '" + touched + "'\nexit 0\n");
    bin.resolve("curl").toFile().setExecutable(true);
    bin.resolve("mvn").toFile().setExecutable(true);
    bin.resolve("mvnw").toFile().setExecutable(true);
    String script = Files.readString(script());
    assertFalse(script.contains("LLM_CHAT_MODEL="));

    ProcessBuilder help =
        new ProcessBuilder("bash", script().toString(), "--help");
    help.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    help.environment().remove("WORK_CHECKPOINT_LIVE");
    help.redirectErrorStream(true);
    Process helpProcess = help.start();
    String helpOutput = new String(helpProcess.getInputStream().readAllBytes());
    assertEquals(0, helpProcess.waitFor(), helpOutput);
    assertTrue(helpOutput.contains("filling"));
    assertTrue(helpOutput.contains("--run-id"));
    assertFalse(Files.exists(touched), helpOutput);

    Path report = temp.resolve("shell-missing-run.json");
    ProcessBuilder missing =
        new ProcessBuilder(
            "bash",
            script().toString(),
            "--checkpoint",
            "filling",
            "--case",
            "om-progressive",
            "--report",
            report.toString());
    missing.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    missing.environment().remove("WORK_CHECKPOINT_LIVE");
    missing.redirectErrorStream(true);
    Process missingProcess = missing.start();
    String missingOutput = new String(missingProcess.getInputStream().readAllBytes());
    assertEquals(2, missingProcess.waitFor(), missingOutput);
    assertFalse(Files.exists(touched), missingOutput);
    assertEquals("INVALID_ARGUMENT", JSON.readTree(report.toFile()).path("failureCode").asText());
  }

  @Test
  void fillingDriverDoesNotCallMappingDocumentOrSchemas() throws Exception {
    String source = Files.readString(harnessSource("FillingCheckpoint.java"));
    assertFalse(source.contains("mappingDocument("));
    assertFalse(source.contains("mappingSchemas("));
  }

  private static CheckpointRequest request(
      String caseId,
      String runId,
      Path report,
      ArtifactBlobStore store,
      boolean resume,
      Path inputFile,
      int maxModelCalls,
      Instant deadline,
      boolean sequentialFake) {
    return request(
        caseId, runId, report, store, resume, inputFile, maxModelCalls, deadline, sequentialFake, true);
  }

  private static CheckpointRequest request(
      String caseId,
      String runId,
      Path report,
      ArtifactBlobStore store,
      boolean resume,
      Path inputFile,
      int maxModelCalls,
      Instant deadline,
      boolean sequentialFake,
      boolean invokeFacades) {
    return new CheckpointRequest(
        "filling",
        caseId,
        report,
        fixtureRoot(),
        invokeFacades,
        store,
        runId,
        resume,
        inputFile,
        maxModelCalls,
        deadline,
        sequentialFake);
  }

  private static void writeAnswer(Path input, JsonNode asked) throws Exception {
    ObjectNode answer = JSON.createObjectNode();
    answer.put("inputId", "process-id-answer-1");
    answer.put("questionId", asked.path("questions").get(0).path("id").asText());
    answer.put("text", processIdAnswer());
    JSON.writeValue(input.toFile(), answer);
  }

  private static String processIdAnswer() throws Exception {
    return Files.readString(
            repoRoot()
                .resolve(
                    ".scratch/progressive-chain-work-document/after-10-document-filling/fixtures/process-id-answer.txt"))
        .trim();
  }

  private static List<String> requestRuleIds(JsonNode document) {
    List<String> ids = new ArrayList<>();
    for (String target : List.of("$.Subject", "$.Priority", "$.Status", "$.ActivityDate", "$.Description")) {
      for (JsonNode step : document.path("flow").path("steps")) {
        for (JsonNode transfer : step.path("data").path("transfers")) {
          for (JsonNode rule : transfer.path("rules")) {
            if (target.equals(rule.path("target").path("fieldPath").asText())) {
              ids.add(rule.path("id").asText());
            }
          }
        }
      }
    }
    return ids;
  }

  private static List<String> ruleIds(JsonNode document) {
    List<String> ids = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          ids.add(rule.path("id").asText());
        }
      }
    }
    return ids;
  }

  private static List<String> targets(JsonNode document) {
    List<String> paths = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          paths.add(rule.path("target").path("fieldPath").asText());
        }
      }
    }
    return paths;
  }

  private static String openTasks(JsonNode document) {
    StringBuilder text = new StringBuilder();
    for (JsonNode task : document.path("progress").path("tasks")) {
      if (!"ACCEPTED".equals(task.path("state").asText())) {
        text.append(task.path("taskKey").asText())
            .append(' ')
            .append(task.path("kind").asText())
            .append(' ')
            .append(task.path("state").asText())
            .append('\n');
      }
    }
    return text.toString();
  }

  private static boolean sameCommandHasDispatchAndResult(String trace) throws Exception {
    java.util.Map<String, java.util.Set<String>> types = new java.util.LinkedHashMap<>();
    int start = 0;
    while (start < trace.length()) {
      int end = trace.indexOf('\n', start);
      if (end < 0) {
        end = trace.length();
      }
      String line = trace.substring(start, end);
      start = end + 1;
      if (line.isBlank()) {
        continue;
      }
      JsonNode event = JSON.readTree(line);
      types
          .computeIfAbsent(event.path("commandId").asText(), key -> new java.util.LinkedHashSet<>())
          .add(event.path("type").asText());
    }
    for (java.util.Set<String> seen : types.values()) {
      if (seen.contains("dispatch") && seen.contains("result")) {
        return true;
      }
    }
    return false;
  }

  private static String expectedOf(JsonNode report, String id) {
    for (JsonNode row : report.path("requirementChecklist")) {
      if (id.equals(row.path("id").asText())) {
        return row.path("expected").asText();
      }
    }
    return "";
  }

  private static List<String> sourcePaths(JsonNode document) {
    List<String> paths = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode transfer : step.path("data").path("transfers")) {
        for (JsonNode rule : transfer.path("rules")) {
          for (JsonNode source : rule.path("sources")) {
            String path = source.path("fieldPath").asText();
            if (!path.isBlank()) {
              paths.add(path);
            }
          }
        }
      }
    }
    return paths;
  }

  private static List<String> retainedPaths(JsonNode document) {
    List<String> paths = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      for (JsonNode value : step.path("data").path("retainedValues")) {
        String path = value.path("source").path("fieldPath").asText();
        if (!path.isBlank()) {
          paths.add(path);
        }
      }
    }
    return paths;
  }

  private static List<String> labels(JsonNode document) {
    List<String> names = new ArrayList<>();
    for (JsonNode step : document.path("flow").path("steps")) {
      names.add(step.path("label").asText());
    }
    return names;
  }

  private static String[] fillingArgs(
      String caseId, String runId, Path report, Path inputFile, boolean resume) {
    List<String> args = new ArrayList<>();
    args.add("--checkpoint");
    args.add("filling");
    args.add("--case");
    args.add(caseId);
    args.add("--run-id");
    args.add(runId);
    args.add("--report");
    args.add(report.toString());
    args.add("--fixtures");
    args.add(fixtureRoot().toString());
    if (resume) {
      args.add("--resume");
    }
    if (inputFile != null) {
      args.add("--input-file");
      args.add(inputFile.toString());
    }
    return args.toArray(String[]::new);
  }

  private static Path script() {
    return repoRoot().resolve("ai-service/e2e/product-pipeline/run-work-document-checkpoint.sh");
  }

  private static Path harnessSource(String name) {
    return repoRoot()
        .resolve("ai-service/src/test/java/org/qubership/integration/platform/ai/plan/workdocument/checkpoint")
        .resolve(name);
  }

  private static Path repoRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    return cursor.getFileName().toString().equals("ai-service") ? cursor.getParent() : cursor;
  }

  private static CheckpointSession session(AtomicInteger sessionCalls) {
    WorkTaskModel model =
        task -> {
          sessionCalls.incrementAndGet();
          throw new IllegalStateException("The offline filling path called the session model.");
        };
    CatalogResolution catalog =
        new CatalogResolution() {
          @Override
          public CatalogLookup lookup(String operationHint, String pinnedVersion) {
            throw new IllegalStateException("The offline filling path called the session catalog.");
          }

          @Override
          public ApiHubHit searchApiHub(String interactionId, String operationHint, String pinnedVersion) {
            throw new IllegalStateException("The offline filling path called the session catalog.");
          }

          @Override
          public void importContract(ApiHubHit hit) {
            throw new IllegalStateException("The offline filling path called the session catalog.");
          }
        };
    return new CheckpointSession("configured-provider", "configured-model", false, model, catalog);
  }

  private static Path fixtureRoot() {
    return repoRoot().resolve("ai-service/e2e/product-pipeline/fixtures/work-checkpoints");
  }
}
