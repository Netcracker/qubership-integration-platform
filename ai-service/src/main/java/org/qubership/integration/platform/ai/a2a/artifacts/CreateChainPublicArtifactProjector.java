package org.qubership.integration.platform.ai.a2a.artifacts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPublicArtifactTypes;

/**
 * Projects durable create-chain evidence into allowlisted public A2A artifacts.
 *
 * <p>Allowlist enforcement lives here: non-public types are dropped. Reviewable MVP content is
 * embedded in the payload. Oversized text is summarized with an explicit {@code truncated} flag.
 */
public final class CreateChainPublicArtifactProjector {

  public static final Set<String> ALLOWED_TYPES =
      Set.of(
          CreateChainPublicArtifactTypes.REQUIREMENT_DRAFT,
          CreateChainPublicArtifactTypes.REQUIREMENT_BRIEF,
          CreateChainPublicArtifactTypes.INTEGRATION_DESIGN,
          CreateChainPublicArtifactTypes.IMPLEMENTATION_PLAN,
          CreateChainPublicArtifactTypes.VALIDATION_REPORT,
          CreateChainPublicArtifactTypes.MATERIALIZATION_RESULT,
          CreateChainPublicArtifactTypes.FAILURE_REPORT);

  /** Maximum characters retained for a single reviewable text field. */
  public static final int MAX_TEXT_CHARS = 32_768;

  private static final Set<String> FORBIDDEN_KEYS =
      Set.of(
          "bucket",
          "bucketname",
          "objectkey",
          "object_key",
          "storagekey",
          "storage_key",
          "s3uri",
          "s3_uri",
          "prompt",
          "systemprompt",
          "modeltrace",
          "model_trace",
          "credentials",
          "password",
          "secret",
          "token",
          "apikey",
          "api_key",
          "rawlog",
          "raw_log",
          "rawlogs",
          "pipelinesnapshot",
          "pipeline_snapshot",
          "compilationref",
          "compilation_ref",
          "reference",
          "blobstorekey",
          "blob_store_key",
          "contentref",
          "content_ref");

  private static final Set<String> ALLOWED_TEXT_FIELDS =
      Set.of(
          "summary",
          "title",
          "status",
          "markdown",
          "planText",
          "goal",
          "outcome",
          "chainId",
          "chainName",
          "validationOutcome",
          "failureCode");

  private static final Pattern FORBIDDEN_VALUE =
      Pattern.compile(
          "(?i)(s3://|app://|product-pipeline-|compiler-artifacts/|minio|amazonaws\\.com|"
              + "\"kind\"\\s*:\\s*\"|Reference\\(|bucketName|objectKey|modelTrace)");

  private CreateChainPublicArtifactProjector() {}

  public static Optional<CreateChainPublicArtifact> project(CreateChainArtifactEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    Optional<String> publicType = resolvePublicType(evidence.type());
    if (publicType.isEmpty() || !ALLOWED_TYPES.contains(publicType.get())) {
      return Optional.empty();
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", evidence.artifactId());
    payload.put("type", publicType.get());
    payload.put("revision", evidence.revision());
    payload.put("contentHash", evidence.contentHash());

    boolean truncated = false;
    for (String key : ALLOWED_TEXT_FIELDS) {
      Object value = evidence.durableFields().get(key);
      if (value instanceof String text && isSafeText(text)) {
        String stripped = text.strip();
        if (stripped.length() > MAX_TEXT_CHARS) {
          payload.put(key, stripped.substring(0, MAX_TEXT_CHARS));
          truncated = true;
        } else {
          payload.put(key, stripped);
        }
      } else if (("chainId".equals(key) || "revision".equals(key))
          && (value instanceof Number || value instanceof Boolean)) {
        payload.put(key, value);
      }
    }
    if (truncated) {
      payload.put("truncated", true);
    }

    assertNoLeaks(payload);
    return Optional.of(
        new CreateChainPublicArtifact(
            evidence.artifactId(),
            publicType.get(),
            evidence.revision(),
            evidence.contentHash(),
            payload));
  }

  public static boolean isAllowedType(String type) {
    return resolvePublicType(type).filter(ALLOWED_TYPES::contains).isPresent();
  }

  /**
   * @deprecated Unresolved {@code app://} references are no longer emitted. Kept for temporary
   *     test migration only.
   */
  @Deprecated
  public static String contentRef(String artifactId, long revision) {
    return "app://create-chain/artifacts/" + artifactId + "?revision=" + revision;
  }

  private static Optional<String> resolvePublicType(String type) {
    if (type == null || type.isBlank()) {
      return Optional.empty();
    }
    String normalized = type.trim().toLowerCase(Locale.ROOT);
    if (ALLOWED_TYPES.contains(normalized)) {
      return Optional.of(normalized);
    }
    return CreateChainPublicArtifactTypes.toKind(normalized)
        .flatMap(CreateChainPublicArtifactTypes::toPublicType);
  }

  private static boolean isSafeText(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    return !FORBIDDEN_VALUE.matcher(text).find();
  }

  private static void assertNoLeaks(Map<String, Object> payload) {
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      String key = entry.getKey().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
      if (FORBIDDEN_KEYS.contains(key)) {
        throw new IllegalStateException("Forbidden artifact field leaked: " + entry.getKey());
      }
      if (entry.getValue() instanceof String text && FORBIDDEN_VALUE.matcher(text).find()) {
        throw new IllegalStateException("Forbidden artifact value leaked for " + entry.getKey());
      }
    }
  }
}
