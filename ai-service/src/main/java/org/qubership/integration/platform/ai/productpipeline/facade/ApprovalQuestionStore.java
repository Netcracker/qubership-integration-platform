package org.qubership.integration.platform.ai.productpipeline.facade;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;

/**
 * Keeps the question a gate was announced with, so a card re-fetched later is never blank.
 *
 * <p>A wait signal carries its prompt only when the run stops for the first time; on resume the
 * prompt is blank, while the gate itself survives. The question is keyed by the artifact hash, so a
 * revised candidate gets its own question and an answered one is never reused.
 */
@ApplicationScoped
public class ApprovalQuestionStore {

  private static final String PREFIX = "product-pipeline-approval-questions/";

  private final ArtifactBlobStore blobStore;

  @Inject
  public ApprovalQuestionStore(ArtifactBlobStore blobStore) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
  }

  public void save(String conversationId, String artifactHash, String question) {
    if (question == null || question.isBlank()) {
      return;
    }
    blobStore.put(key(conversationId, artifactHash), question.getBytes(StandardCharsets.UTF_8));
  }

  public Optional<String> find(String conversationId, String artifactHash) {
    if (artifactHash == null || artifactHash.isBlank()) {
      return Optional.empty();
    }
    return blobStore
        .get(key(conversationId, artifactHash))
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .filter(question -> !question.isBlank());
  }

  private static String key(String conversationId, String artifactHash) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(artifactHash, "artifactHash");
    return PREFIX + conversationId + "/" + artifactHash.replace(':', '-') + ".txt";
  }
}
