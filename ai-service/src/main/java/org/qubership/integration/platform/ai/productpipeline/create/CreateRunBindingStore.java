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
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;

/**
 * Persists one immutable CREATE runtime assignment per conversation behind create-only CAS writes.
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
    return blobStore.get(key(conversationId)).map(this::read);
  }

  public CreateRunBinding create(CreateRunBinding binding) {
    Objects.requireNonNull(binding, "binding");
    byte[] payload = write(binding);
    try {
      blobStore.putIfVersion(key(binding.conversationId()), payload, null);
      return binding;
    } catch (StaleBlobVersionException e) {
      return load(binding.conversationId())
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "binding raced and disappeared: " + binding.conversationId(), e));
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
