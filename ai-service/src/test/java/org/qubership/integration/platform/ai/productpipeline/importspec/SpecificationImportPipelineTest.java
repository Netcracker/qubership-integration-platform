package org.qubership.integration.platform.ai.productpipeline.importspec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecAttachment;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportOutcome;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;

class SpecificationImportPipelineTest {
  @Test
  void keepsPartialFailuresAndCatalogIdsAfterReload() {
    var gateway = mock(CatalogMutationGateway.class);
    var blobs = new InMemoryArtifactBlobStore();
    var pipeline = new SpecificationImportPipeline(gateway, blobs, new ObjectMapper());
    var good = new UploadedSpecAttachment("uploads/good.json", "good.json");
    var bad = new UploadedSpecAttachment("uploads/bad.json", "bad.json");
    when(gateway.importUploadedSpec("chat", good, "EXTERNAL")).thenReturn(Uni.createFrom().item(
        new UploadedSpecImportOutcome(good.s3Key(), "sys", "group", "spec", true)));
    when(gateway.importUploadedSpec("chat", bad, "INTERNAL"))
        .thenReturn(Uni.createFrom().failure(
            new org.qubership.integration.platform.ai.integration.catalog.client.CatalogNonRetryableResponseException(
                jakarta.ws.rs.core.Response.status(400).build(),
                "{\"errorMessage\":\"Specification version 1.0 already exists\"}")));

    var result = pipeline.execute("chat", List.of(good, bad), Map.of(good.s3Key(), "EXTERNAL"), "INTERNAL")
        .await().indefinitely();

    assertFalse(result.succeeded());
    assertEquals(2, result.items().size());
    assertEquals("spec", result.imported().getFirst().specificationId());
    assertTrue(result.failures().contains("Specification version 1.0 already exists"));
    var reloaded = new SpecificationImportPipeline(gateway, blobs, new ObjectMapper());
    assertEquals(result, reloaded.result("chat").orElseThrow());
  }

  @Test
  void rejectsUnsafeKeysWithoutCallingTheCatalog() {
    var gateway = mock(CatalogMutationGateway.class);
    var pipeline = new SpecificationImportPipeline(gateway, new InMemoryArtifactBlobStore(), new ObjectMapper());
    var result = pipeline.execute("chat", List.of(new UploadedSpecAttachment("../api.json", "api.json")), Map.of(), "INTERNAL")
        .await().indefinitely();
    assertFalse(result.succeeded());
    verifyNoInteractions(gateway);
  }
}
