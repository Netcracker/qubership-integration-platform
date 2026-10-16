package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SidecarKnowledgeClientTest {

  private RecordingKnowledgeSidecarApi api;
  private SidecarKnowledgeClient client;

  @BeforeEach
  void setUp() {
    api = new RecordingKnowledgeSidecarApi();
    client = SidecarKnowledgeClient.forTests(api);
  }

  static KnowledgePackageRef packageRef(String checksum) {
    return new KnowledgePackageRef(
        "fixture@1.0.0",
        "1.0.0",
        "1.0.0",
        checksum,
        "CERTIFIED",
        "sha256:certificate");
  }

  static CanonicalKnowledgeObject canonicalObject() {
    return new CanonicalKnowledgeObject(
        "1.0",
        "CIP:STD-000001",
        "Standard",
        "Fixture standard",
        "Fixture summary",
        metadataWithNullAnchor(),
        List.of(
            new CanonicalKnowledgeObject.Relation(
                "CIP:STD-000001",
                "implements",
                "CIP:RULE-000001",
                Map.of("required", true))),
        new CanonicalKnowledgeObject.Content(
            "markdown",
            "Complete body",
            "Complete raw source",
            List.of(Map.of("heading", "Fixture", "level", 1))),
        "1.0.0",
        "active",
        new CanonicalKnowledgeObject.Source(
            "markdown",
            "fixtures/source.md",
            "fixture-standard",
            "sha256:source",
            "1.0.0"));
  }

  static Map<String, Object> metadataWithNullAnchor() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("aliases", List.of("fixture-standard"));
    metadata.put("tags", List.of("fixture"));
    metadata.put("anchor", null);
    return metadata;
  }

  @Test
  void sendsPinnedChecksumAndAcceptsMatchingIdentity() {
    KnowledgePackageRef ref = packageRef("sha256:package-a");
    api.exactResponse =
        new KnowledgeSidecarApi.ExactResponseDto(toDto(ref), canonicalObject());

    KnowledgeObjectResult result =
        client.exact(new KnowledgeQueryContext(ref), "CIP:STD-000001");

    assertEquals("sha256:package-a", api.lastExactRequest().expectedPackageChecksum());
    assertEquals(ref, result.identity().packageRef());
    assertEquals("1.0", result.object().irVersion());
    assertEquals("Complete body", result.object().content().body());
    assertEquals("Complete raw source", result.object().content().raw());
    assertEquals("CIP:RULE-000001", result.object().relations().get(0).toId());
    assertEquals("fixtures/source.md", result.object().source().document());
    assertEquals("1.0.0", result.object().version());
    assertEquals("active", result.object().status());
    assertTrue(result.object().metadata().containsKey("anchor"));
    assertNull(result.object().metadata().get("anchor"));
  }

  @Test
  void mapsPackagePinMismatchWithoutRetry() {
    api.exactError =
        new WebApplicationException(
            Response.status(409)
                .entity(
                    new KnowledgeSidecarApi.ErrorDto(
                        "KNOWLEDGE_PACKAGE_PIN_MISMATCH",
                        "expectedPackageChecksum does not match the active package",
                        false))
                .build());

    KnowledgeClientException error =
        assertThrows(
            KnowledgeClientException.class,
            () ->
                client.exact(
                    new KnowledgeQueryContext(packageRef("sha256:old")),
                    "CIP:STD-000001"));

    assertEquals(KnowledgeFailureKind.KNOWLEDGE_PACKAGE_PIN_MISMATCH, error.kind());
    assertFalse(error.retryable());
  }

  @Test
  void mapsTemporarilyUnavailableAsRetryable() {
    api.exactError =
        new WebApplicationException(
            Response.status(503)
                .entity(
                    new KnowledgeSidecarApi.ErrorDto(
                        "KNOWLEDGE_TEMPORARILY_UNAVAILABLE", "busy", true))
                .build());

    KnowledgeClientException error =
        assertThrows(
            KnowledgeClientException.class,
            () ->
                client.exact(
                    new KnowledgeQueryContext(packageRef("sha256:package-a")),
                    "x"));
    assertEquals(KnowledgeFailureKind.KNOWLEDGE_TEMPORARILY_UNAVAILABLE, error.kind());
    assertTrue(error.retryable());
  }

  @Test
  void relationsAndContextRoundTrip() {
    KnowledgePackageRef ref = packageRef("sha256:package-a");
    api.relationsResponse =
        new KnowledgeSidecarApi.RelationsResponseDto(
            toDto(ref),
            List.of(
                new CanonicalKnowledgeObject.Relation(
                    "CIP:STD-000001", "implements", "CIP:RULE-000001", Map.of())));
    api.contextResponse =
        new KnowledgeSidecarApi.ContextResponseDto(
            toDto(ref),
            List.of("generator", "rule"),
            List.of(canonicalObject()),
            42);

    KnowledgeQueryContext context = new KnowledgeQueryContext(ref);
    assertEquals(1, client.relations(context, "a", Set.of()).relations().size());
    KnowledgeContextPackage contextPackage =
        client.context(
            context,
            new KnowledgeContextRequest(
                "Add error handling",
                "cip-error-handling-generator",
                "GENERATOR",
                List.of("catch-2"),
                12,
                20_000));
    assertEquals(ref, contextPackage.identity().packageRef());
    assertEquals(List.of("generator", "rule"), contextPackage.capabilities());
    assertEquals(1, contextPackage.objects().size());
    assertEquals(42, contextPackage.contentChars());
    assertEquals("sha256:package-a", api.lastContextRequest().expectedPackageChecksum());
  }

  private static KnowledgeSidecarApi.PackageRefDto toDto(KnowledgePackageRef ref) {
    return new KnowledgeSidecarApi.PackageRefDto(
        ref.packageKey(),
        ref.knowledgeVersion(),
        ref.schemaVersion(),
        ref.packageChecksum(),
        ref.certificationStatus(),
        ref.certificationDigest());
  }

  /** Recording stub used by contract tests; exposes {@code lastExactRequest()}. */
  static final class RecordingKnowledgeSidecarApi implements KnowledgeSidecarApi {
    private final AtomicReference<ExactRequestDto> lastExact = new AtomicReference<>();
    private final AtomicReference<ContextRequestDto> lastContext = new AtomicReference<>();
    ExactResponseDto exactResponse;
    WebApplicationException exactError;
    RelationsResponseDto relationsResponse;
    ContextResponseDto contextResponse;

    ExactRequestDto lastExactRequest() {
      return lastExact.get();
    }

    ContextRequestDto lastContextRequest() {
      return lastContext.get();
    }

    @Override
    public HealthDto ready() {
      return new HealthDto("ok", null);
    }

    @Override
    public PackageResponseDto activePackage() {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public ExactResponseDto exact(ExactRequestDto body) {
      lastExact.set(body);
      if (exactError != null) {
        throw exactError;
      }
      return exactResponse;
    }

    @Override
    public SearchResponseDto filter(FilterRequestDto body) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public RelationsResponseDto relations(RelationsRequestDto body) {
      return relationsResponse;
    }

    @Override
    public ContextResponseDto context(ContextRequestDto body) {
      lastContext.set(body);
      return contextResponse;
    }
  }
}
