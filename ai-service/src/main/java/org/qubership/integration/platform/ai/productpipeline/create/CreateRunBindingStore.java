package org.qubership.integration.platform.ai.productpipeline.create;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;

/**
 * Persists one CREATE runtime assignment until the conversation is explicitly reset.
 *
 * <p>Product {@code create-chain@*} bindings registered in the profile catalog are readable. New
 * writes
 * omit the historical {@code mode} field. Unsupported historical bindings fail closed without
 * mutation.
 */
@ApplicationScoped
public class CreateRunBindingStore {

  private static final String PREFIX = "product-pipeline-create-bindings/";
  private static final String SUPPORTED_PROFILE_ID = "create-chain";

  private final ArtifactBlobStore blobStore;
  private final ObjectMapper objectMapper;
  private final ProductPipelineProfileCatalog profileCatalog;

  /** Test constructor with bundled create-chain@1 and @2 profiles. */
  public CreateRunBindingStore(ArtifactBlobStore blobStore, ObjectMapper objectMapper) {
    this(blobStore, objectMapper, defaultTestProfileCatalog());
  }

  @Inject
  public CreateRunBindingStore(
      ArtifactBlobStore blobStore,
      ObjectMapper objectMapper,
      ProductPipelineProfileCatalog profileCatalog) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper")
            .copy()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.profileCatalog = Objects.requireNonNull(profileCatalog, "profileCatalog");
  }

  public Optional<CreateRunBinding> load(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    return blobStore.get(key(conversationId))
        .filter(payload -> resetSuffix(payload).isEmpty()).map(this::read);
  }

  public void reset(String conversationId) {
    String key = key(Objects.requireNonNull(conversationId, "conversationId"));
    var current = blobStore.getVersioned(key);
    byte[] marker = ("{\"reset\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
    blobStore.putIfVersion(key, marker, current.map(blob -> blob.version()).orElse(null));
  }

  public String plannedRunId(String conversationId, String initialRunId) {
    return blobStore.get(key(conversationId))
        .map(payload -> resetSuffix(payload).map(suffix -> initialRunId + "-" + suffix)
            .orElseGet(() -> read(payload).productRunId()))
        .orElse(initialRunId);
  }

  private Optional<String> resetSuffix(byte[] payload) {
    try {
      JsonNode root = objectMapper.readTree(payload);
      return root != null && root.isObject() && root.size() == 1 && root.hasNonNull("reset")
          ? Optional.of(UUID.fromString(root.get("reset").asText()).toString()) : Optional.empty();
    } catch (Exception e) {
      throw new IllegalStateException("cannot deserialize create-run binding", e);
    }
  }

  public CreateRunBinding create(CreateRunBinding binding) {
    Objects.requireNonNull(binding, "binding");
    var current = blobStore.getVersioned(key(binding.conversationId()));
    Optional<String> reset = current.flatMap(blob -> resetSuffix(blob.content()));
    if (current.isPresent() && reset.isEmpty()) {
      return read(current.get().content());
    }
    if (current.isPresent()) {
      String runId = binding.productRunId() + "-" + reset.orElseThrow();
      RunManifest source = binding.runManifest();
      RunManifest manifest = new RunManifest(runId, source.parentRunId(), source.sourceReferences(),
          source.runtimeSelection(), source.profileId(), source.profileVersion(), source.profileDigest(),
          source.referenceBaselineId(), source.referenceBaselineDigest(), source.dependencyClosure(),
          source.dependencyClosureDigest(), source.knowledgePackage(), source.languageVersion(),
          source.artifactSchemaVersions(), source.compilerRunPin(), source.responseLocale());
      binding = new CreateRunBinding(binding.conversationId(), runId, manifest, binding.createdAt());
    }
    byte[] payload = write(binding);
    try {
      blobStore.putIfVersion(key(binding.conversationId()), payload,
          current.map(blob -> blob.version()).orElse(null));
      return binding;
    } catch (StaleBlobVersionException e) {
      String conversationId = binding.conversationId();
      return load(conversationId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "binding raced and disappeared: " + conversationId, e));
    }
  }

  private static String key(String conversationId) {
    return PREFIX + conversationId + ".json";
  }

  private byte[] write(CreateRunBinding binding) {
    try {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("conversationId", binding.conversationId());
      node.put("productRunId", binding.productRunId());
      node.set("runManifest", objectMapper.valueToTree(binding.runManifest()));
      node.put("createdAt", binding.createdAt().toString());
      return objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize create-run binding", e);
    }
  }

  private CreateRunBinding read(byte[] payload) {
    try {
      JsonNode root = objectMapper.readTree(payload);
      if (root == null || !root.isObject()) {
        throw new UnsupportedCreateRunBindingException("binding payload is not a JSON object");
      }
      return parseSupportedBinding(root);
    } catch (UnsupportedCreateRunBindingException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("cannot deserialize create-run binding", e);
    }
  }

  private CreateRunBinding parseSupportedBinding(JsonNode root) {
    JsonNode modeNode = root.get("mode");
    if (modeNode != null && !modeNode.isNull()) {
      String mode = modeNode.asText();
      if (!"PRODUCT".equals(mode)) {
        throw new UnsupportedCreateRunBindingException("mode=" + mode);
      }
    }

    JsonNode manifestNode = root.get("runManifest");
    if (manifestNode == null || manifestNode.isNull() || !manifestNode.isObject()) {
      throw new UnsupportedCreateRunBindingException("missing runManifest");
    }
    String profileId = textOrNull(manifestNode.get("profileId"));
    String profileVersion = textOrNull(manifestNode.get("profileVersion"));
    if (!SUPPORTED_PROFILE_ID.equals(profileId)
        || !profileCatalog.supports(profileId, profileVersion)) {
      throw new UnsupportedCreateRunBindingException(
          "profile=" + profileId + "@" + profileVersion);
    }

    String conversationId = textOrNull(root.get("conversationId"));
    String productRunId = textOrNull(root.get("productRunId"));
    Instant createdAt = Instant.parse(textOrNull(root.get("createdAt")));
    RunManifest runManifest = objectMapper.convertValue(manifestNode, RunManifest.class);
    return new CreateRunBinding(conversationId, productRunId, runManifest, createdAt);
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.asText();
  }

  private static ProductPipelineProfileCatalog defaultTestProfileCatalog() {
    List<ProductPipelineProfile> profiles = new ArrayList<>();
    for (String fileName : List.of("create-chain-v1.yaml", "create-chain-v2.yaml")) {
      try (InputStream in =
          CreateRunBindingStore.class.getResourceAsStream(
              "/product-pipelines/profiles/" + fileName)) {
        if (in == null) {
          throw new IllegalStateException("missing bundled profile for tests: " + fileName);
        }
        profiles.add(ProductPipelineProfileParser.parse(in));
      } catch (IOException e) {
        throw new IllegalStateException("cannot load bundled profile for tests: " + fileName, e);
      }
    }
    return new ProductPipelineProfileCatalog(profiles);
  }
}
