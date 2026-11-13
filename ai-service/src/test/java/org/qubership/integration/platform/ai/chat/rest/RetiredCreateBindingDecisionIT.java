package org.qubership.integration.platform.ai.chat.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.a2a.A2aSdkBootDisabledProfile;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;
import org.qubership.integration.platform.ai.productpipeline.create.UnsupportedCreateRunBindingException;
import org.qubership.integration.platform.ai.skills.RockySkills;

@QuarkusTest
@TestProfile(A2aSdkBootDisabledProfile.class)
class RetiredCreateBindingDecisionIT {
  @Inject ArtifactBlobStore blobs;
  @Inject RockySkills rockySkills;
  @Inject ProductPipelineRunStore runStore;
  @Inject ObjectMapper mapper;

  @ParameterizedTest
  @ValueSource(strings = {"1", "2"})
  void decisionEndpointRejectsRetiredBindingAndAcceptsCurrentBinding(String version) throws Exception {
    assertTrue(rockySkills.available(), "CREATE skills must be available to exercise the decision preflight");
    String conversationId = UUID.randomUUID().toString();
    String runId = conversationId + "-create-chain-" + version;
    String fixture;
    try (var stream = getClass().getResourceAsStream(
        "/product-pipelines/historical-create-chain-v1-binding.json")) {
      fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    String binding = fixture.formatted(conversationId, runId, runId)
        .replace("\"profileVersion\":\"1\"", "\"profileVersion\":\"" + version + "\"")
        .replace("create-chain@1", "create-chain@" + version);
    blobs.put("product-pipeline-create-bindings/" + conversationId + ".json",
        binding.getBytes(StandardCharsets.UTF_8));

    var response = given().get("/api/v1/chat/{id}/decision", conversationId).then();
    if ("1".equals(version)) {
      response.statusCode(409).body(equalTo(new UnsupportedCreateRunBindingException().sseMessage()));
    } else {
      response.statusCode(204);
    }
  }

  @ParameterizedTest
  @CsvSource({"1, WAITING_FOR_APPROVAL", "2, WAITING_FOR_APPROVAL",
      "1, WAITING_FOR_INPUT", "2, WAITING_FOR_INPUT"})
  void decisionEndpointChecksBindingBeforeReturningPersistedCard(String version, RunStatus status)
      throws Exception {
    assertTrue(rockySkills.available(), "CREATE skills must be available to exercise the decision preflight");
    String conversationId = UUID.randomUUID().toString();
    String runId = conversationId + "-create-chain-" + version;
    String fixture;
    try (var stream = getClass().getResourceAsStream(
        "/product-pipelines/historical-create-chain-v1-binding.json")) {
      fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    String binding = fixture.formatted(conversationId, runId, runId)
        .replace("\"profileVersion\":\"1\"", "\"profileVersion\":\"" + version + "\"")
        .replace("create-chain@1", "create-chain@" + version);
    blobs.put("product-pipeline-create-bindings/" + conversationId + ".json",
        binding.getBytes(StandardCharsets.UTF_8));
    var candidate = new CompilationArtifacts.Reference(
        CompilationArtifacts.Kind.REQUIREMENT_BRIEF, "brief-1", "brief-hash");
    var stage = new StageSnapshot("requirement-analysis", status == RunStatus.WAITING_FOR_APPROVAL
        ? StageStatus.WAITING_FOR_APPROVAL : StageStatus.WAITING_FOR_INPUT,
        List.of(candidate), candidate.artifactId(), List.of(candidate), candidate, 1);
    var snapshot = new RunSnapshot(runId, conversationId, 4L, status,
        "requirement-analysis", List.of(stage), null, "flow-1");
    runStore.create(snapshot);
    var transition = new RunTransition(3L, 4L, RunStatus.RUNNING, status,
        "requirement-analysis", Instant.EPOCH, "Which system should receive the request?");
    blobs.put("product-pipeline-runs/" + runId + ".json", mapper.writeValueAsBytes(
        new ProductPipelineRunDocument(snapshot, List.of(), List.of(transition), null)));

    var response = given().get("/api/v1/chat/{id}/decision", conversationId).then();
    if ("1".equals(version)) {
      response.statusCode(409).body(equalTo(new UnsupportedCreateRunBindingException().sseMessage()));
    } else {
      response.statusCode(200).body(containsString(status == RunStatus.WAITING_FOR_APPROVAL
          ? "approve" : "clarify"));
    }
  }
}
