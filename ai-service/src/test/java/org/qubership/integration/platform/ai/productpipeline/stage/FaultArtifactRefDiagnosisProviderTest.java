package org.qubership.integration.platform.ai.productpipeline.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModel;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative.RecoveryStatus;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecisionValidator;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class FaultArtifactRefDiagnosisProviderTest {
  @Inject ObjectMapper json;
  @Inject FailureNarrativeAgent agent;
  @InjectMock RateLimitChatModel provider;

  @ParameterizedTest
  @ValueSource(strings = {"nested-attempt", "foreign", "missing-fields", "exhausted", "invalid-action", "invalid-question"})
  void invalidFaultReferenceReachesTheNextProviderRequestWithoutChangingAuthorityOrRetryCredit(String mode) throws Exception {
    String runId = "fault-reference-" + UUID.randomUUID();
    var producer = new Reference(Kind.REQUIREMENT_DRAFT, "current-producer", "producer-" + "x".repeat(2_300));
    var attempt = new Reference(Kind.REQUIREMENT_CAPTURE_ATTEMPT, "nested-attempt", "attempt-hash");
    var feedbackRef = new Reference(Kind.CAPTURE_ATTEMPT_FEEDBACK, "nested-feedback", "feedback-hash");
    var foreign = new Reference(Kind.REQUIREMENT_DRAFT, "foreign-producer", "foreign-hash");
    Reference rejected = "nested-attempt".equals(mode) ? attempt : foreign;
    var feedback = new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, "The original native refusal.")
        .withEvidenceReference(feedbackRef);
    var finding = new SemanticFinding("UNBOUND_REQUIRED_LITERAL", "required literal binding", "native-finding",
        "owned-output", "service-call", List.of(), List.of(), List.of(), "", Map.of(), List.of(),
        "Original native field /draft/facts/owned-output/elementIntent/requestedProperties; retain all exactValues.");
    var evidence = new RecoveryEvidence(1, "native-failure", "CONTRACT_SHAPE", "requirement-discovery",
        "requirement-discovery", null, null, List.of(producer), List.of(finding), null, List.of(), List.of(),
        List.of(), List.of(), false, new CaptureFailureEvidence(feedback, null, attempt, List.of()));
    var context = new RecoveryContext(evidence, null, Map.of("producerStageId", "requirement-discovery"), "en");
    var invalid = switch (mode) {
      case "missing-fields" -> new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, null, List.of(),
          RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Repair the generated output. " + "details ".repeat(400));
      case "invalid-action" -> new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, producer,
          List.of(evidence.failureId()), RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Repair the generated output.");
      case "invalid-question" -> new RecoveryDecision(RecoveryCauseClass.BRIEF_DEFECT, producer,
          List.of(evidence.failureId()), RecoveryAction.ASK_USER, List.of(),
          "Which design-execution output should change?", "An unresolved choice needs clarification.");
      default -> decision(rejected, evidence);
    };
    String nativeDiagnostic = RecoveryDecisionValidator.validate(invalid, context).findings().getFirst();
    assertFalse(RecoveryDecisionValidator.validate(invalid, context).accepted());
    var requests = new ArrayList<JsonNode>();
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      JsonNode schema = json.valueToTree(JsonSchemaElementUtils.toMap(
          request.parameters().responseFormat().jsonSchema().rootElement()));
      var fault = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
          .getSchema(schema.path("properties").path("faultArtifactRef"));
      assertTrue(fault.validate(json.valueToTree(producer)).isEmpty());
      assertFalse(fault.validate(json.valueToTree(rejected)).isEmpty());
      String prompt = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
          .map(UserMessage::singleText).filter(text -> text.startsWith("You diagnose create-chain recovery")).findFirst().orElseThrow();
      int start = prompt.indexOf("\n\nContext: ") + "\n\nContext: ".length();
      int end = prompt.indexOf("\n\nReturn RecoveryDecision only.", start);
      assertTrue(start >= "\n\nContext: ".length() && end > start, prompt);
      requests.add(json.readTree(prompt.substring(start, end)));
      assertTrue(requests.size() <= 2, "The existing diagnosis retry bound must remain two calls.");
      RecoveryDecision reply = requests.size() == 1 || "exhausted".equals(mode) ? invalid : decision(producer, evidence);
      return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(reply))).build();
    }).when(provider).chat(any(ChatRequest.class));

    var runtime = diagnosisRuntime(runId);
    var executor = runtime.executor();
    var accepted = executor.acceptedRecoveryDecision(runId, context);

    assertEquals(2, requests.size());
    JsonNode firstEvidence = requests.getFirst().path("evidence");
    JsonNode nextEvidence = requests.get(1).path("evidence");
    assertEquals(json.valueToTree(evidence.captureFailure()), firstEvidence.path("captureFailure"));
    assertEquals(firstEvidence.path("captureFailure"), nextEvidence.path("captureFailure"));
    assertEquals(firstEvidence.path("rejectedArtifactRefs"), nextEvidence.path("rejectedArtifactRefs"));
    assertEquals(json.valueToTree(finding), nextEvidence.path("findings").get(0));
    assertEquals("INVALID_RECOVERY_DECISION", nextEvidence.path("findings").get(1).path("code").asText());
    JsonNode rejectedReply = json.readTree(nextEvidence.path("findings").get(1).path("rawValidatorJson").asText());
    assertEquals("REJECTED", rejectedReply.path("status").asText());
    assertEquals(json.valueToTree(invalid), rejectedReply.path("rejectedDecision"));
    assertEquals(json.valueToTree(RecoveryDecisionValidator.validate(invalid, context).findings()),
        rejectedReply.path("validationFindings"));
    assertEquals(firstEvidence.path("failureId"), nextEvidence.path("failureId"));
    assertTrue(requests.get(1).path("evidenceRefIds").toString().contains(producer.contentHash()));
    assertFalse(requests.get(1).path("evidenceRefIds").toString().contains(foreign.artifactId()));
    if (List.of("nested-attempt", "foreign", "exhausted").contains(mode)) {
      assertTrue(nativeDiagnostic.contains("Rejected value: " + json.valueToTree(rejected)));
      assertTrue(nativeDiagnostic.contains(json.valueToTree(producer).toString()),
          "The complete current reference must reach the provider.");
      assertTrue(nativeDiagnostic.contains("Action and strategy constraints still apply"));
    }
    if ("exhausted".equals(mode)) {
      assertNull(accepted.decision());
      assertEquals(RecoveryStatus.INVALID_REPLY, accepted.diagnosisStatus());
      assertEquals(2, accepted.evidence().findings().stream()
          .filter(item -> "INVALID_RECOVERY_DECISION".equals(item.code())).count());
      JsonNode retainedReply = json.readTree(accepted.evidence().findings().getLast().rawValidatorJson());
      assertEquals(json.valueToTree(invalid), retainedReply.path("rejectedDecision"));
    } else {
      var expectedMachineDecision = json.valueToTree(decision(producer, evidence));
      var actualMachineDecision = json.valueToTree(accepted.decision());
      ((com.fasterxml.jackson.databind.node.ObjectNode) expectedMachineDecision).remove("userSummary");
      ((com.fasterxml.jackson.databind.node.ObjectNode) actualMachineDecision).remove("userSummary");
      assertEquals(expectedMachineDecision, actualMachineDecision);
      assertEquals(RecoveryStatus.READY, accepted.diagnosisStatus());
    }
    assertEquals(runtime.before(), runtime.runs().load(runId).orElseThrow(),
        "Diagnosis must not replace accepted work or source input.");
    assertEquals(accepted, executor.acceptedRecoveryDecision(runId, context));
    assertEquals(2, requests.size(), "Reentry must reuse the saved result without new diagnosis credit.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"en-US", "ru-RU"})
  void modelSummaryIsAuditOnlyWhileTheRegisteredRecoveryRequestKeepsItsMachineDecision(String locale) throws Exception {
    String runId = "presentation-" + UUID.randomUUID();
    var producer = new Reference(Kind.CHAIN_PLAN_GRAPH, "current-graph", "current-graph-hash");
    var evidence = new RecoveryEvidence(1, "presentation-failure", "CONTRACT_SHAPE", "design-execution",
        "design-execution", null, null, List.of(producer), List.of(), null, List.of(), List.of());
    var context = new RecoveryContext(evidence, null, Map.of("producerStageId", "design-execution"), locale);
    var reply = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, producer, List.of(evidence.failureId()),
        RecoveryAction.REGENERATE_ARTIFACT, List.of(), null,
        "Repair design-execution using private-source-literal, /private/source/path, and ref-123.");
    assertEquals(List.of("userSummary must not contain stage identifiers."),
        RecoveryDecisionValidator.validate(reply, context).findings());
    var requests = new ArrayList<ChatRequest>();
    doAnswer(call -> {
      ChatRequest request = call.getArgument(0);
      requests.add(request);
      String prompt = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
          .map(UserMessage::singleText).filter(text -> text.startsWith("You diagnose create-chain recovery"))
          .findFirst().orElseThrow();
      assertTrue(prompt.contains("\"responseLocale\":\"" + locale + "\""));
      assertTrue(prompt.contains(producer.contentHash()));
      return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(reply))).build();
    }).when(provider).chat(any(ChatRequest.class));
    var runtime = diagnosisRuntime(runId);
    var accepted = runtime.executor().acceptedRecoveryDecision(runId, context);
    assertEquals(1, requests.size(), "Presentation must not spend the existing correction call.");
    assertEquals(RecoveryStatus.READY, accepted.diagnosisStatus());
    var expectedMachine = json.valueToTree(reply);
    var actualMachine = json.valueToTree(accepted.decision());
    ((com.fasterxml.jackson.databind.node.ObjectNode) expectedMachine).remove("userSummary");
    ((com.fasterxml.jackson.databind.node.ObjectNode) actualMachine).remove("userSummary");
    assertEquals(expectedMachine, actualMachine);
    assertEquals("The generated result needs correction. Recovery will continue with the saved requirements.",
        accepted.decision().userSummary());
    assertTrue(RecoveryDecisionValidator.validate(accepted.decision(), context).accepted());
    var audit = json.readTree(accepted.evidence().findings().getLast().rawValidatorJson());
    assertEquals("PRESENTATION_ONLY", audit.path("status").asText());
    assertEquals(json.valueToTree(reply), audit.path("rawDecision"));
    assertEquals(json.valueToTree(accepted.decision()), audit.path("presentedDecision"));
    assertEquals(accepted, runtime.executor().acceptedRecoveryDecision(runId, context));
    assertEquals(1, requests.size());
    assertEquals(runtime.before(), runtime.runs().load(runId).orElseThrow());
  }

  private DiagnosisRuntime diagnosisRuntime(String runId) {
    var blobs = new InMemoryArtifactBlobStore();
    var clock = Clock.systemUTC();
    var runs = new ProductPipelineRunStore(blobs, json, clock);
    var artifacts = new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, json, clock));
    var support = CreateChainTestOrchestrator.supportBuilder(runs, artifacts, new StageCapabilityRegistry(List.of()), clock)
        .failureNarrative(new FailureNarrative(agent)).build();
    var output = new ArtifactTypeRef("requirement-draft", 1);
    var profile = new ProductPipelineProfile(1, "fault-reference-diagnostic", "1", List.of(),
        List.of(new ProfileStage("requirement-discovery", "unused-producer", List.of(), List.of(output), null, null,
            new RetryPolicy(0, 0L))), new TerminalPolicy("requirement-discovery", "COMPLETE"), List.of());
    var manifest = new RunManifest(runId, null, List.of(), "product", profile.profileId(), profile.profileVersion(),
        "profile-hash", "baseline", "baseline-hash", List.of(), "closure-hash", null, "2026.1", List.of(output), null);
    var before = support.bootstrap(new StartOrResumeCommand(runId, runId, profile, manifest), "diagnostic-test");
    return new DiagnosisRuntime(assertInstanceOf(ProductPipelineStageExecutor.class, support.stageExecutor()), runs, before);
  }

  private record DiagnosisRuntime(ProductPipelineStageExecutor executor, ProductPipelineRunStore runs,
                                  ProductPipelineRunDocument before) {}

  private static RecoveryDecision decision(Reference fault, RecoveryEvidence evidence) {
    return new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, fault, List.of(evidence.failureId()),
        RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Repair the generated output from the saved requirements.");
  }
}
