package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class KnowledgePackagePinRestartIT {

  @Test
  void oldConversationKeepsPackageAAndRejectsPackageBAfterRestart() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC);
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    CompilationArtifacts core = new CompilationArtifacts(blobs, mapper, clock);
    ProductPipelineArtifactStore artifacts = new ProductPipelineArtifactStore(core);
    ProductPipelineRunStore runs = new ProductPipelineRunStore(blobs, mapper, clock);
    KnowledgePackageRef packageA =
        packageRef("sha256:package-a");
    KnowledgePackageRef packageB =
        packageRef("sha256:package-b");
    AtomicReference<KnowledgePackageRef> active = new AtomicReference<>(packageA);
    DefaultKnowledgeContextProvider provider =
        new DefaultKnowledgeContextProvider(runs, artifacts, active::get);

    RunManifest manifest = manifest("run-a", packageA);
    Revision storedA =
        artifacts.append(
            new AppendCommand(
                "run-a",
                Kind.RUN_MANIFEST,
                "1",
                "knowledge-pin-test",
                "1",
                manifest,
                List.of(),
                null,
                provenance("run-a")));
    runs.create(
        new RunSnapshot(
            "run-a",
            "conversation-a",
            1L,
            RunStatus.RUNNING,
            "planning",
            List.of(),
            storedA.reference()));

    assertEquals(packageA, provider.forConversation("conversation-a").packageRef());
    active.set(packageB);
    Revision storedB =
        artifacts.append(
            new AppendCommand(
                "run-a",
                Kind.RUN_MANIFEST,
                "1",
                "knowledge-pin-test",
                "1",
                manifest("run-a", packageB),
                List.of(storedA.reference()),
                null,
                provenance("run-a")));
    assertNotEquals(storedA.reference(), storedB.reference());
    assertEquals(packageA, provider.forConversation("conversation-a").packageRef());

    KnowledgeQueryContext oldContext = provider.forConversation("conversation-a");
    KnowledgeClientException mismatch =
        assertThrows(
            KnowledgeClientException.class,
            () -> requireActiveChecksum(oldContext, active.get()));
    assertEquals(KnowledgeFailureKind.KNOWLEDGE_PACKAGE_PIN_MISMATCH, mismatch.kind());
    assertEquals(packageB, provider.forConversation("conversation-b").packageRef());
  }

  private static void requireActiveChecksum(
      KnowledgeQueryContext context, KnowledgePackageRef active) {
    if (!context.packageRef().packageChecksum().equals(active.packageChecksum())) {
      throw new KnowledgeClientException(
          KnowledgeFailureKind.KNOWLEDGE_PACKAGE_PIN_MISMATCH,
          "expectedPackageChecksum does not match the active package");
    }
  }

  private static RunManifest manifest(String runId, KnowledgePackageRef ref) {
    return new RunManifest(
        runId,
        null,
        List.of(),
        "product",
        "create-chain",
        "1",
        "profile-digest",
        "baseline",
        "baseline-digest",
        List.of(),
        "closure-digest",
        ref,
        "24.4",
        List.of(),
        null);
  }

  private static ArtifactProvenance provenance(String runId) {
    return new ArtifactProvenance(
        runId,
        "planning",
        "create-chain",
        "1",
        "profile-digest",
        null,
        null,
        "closure-digest");
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
}
