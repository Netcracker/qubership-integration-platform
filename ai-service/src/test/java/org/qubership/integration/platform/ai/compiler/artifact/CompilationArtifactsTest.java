package org.qubership.integration.platform.ai.compiler.artifact;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.ArtifactDecision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Decision;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.DecisionCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;

class CompilationArtifactsTest {

  private static final String COMPILATION_ID = "compilation-1";

  private CompilationArtifacts artifacts;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(),
            mapper,
            Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-10-29T10:36:24.123456789Z", "2026-10-29T10:36:24.123456Z",
      "2026-10-29T10:36:24.123Z", "2026-10-29T10:36:24Z"})
  void preservesUserInputTimestampPrecisionAfterReload(String timestamp) {
    var receivedAt = Instant.parse(timestamp);
    var input = new UserInput(
        "turn-1", "policy-choice", "Keep NONE", receivedAt);
    var revision = artifacts.append(new AppendCommand(
        COMPILATION_ID, Kind.USER_INPUT, "1", "test", "1", input, List.of(), null));

    var reloaded = artifacts.get(COMPILATION_ID, revision.reference()).orElseThrow();
    var actual = artifacts.payload(reloaded, UserInput.class);

    assertEquals(input, actual);
  }

  @Test
  void preservesPreviouslyStoredNumericPayloadAndApproval() throws Exception {
    var legacyMapper = new ObjectMapper().registerModule(new JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    var expected = new TimestampedAmount(1.0, Instant.parse("2026-10-29T10:36:24.123456789Z"));
    var payload = legacyMapper.valueToTree(expected);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(legacyMapper.writeValueAsBytes(payload)));
    var revision = new Revision("legacy-artifact", COMPILATION_ID, Kind.IMPLEMENTATION_PLAN, "legacy-lineage",
        1L, "1", expected.receivedAt(), "previous-writer", "1", hash, List.of(), null, payload, null);
    var decision = new ArtifactDecision("legacy-approval", COMPILATION_ID, 1L,
        revision.reference(), Decision.APPROVED, "user-1", expected.receivedAt(), null);
    var blobs = new InMemoryArtifactBlobStore();
    String prefix = "compiler-artifacts/" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(COMPILATION_ID.getBytes(StandardCharsets.UTF_8)) + "/";
    blobs.put(prefix + "artifacts/implementation_plan/legacy.json", legacyMapper.writeValueAsBytes(revision));
    blobs.put(prefix + "decisions/legacy.json", legacyMapper.writeValueAsBytes(decision));
    var reader = new CompilationArtifacts(blobs, legacyMapper, Clock.systemUTC());

    var actual = reader.get(COMPILATION_ID, revision.reference()).orElseThrow();

    assertAll(
        () -> assertEquals(expected, reader.payload(actual, TimestampedAmount.class)),
        () -> assertEquals(hash, actual.contentHash()),
        () -> assertEquals(revision.reference(), actual.reference()),
        () -> assertTrue(reader.isApproved(COMPILATION_ID, revision.reference()), "Existing approval remains valid."));
  }

  private record TimestampedAmount(double amount, Instant receivedAt) {}

  @Test
  void preservesImmutableRevisionHistory() {
    Revision first = append(Kind.REQUIREMENT_DRAFT, "draft one", List.of(), null);
    Revision second =
        append(Kind.REQUIREMENT_DRAFT, "draft two", List.of(), first.artifactId());

    List<Revision> history = artifacts.history(COMPILATION_ID, Kind.REQUIREMENT_DRAFT);

    assertEquals(2, history.size());
    assertEquals("draft one", history.get(0).payload().get("text").asText());
    assertEquals("draft two", history.get(1).payload().get("text").asText());
    assertEquals(first.artifactId(), second.revisesArtifactId());
  }

  @Test
  void derivesTransitiveStaleDescendantsFromLineage() {
    Revision designOne = append(Kind.IMPLEMENTATION_PLAN, "design one", List.of(), null);
    Revision planOne =
        append(Kind.IMPLEMENTATION_PLAN, "plan one", List.of(designOne.reference()), null);
    Revision bundleOne =
        append(
            Kind.CHAIN_PLAN_GRAPH,
            "bundle one",
            List.of(planOne.reference()),
            null);
    Revision reportOne =
        append(
            Kind.PLAN_VALIDATION_RESULT,
            "validation one",
            List.of(bundleOne.reference()),
            null);
    Revision designTwo =
        append(Kind.IMPLEMENTATION_PLAN, "design two", List.of(), designOne.artifactId());

    var impact = artifacts.changeImpact(COMPILATION_ID, designTwo.reference());

    assertEquals(
        List.of(planOne.artifactId(), bundleOne.artifactId(), reportOne.artifactId()),
        impact.staleDescendants().stream().map(Revision::artifactId).toList());
  }

  @Test
  void revisingOlderBranchInvalidatesDescendantsOfNewerSibling() {
    Revision designOne = append(Kind.IMPLEMENTATION_PLAN, "design one", List.of(), null);
    Revision designTwo =
        append(Kind.IMPLEMENTATION_PLAN, "design two", List.of(), designOne.artifactId());
    Revision planTwo =
        append(Kind.IMPLEMENTATION_PLAN, "plan two", List.of(designTwo.reference()), null);
    Revision designThree =
        append(Kind.IMPLEMENTATION_PLAN, "design three", List.of(), designOne.artifactId());

    var impact = artifacts.changeImpact(COMPILATION_ID, designThree.reference());

    assertEquals(
        List.of(planTwo.artifactId()),
        impact.staleDescendants().stream().map(Revision::artifactId).toList());
    assertEquals(designOne.lineageId(), designThree.lineageId());
  }

  @Test
  void approvalTargetsExactArtifactContent() {
    Revision first = append(Kind.IMPLEMENTATION_PLAN, "plan one", List.of(), null);
    artifacts.recordDecision(
        new DecisionCommand(
            COMPILATION_ID, first.reference(), Decision.APPROVED, "user-1", null));
    Revision second =
        append(Kind.IMPLEMENTATION_PLAN, "plan two", List.of(), first.artifactId());

    assertTrue(artifacts.isApproved(COMPILATION_ID, first.reference()));
    assertFalse(artifacts.isApproved(COMPILATION_ID, second.reference()));
  }

  @Test
  void latestDecisionWinsForExactArtifactContent() {
    Revision plan = append(Kind.IMPLEMENTATION_PLAN, "plan", List.of(), null);
    artifacts.recordDecision(
        new DecisionCommand(
            COMPILATION_ID, plan.reference(), Decision.APPROVED, "user-1", null));
    artifacts.recordDecision(
        new DecisionCommand(
            COMPILATION_ID, plan.reference(), Decision.REJECTED, "user-1", "Needs changes"));

    assertFalse(artifacts.isApproved(COMPILATION_ID, plan.reference()));
  }

  @Test
  void rejectsInputReferenceWithDifferentContentHash() {
    Revision design = append(Kind.IMPLEMENTATION_PLAN, "design", List.of(), null);
    var wrongReference =
        new CompilationArtifacts.Reference(
            design.kind(), design.artifactId(), "different-content-hash");

    AppendCommand command =
        command(Kind.IMPLEMENTATION_PLAN, "plan", List.of(wrongReference), null);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> artifacts.append(command));
    assertEquals("input does not match a stored artifact revision", error.getMessage());
  }

  @Test
  void legacyAppendCommandLeavesProvenanceNull() {
    Revision revision = append(Kind.REQUIREMENT_DRAFT, "draft", List.of(), null);

    assertNull(revision.provenance());
    assertNull(command(Kind.REQUIREMENT_DRAFT, "draft", List.of(), null).provenance());
  }

  @Test
  void decodesChainSemanticRevisionAsTypedPayload() {
    ChainSemanticRevision semantic = sampleSemanticRevision();
    Revision stored =
        artifacts.append(
            new AppendCommand(
                COMPILATION_ID,
                Kind.CHAIN_SEMANTIC_REVISION,
                ChainSemanticRevision.CURRENT_SCHEMA_VERSION,
                "test-producer",
                "1",
                semantic,
                List.of(),
                null));

    assertEquals(semantic, artifacts.payload(stored, ChainSemanticRevision.class));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> artifacts.payload(stored, Map.class));
    assertEquals(
        "CHAIN_SEMANTIC_REVISION payload decodes only as ChainSemanticRevision",
        error.getMessage());
  }

  @Test
  void rejectsUnsupportedChainSemanticRevisionSchema() {
    AppendCommand command =
        new AppendCommand(
            COMPILATION_ID,
            Kind.CHAIN_SEMANTIC_REVISION,
            "normalized-design-flow/v1",
            "test-producer",
            "1",
            sampleSemanticRevision(),
            List.of(),
            null);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> artifacts.append(command));
    assertEquals(
        "Unsupported semantic schema version: normalized-design-flow/v1", error.getMessage());
  }

  private static ChainSemanticRevision sampleSemanticRevision() {
    return SemanticFixtures.revision(
        List.of(SemanticFixtures.entry("http-in", "trigger-http")));
  }

  private Revision append(
      Kind kind,
      String text,
      List<CompilationArtifacts.Reference> inputs,
      String revisesArtifactId) {
    return artifacts.append(command(kind, text, inputs, revisesArtifactId));
  }

  private AppendCommand command(
      Kind kind,
      String text,
      List<CompilationArtifacts.Reference> inputs,
      String revisesArtifactId) {
    return new AppendCommand(
        COMPILATION_ID,
        kind,
        "1",
        "test-producer",
        "1",
        Map.of("text", text),
        inputs,
        revisesArtifactId);
  }

}
