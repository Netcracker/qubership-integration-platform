package org.qubership.integration.platform.ai.chat.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.a2a.A2aSdkBootDisabledProfile;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.create.UnsupportedCreateRunBindingException;
import org.qubership.integration.platform.ai.skills.RockySkills;

@QuarkusTest
@TestProfile(A2aSdkBootDisabledProfile.class)
class RetiredCreateBindingDecisionIT {
  @Inject ArtifactBlobStore blobs;
  @Inject RockySkills rockySkills;

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
}
