package org.qubership.integration.platform.ai.compiler.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

class RecoveryEvidenceProbeTest {
  @Test void exportsSeparateCaptureHistoryWithoutCreatingLinksOrWriting() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var runs = new ProductPipelineRunStore(blobs, mapper, Clock.systemUTC());
    runs.create(new RunSnapshot("pipeline-run", "conversation", 1L, RunStatus.RUNNING,
        "design-execution", List.of(), null));
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    String compilation = sessions.startNew("conversation");
    artifacts.append(new CompilationArtifacts.AppendCommand(compilation,
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK, "1", "capture-feedback", "1",
        Map.of("proposal", Map.of("arguments", "rejected")), List.of(), null));
    artifacts.append(new CompilationArtifacts.AppendCommand(compilation,
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK, "1", "capture-feedback", "1",
        Map.of("proposal", Map.of("arguments", "corrected")), List.of(), null));
    var readonly = readOnly(blobs);
    var result = RecoveryEvidenceProbe.collect(readonly, mapper, "conversation");
    assertEquals("pipeline-run", result.get("runId"));
    assertFalse(compilation.equals("pipeline-run"));
    assertEquals(2, ((List<?>) result.get("captureArtifacts")).size());

    runs.create(new RunSnapshot("no-history", "unlinked-capture", 1L, RunStatus.RUNNING,
        "design-execution", List.of(), null));
    var missing = RecoveryEvidenceProbe.collect(readonly, mapper, "unlinked-capture");
    assertEquals(List.of(), missing.get("captureArtifacts"));
    assertEquals(Optional.empty(), sessions.current("unlinked-capture"));
    assertThrows(UnsupportedOperationException.class, () -> readonly.put("new", new byte[0]));
  }

  private static ArtifactBlobStore readOnly(ArtifactBlobStore delegate) {
    return new ArtifactBlobStore() {
      public void put(String key, byte[] bytes) { throw new UnsupportedOperationException("Unexpected write"); }
      public Optional<byte[]> get(String key) { return delegate.get(key); }
      public List<String> list(String prefix) { return delegate.list(prefix); }
      public Optional<VersionedBlob> getVersioned(String key) { return delegate.getVersioned(key); }
      public void putIfVersion(String key, byte[] bytes, String version) {
        throw new UnsupportedOperationException("Unexpected write");
      }
    };
  }
}
