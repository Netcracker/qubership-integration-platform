package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class DefaultKnowledgeContextProviderTest {

  static KnowledgePackageRef packageRef(String checksum) {
    return new KnowledgePackageRef(
        "fixture@1.0.0",
        "1.0.0",
        "1.0.0",
        checksum,
        "CERTIFIED",
        "sha256:certificate");
  }

  @Test
  void returnsActivePackageForLegacyConversation() {
    KnowledgePackageRef active = packageRef("sha256:active");
    ProductPipelineRunStore runStore = mock(ProductPipelineRunStore.class);
    when(runStore.loadByConversation(any())).thenReturn(Optional.empty());
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    DefaultKnowledgeContextProvider provider =
        new DefaultKnowledgeContextProvider(runStore, artifactStore, () -> active);

    assertEquals(active, provider.forConversation("legacy-1").packageRef());
    assertEquals(active, provider.forConversation(null).packageRef());
  }

  @Test
  void returnsPinnedPackageForProductRun() {
    KnowledgePackageRef active = packageRef("sha256:active");
    KnowledgePackageRef pinned = packageRef("sha256:pinned");
    CompilationArtifacts.Reference manifestRef =
        new CompilationArtifacts.Reference(
            CompilationArtifacts.Kind.RUN_MANIFEST, "manifest-1", "hash-1");
    ProductPipelineRunStore runStore = mock(ProductPipelineRunStore.class);
    when(runStore.loadByConversation("conv-1"))
        .thenReturn(
            Optional.of(
                new ProductPipelineRunDocument(
                    new RunSnapshot(
                        "run-1",
                        "conv-1",
                        1L,
                        RunStatus.RUNNING,
                        "planning",
                        List.of(),
                        manifestRef),
                    List.of(),
                    List.of(),
                    null)));

    RunManifest manifest =
        new RunManifest(
            "run-1",
            null,
            List.of(),
            "runtime",
            "create-v1",
            "1",
            "digest",
            "experimental-migration",
            "base",
            List.of(),
            "closure",
            pinned,
            "24.4",
            List.of(),
            null);
    Revision revision = mock(Revision.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    when(artifactStore.get(eq("run-1"), eq(manifestRef))).thenReturn(Optional.of(revision));
    when(artifactStore.payload(revision, RunManifest.class)).thenReturn(manifest);

    DefaultKnowledgeContextProvider provider =
        new DefaultKnowledgeContextProvider(runStore, artifactStore, () -> active);

    KnowledgeQueryContext context = provider.forConversation("conv-1");
    assertEquals(pinned, context.packageRef());
    assertEquals("sha256:pinned", context.packageRef().packageChecksum());
  }

  @Test
  void blankConversationUsesActiveSupplierEvenWhenActiveChanges() {
    AtomicReference<KnowledgePackageRef> active =
        new AtomicReference<>(packageRef("sha256:a"));
    ProductPipelineRunStore runStore = mock(ProductPipelineRunStore.class);
    when(runStore.loadByConversation(any())).thenReturn(Optional.empty());
    DefaultKnowledgeContextProvider provider =
        new DefaultKnowledgeContextProvider(
            runStore, mock(ProductPipelineArtifactStore.class), active::get);

    assertEquals(active.get(), provider.forConversation("").packageRef());
    active.set(packageRef("sha256:b"));
    assertEquals(active.get(), provider.forConversation("new-conv").packageRef());
  }
}
