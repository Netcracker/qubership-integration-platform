package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitStreamingChatModel;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementAnalysisCapability;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeSidecarApi;
import org.qubership.integration.platform.ai.productpipeline.knowledge.DefaultKnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.SidecarKnowledgeClient;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Exercises the real analysis tool, stateless retry, and native accepted-brief continuation. */
@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class ApprovedMappingFeedbackProviderTest {
  @Inject ObjectMapper json;
  @Inject RequirementAnalysisCapability analysis;
  @Inject CaptureAttemptFeedbackStore feedback;
  @Inject ConversationService conversations;
  @Inject CompilationArtifacts artifacts;
  @Inject CompilationSessions sessions;
  @Inject AppConfig config;
  @InjectMock RateLimitStreamingChatModel provider;
  @InjectMock SidecarKnowledgeClient knowledge;
  @InjectMock DefaultKnowledgeContextProvider knowledgeContext;
  @InjectMock org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaLoader schemas;

  @ParameterizedTest
  @CsvSource({"true,false", "false,false", "true,true", "false,true"})
  void nativeFirstNextRequestGetsExactMismatchThenProjectsOrPreservesNativeFalse(
      boolean corrected, boolean large) throws Exception {
    String chat = "approved-mapping-feedback-" + UUID.randomUUID();
    String source = "Receive POST /parcels and deliver the retained original parcel as Receipt.";
    var input = conversations.archive().beginInput(chat, source, null);
    conversations.archive().resolveInput(chat, input, source);
    var approved = typedParcelDraft();
    when(schemas.load(anyString())).thenAnswer(call -> {
      String operation = call.getArgument(0);
      String body = operation.equals("op-ingress")
          ? "{\"type\":\"object\",\"properties\":{\"parcel\":{\"type\":\"string\"}}}"
          : "{\"type\":\"object\",\"properties\":{\"Receipt\":{\"type\":\"string\"}}}";
      return new org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps(operation,
          Map.of("application/json", json.readTree(body)), Map.of());
    });
    var approvedReference = artifacts.append(new AppendCommand(sessions.active(chat),
        CompilationArtifacts.Kind.REQUIREMENT_DRAFT, "2", "approved-mapping-fixture", "1",
        approved, List.of(), null)).reference();
    var fixture = FakeKnowledgeClient.defaultFixture();
    when(knowledgeContext.forConversation(anyString())).thenReturn(fixture.context());
    when(knowledge.context(any(), any())).thenAnswer(call -> fixture.context(call.getArgument(0), call.getArgument(1)));
    when(knowledge.documentationManifest()).thenReturn(
        new KnowledgeSidecarApi.DocumentationManifestDto(1, "sha256:" + "d".repeat(64), 1, 1));
    when(knowledge.searchDocumentation(any(), any(), any(), anyInt())).thenReturn(
        new KnowledgeSidecarApi.DocumentationSearchDto("sha256:" + "d".repeat(64), List.of()));
    var originalSource = json.valueToTree(conversations.archive().read(chat));
    var limits = Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun());
    String original = """
        {"capture":{"goal":"Deliver parcels","summary":"Preserve the original parcel receipt",
          "inputs":["POST /parcels"],"assumptions":[],"citations":[],"sourceOmissions":[],
          "mappingIntents":[{"sourceRef":"ingress","targetRef":"delivery","rules":[
            {"sourcePath":"$.parcel","targetPath":"$.Receipt"}]}]}}
        """;
    String detailTail = "retained-proposed-behavior-tail";
    String bad = large ? original.replace("\"targetPath\":\"$.Receipt\"",
        "\"targetPath\":\"$.Receipt\",\"expression\":\"" + "\u00f8".repeat(5000) + detailTail + "\"") : original;
    String good = """
        {"capture":{"goal":"Deliver parcels","summary":"Preserve the original parcel receipt",
          "inputs":["POST /parcels"],"assumptions":[],"citations":[],"sourceOmissions":[]}}
        """;
    String next = corrected ? good : bad.replace("$.parcel", "$.anotherParcel");
    var expectedNativeProposal = json.valueToTree(
        json.treeToValue(json.readTree(bad).path("capture"), RequirementAnalysisCapture.class));
    var observed = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    var script = new ScriptedStreamingChatModel(
        Turn.tool("propose-current-response", request -> {
          assertTrue(request.messages().toString().contains(source));
          var spec = request.parameters().toolSpecifications().stream()
              .filter(tool -> "captureRequirementBrief".equals(tool.name())).findFirst().orElseThrow();
          assertTrue(spec.description().contains("server projects those approved rules"));
        }, "captureRequirementBrief", bad),
        Turn.tool("omit-authored-recapture", request -> {
          String actual = request.messages().toString();
          var refusal = feedback.lastPlanFailure(chat).orElseThrow();
          assertTrue(actual.contains(refusal.summary()), actual);
          assertTrue(actual.contains("Read the retained original parcel from ingress"), actual);
          assertTrue(actual.contains("proposedRule"), actual);
          assertTrue(actual.contains("approvedRules"), actual);
          assertTrue(actual.contains("omit mappingIntents"), actual);
          assertTrue(actual.contains(source), actual);
          if (large) {
            assertTrue(actual.contains("\u00f8".repeat(5000) + detailTail), actual);
          }
          assertTrue(refusal.outerAllowed());
          assertEquals("CORRECTABLE", refusal.failureClass().name());
          var nativeRevision = artifacts.get(sessions.active(chat), refusal.evidenceReference()).orElseThrow();
          assertEquals(expectedNativeProposal, nativeRevision.payload().at("/proposal/arguments"));
          assertEquals(refusal.presentationDetails(), nativeRevision.payload().at("/feedback/presentationDetails").asText());
          observed.set(json.valueToTree(Map.of("requestMessages", actual, "nativeFeedback", refusal,
              "nativeObservation", nativeRevision, "rawOriginalToolArguments", bad, "rawNextToolArguments", next,
              "source", originalSource, "approvedDraftReference", approvedReference)));
          assertEquals(approved, json.convertValue(
              artifacts.get(sessions.active(chat), approvedReference).orElseThrow().payload(), RequirementDraft.class));
        }, "captureRequirementBrief", next));
    doAnswer(call -> {
      script.chat((ChatRequest) call.getArgument(0), (StreamingChatResponseHandler) call.getArgument(1));
      return null;
    }).when(provider).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var signals = analysis.execute(new StageExecutionContext("run-" + chat, chat, "requirement-analysis",
        "execution", "attempt", null, null, List.of(approvedReference), Map.of("approvedDraft", approved)))
        .collect().asList().await().atMost(Duration.ofSeconds(30));
    script.assertComplete();
    assertEquals(2, script.requests().size());
    var outcome = signals.stream().filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast).findFirst().orElseThrow().outcome();
    assertEquals(script.requests().getFirst().parameters().toolSpecifications(),
        script.requests().getLast().parameters().toolSpecifications());
    assertEquals(corrected ? StageOutcomeClass.SUCCEEDED : StageOutcomeClass.CONTRACT_FAILURE,
        outcome.outcomeClass(), outcome.toString());
    assertEquals(originalSource, json.valueToTree(conversations.archive().read(chat)));
    assertEquals(limits, Map.of("requests", config.llm().maxRequestsPerRun(), "tokens", config.llm().maxTokensPerRun(),
        "time", config.llm().maxActiveTimePerRun()));
    var receipt = (com.fasterxml.jackson.databind.node.ObjectNode) observed.get();
    if (corrected) {
      RequirementBrief brief = (RequirementBrief) outcome.candidates().getFirst().payload();
      assertEquals(approvedReference.artifactId(), brief.approvedDraftReference());
      var expectedRules = RequirementBriefProjector.authoredMappings(approved).getFirst().rules().stream()
          .map(rule -> rule.withSchemaPins(Map.of(
              "source", "d54b534da90e548785e3d8f693215888913bff38b7104392d5df2a14df1dacc2", "target", "5b88e4ed0e39942168c6bf6d34bb221318305e43f648ae1b1e26822351d0958e"))).toList();
      assertEquals(expectedRules, brief.mappingIntents().getFirst().rules());
      receipt.set("acceptedBrief", json.valueToTree(brief));
    } else {
      var terminal = outcome.recoveryCause().captureFailure().feedback();
      assertFalse(terminal.outerAllowed());
      assertEquals("IDENTICAL_SPAM", terminal.failureClass().name());
      assertTrue(outcome.candidates().isEmpty());
      assertTrue(terminal.presentationDetails().contains("$.anotherParcel"));
      receipt.set("terminalNativeFeedback", json.valueToTree(terminal));
    }
    receipt.put("continuedOutcome", outcome.outcomeClass().name());
    receipt.put("actualProviderRequests", script.requests().size());
    receipt.put("realExternalProviderCalled", false);
    receipt.put("fullChainAcceptanceClaimed", false);
    Path output = Path.of("target", "approved-mapping-proof");
    Files.createDirectories(output);
    json.writerWithDefaultPrettyPrinter().writeValue(output.resolve(corrected
        ? (large ? "provider-next-request-large.json" : "provider-next-request.json")
        : (large ? "provider-native-false-large.json" : "provider-native-false.json")).toFile(), receipt);
  }
  private static RequirementDraft typedParcelDraft() {
    var base = ApprovedMappingCanonicalTest.draft(null, "Receipt", "Read the retained original parcel from ingress")
        .authoredDraft();
    var facts = base.facts().stream().map(fact -> fact.fieldMapping() == null ? fact
        : new RequirementCaptureInput.FactInput(fact.sourceFactId(), fact.interactionIds(), fact.kind(), fact.polarity(),
            "Read the retained original parcel from ingress", new RequirementCaptureInput.FieldMappingInput(
                "ingress", null, "delivery", "$.Receipt", null,
                new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingValue.Copy(
                    new org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource.Message("ingress",
                        org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort.OUTPUT, "$.parcel"))))).toList();
    var input = new RequirementCaptureInput.DraftInput(base.flow(), facts,
        List.of(new RequirementCaptureInput.CapabilityInput("ingress", "http-trigger", RequirementCaptureInput.HttpMode.CATALOG,
            RequirementCaptureInput.HttpMethod.POST, null, null, null, null)), base.openQuestions(), base.settings());
    var bindings = List.of(parcelBinding("ingress", "/parcels"), parcelBinding("delivery", "/receipts"));
    return RequirementCaptureProjection.toDraft(RequirementCaptureProjection.withCopyPolicies(input), null,
        bindings, "1", null);
  }

  private static org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint parcelBinding(
      String id, String path) {
    return new org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint("3", id, id,
        "POST " + path, "system", "group", "spec", "op-" + id, "HTTP", "POST", path, "1",
        java.time.Instant.EPOCH, "catalog-evidence");
  }

}
