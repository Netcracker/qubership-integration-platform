package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecisionValidator;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RecoveryDecisionSchemaProviderTest {
  @Inject ObjectMapper json;
  @Inject FailureNarrativeAgent agent;
  @Inject AppConfig config;
  @InjectMock RateLimitChatModel provider;

  @Test
  void actualRegisteredDefaultAndTwoNormalInvocationsDifferOnlyInTheirCurrentReferenceDomains() throws Exception {
    var firstRef = new Reference(Kind.REQUIREMENT_DRAFT, "first-producer", "first-hash");
    var secondRef = new Reference(Kind.REQUIREMENT_DRAFT, "second-producer", "second-hash");
    var first = context("first-failure", firstRef);
    var second = context("second-failure", secondRef);
    var firstReply = decision(firstRef, "first-failure");
    var secondReply = decision(secondRef, "second-failure");
    var captured = new ArrayList<ChatRequest>();
    var budget = install(captured, List.of(firstReply, firstReply, secondReply));
    String baselineRun = "registered-default-" + UUID.randomUUID();
    String firstRun = "schema-first-" + UUID.randomUUID();
    String secondRun = "schema-second-" + UUID.randomUUID();

    // This separate test-only call observes Quarkus's default schema, not the new schema builder.
    assertEquals(firstReply, LlmRunBudget.withRun(baselineRun, () -> agent.recover("en", "{}",
        DefaultChatRequestParameters.builder().build())));
    var firstResult = new FailureNarrative(agent).diagnoseRecovery(firstRun, first);
    var secondResult = new FailureNarrative(agent).diagnoseRecovery(secondRun, second);

    assertEquals(3, captured.size(), "One explicit baseline plus one physical request per normal diagnosis.");
    assertEquals(firstReply, firstResult.decision());
    assertEquals(secondReply, secondResult.decision());
    assertTrue(RecoveryDecisionValidator.validate(firstResult.decision(), first).accepted());
    assertTrue(RecoveryDecisionValidator.validate(secondResult.decision(), second).accepted());
    var original = captured.getFirst().parameters().responseFormat();
    assertNotNull(original, "The actual registered service must supply its generated default schema.");
    for (int i = 1; i < captured.size(); i++) {
      var actual = captured.get(i).parameters().responseFormat();
      assertNotNull(actual);
      assertEquals(original.type(), actual.type());
      assertEquals(original.jsonSchema().name(), actual.jsonSchema().name());
      assertEquals(withoutReferences(captured.getFirst()), withoutReferences(captured.get(i)),
          "Names, required fields, nullability, definitions, actions, and every other field must stay unchanged.");
      assertEquals(captured.getFirst().parameters().toolChoice(), captured.get(i).parameters().toolChoice());
      assertEquals(captured.getFirst().parameters().maxOutputTokens(), captured.get(i).parameters().maxOutputTokens());
    }
    var firstFields = schema(captured.get(1)).path("properties");
    var secondFields = schema(captured.get(2)).path("properties");
    assertMembership(firstFields.path("faultArtifactRef"), json.valueToTree(firstRef), true);
    assertMembership(firstFields.path("faultArtifactRef"), json.valueToTree(secondRef), false);
    assertMembership(secondFields.path("faultArtifactRef"), json.valueToTree(secondRef), true);
    assertMembership(secondFields.path("faultArtifactRef"), json.valueToTree(firstRef), false);
    assertMembership(secondFields.path("evidenceRefs"), json.valueToTree(List.of("second-failure")), true);
    assertMembership(secondFields.path("evidenceRefs"), json.valueToTree(List.of("first-failure")), false);
    for (String run : List.of(baselineRun, firstRun, secondRun)) {
      assertEquals(1, budget.state(run).requests());
      assertEquals(21, budget.state(run).tokens());
      assertEquals(0, budget.state(run).grants());
      assertTrue(budget.state(run).active().isEmpty());
    }
  }

  @Test
  void anEmptyDomainReachesTheRealProviderBoundaryAndStillCannotSupplyMissingNativeEvidence() {
    var reply = new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, null, List.of(), RecoveryAction.PARK,
        List.of(), "", "No diagnostic evidence was supplied.");
    var captured = new ArrayList<ChatRequest>();
    var budget = install(captured, List.of(reply));
    String run = "empty-schema-" + UUID.randomUUID();
    var context = new RecoveryContext(null, null, null, "en");

    var result = new FailureNarrative(agent).diagnoseRecovery(run, context);

    assertEquals(1, captured.size());
    var fields = schema(captured.getFirst()).path("properties");
    assertMembership(fields.path("faultArtifactRef"), json.nullNode(), true);
    assertMembership(fields.path("faultArtifactRef"), json.valueToTree(
        new Reference(Kind.REQUIREMENT_DRAFT, "invented", "invented-hash")), false);
    assertMembership(fields.path("evidenceRefs"), json.valueToTree(List.of()), true);
    assertMembership(fields.path("evidenceRefs"), json.valueToTree(List.of("invented")), false);
    assertEquals(List.of("Missing recovery evidence."),
        RecoveryDecisionValidator.validate(result.decision(), context).findings());
    assertEquals(1, budget.state(run).requests());
    assertEquals(0, budget.state(run).grants());
    assertTrue(budget.state(run).active().isEmpty());
  }

  private LlmRunBudget install(List<ChatRequest> captured, List<RecoveryDecision> replies) {
    var upstream = mock(ChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
    when(upstream.supportedCapabilities()).thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    when(provider.supportedCapabilities()).thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    when(upstream.chat(any(ChatRequest.class))).thenAnswer(call -> {
      captured.add(call.getArgument(0, ChatRequest.class));
      assertTrue(captured.size() <= replies.size(), "No hidden diagnosis or transport retry is allowed.");
      return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(replies.get(captured.size() - 1))))
          .tokenUsage(new TokenUsage(17, 4)).build();
    });
    var budget = new LlmRunBudget(new InMemoryArtifactBlobStore(), json, config);
    var boundary = new RateLimitChatModel(upstream, config, seconds -> {
      throw new AssertionError("No transport retry is allowed.");
    }, new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    doAnswer(call -> boundary.chat(call.getArgument(0, ChatRequest.class))).when(provider).chat(any(ChatRequest.class));
    return budget;
  }

  private ObjectNode withoutReferences(ChatRequest request) {
    var root = schema(request);
    ((ObjectNode) root.path("properties")).remove(List.of("faultArtifactRef", "evidenceRefs"));
    return root;
  }

  private ObjectNode schema(ChatRequest request) {
    return json.valueToTree(JsonSchemaElementUtils.toMap(request.parameters().responseFormat().jsonSchema().rootElement()));
  }

  private void assertMembership(JsonNode schema, JsonNode value, boolean expected) {
    var findings = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema).validate(value);
    assertEquals(expected, findings.isEmpty(), () -> value + " checked against " + schema + ": " + findings);
  }

  private static RecoveryContext context(String failureId, Reference ref) {
    return new RecoveryContext(new RecoveryEvidence(1, failureId, "VALIDATION_BLOCKER", "design-execution",
        null, null, List.of(ref), List.of(), null, List.of()), null, null, "en");
  }

  private static RecoveryDecision decision(Reference ref, String failureId) {
    return new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, ref, List.of(failureId), RecoveryAction.PARK,
        List.of(), "", "The original refusal remains available.");
  }
}
