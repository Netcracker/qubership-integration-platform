package org.qubership.integration.platform.ai.productpipeline.knowledge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/**
 * Bridges conversations to either a product-run pinned knowledge package or the sidecar-selected
 * active package.
 */
@ApplicationScoped
public class DefaultKnowledgeContextProvider implements KnowledgeContextProvider {

  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final Supplier<KnowledgePackageRef> activePackage;

  @Inject
  public DefaultKnowledgeContextProvider(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      @RestClient KnowledgeSidecarApi sidecarApi) {
    this(
        runStore,
        artifactStore,
        () -> SidecarKnowledgeClient.toPackageRef(sidecarApi.activePackage().packageRef()));
  }

  DefaultKnowledgeContextProvider(
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      Supplier<KnowledgePackageRef> activePackage) {
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.activePackage = Objects.requireNonNull(activePackage, "activePackage");
  }

  @Override
  public KnowledgeQueryContext forConversation(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return new KnowledgeQueryContext(activePackage.get());
    }
    return pinnedPackage(conversationId)
        .map(KnowledgeQueryContext::new)
        .orElseGet(() -> new KnowledgeQueryContext(activePackage.get()));
  }

  private Optional<KnowledgePackageRef> pinnedPackage(String conversationId) {
    Optional<ProductPipelineRunDocument> document = runStore.loadByConversation(conversationId);
    if (document.isEmpty()) {
      return Optional.empty();
    }
    ProductPipelineRunDocument stored = document.orElseThrow();
    String runId = stored.run().runId();
    CompilationArtifacts.Reference manifestRef = stored.run().runManifestRef();
    if (manifestRef == null) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_INTEGRITY_FAILURE,
          "RUN_MANIFEST reference is missing for run " + runId);
    }
    RunManifest manifest =
        artifactStore
            .get(runId, manifestRef)
            .map(revision -> artifactStore.payload(revision, RunManifest.class))
            .orElseThrow(
                () ->
                    new KnowledgeClientException(
                        KnowledgeFailureKind.KNOWLEDGE_INTEGRITY_FAILURE,
                        "RUN_MANIFEST revision is missing for run " + runId));
    if (manifest.knowledgePackage() == null) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_INTEGRITY_FAILURE,
          "knowledgePackage is missing for run " + runId);
    }
    return Optional.of(manifest.knowledgePackage());
  }
}
