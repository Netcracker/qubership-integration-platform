package org.qubership.integration.platform.ai.compiler.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Local read-only exporter for a conversation in the campaign's private bucket. */
public final class RecoveryEvidenceProbe {
  private RecoveryEvidenceProbe() {}

  static Map<String, Object> collect(ArtifactBlobStore blobs, ObjectMapper mapper, String conversationId) {
    var runs = new ProductPipelineRunStore(blobs, mapper, Clock.systemUTC());
    var run = runs.loadByConversation(conversationId).orElseThrow(
        () -> new IllegalStateException("No pipeline run for the owned conversation"));
    String runId = run.run().runId();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var pipeline = new ArrayList<CompilationArtifacts.Revision>();
    var captures = new ArrayList<CompilationArtifacts.Revision>();
    for (var kind : CompilationArtifacts.Kind.values()) {
      pipeline.addAll(artifacts.history(runId, kind));
    }
    var links = new ArrayList<CompilationSessions.CompilationSession>(sessions.history(conversationId));
    var compilations = new java.util.LinkedHashSet<String>();
    links.forEach(link -> compilations.add(link.compilationId()));
    sessions.current(conversationId).ifPresent(link -> compilations.add(link.compilationId()));
    for (String compilation : compilations) {
      if (compilation.equals(runId)) { continue; }
      for (var kind : CompilationArtifacts.Kind.values()) {
        captures.addAll(artifacts.history(compilation, kind));
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", 1);
    result.put("conversationId", conversationId);
    result.put("runId", runId);
    result.put("runDocument", run);
    result.put("pipelineArtifacts", pipeline);
    result.put("captureArtifacts", captures);
    result.put("captureLinks", links);
    try {
      String hashed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(runId.getBytes(StandardCharsets.UTF_8)));
      blobs.get("product-pipeline-llm-budget/" + hashed + ".json").ifPresent(bytes -> {
        try { result.put("budget", mapper.readTree(bytes)); }
        catch (java.io.IOException error) { throw new IllegalStateException("Invalid model accounting", error); }
      });
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
    return result;
  }

  public static void main(String[] args) throws Exception {
    boolean objectOnly = args.length == 3 && "--object".equals(args[0]);
    if (args.length != 2 && !objectOnly) { throw new IllegalArgumentException("Expected conversation ID and output file"); }
    var mapper = new ObjectMapper().findAndRegisterModules();
    try (var s3 = S3Client.builder()
        .endpointOverride(URI.create(required("RECOVERY_STORAGE_ENDPOINT")))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
            required("RECOVERY_STORAGE_ACCESS_KEY"), required("RECOVERY_STORAGE_SECRET_KEY"))))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()) {
      var blobs = new ReadOnlyS3(s3, required("RECOVERY_STORAGE_BUCKET"));
      if (objectOnly) {
        byte[] content = blobs.get(args[1]).orElseThrow();
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), Map.of(
            "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
            "modelBudgetCount", blobs.list("product-pipeline-llm-budget/").size()));
      } else {
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),
            collect(blobs, mapper, args[0]));
      }
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " is required"); }
    return value;
  }

  static final class ReadOnlyS3 implements ArtifactBlobStore {
    private final S3Client s3;
    private final String bucket;

    ReadOnlyS3(S3Client s3, String bucket) { this.s3 = s3; this.bucket = bucket; }

    @Override public Optional<byte[]> get(String key) {
      try { return Optional.of(s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray()); }
      catch (S3Exception error) { if (error.statusCode() == 404) { return Optional.empty(); } throw error; }
    }

    @Override public List<String> list(String prefix) {
      return s3.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix)).contents().stream()
          .map(software.amazon.awssdk.services.s3.model.S3Object::key).toList();
    }

    @Override public Optional<VersionedBlob> getVersioned(String key) {
      return get(key).map(bytes -> new VersionedBlob(bytes, "read-only"));
    }

    @Override public void put(String key, byte[] content) { throw new UnsupportedOperationException("Read-only probe"); }
    @Override public void putIfVersion(String key, byte[] content, String version) {
      throw new UnsupportedOperationException("Read-only probe");
    }
  }
}
