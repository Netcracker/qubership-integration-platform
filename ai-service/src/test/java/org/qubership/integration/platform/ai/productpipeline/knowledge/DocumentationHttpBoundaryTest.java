package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import io.quarkus.test.junit.QuarkusTest;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;

@QuarkusTest
@EnabledIfSystemProperty(named = "qip.documentation.test-url", matches = ".+")
class DocumentationHttpBoundaryTest {
  @Test
  void longAuthoringRequestStillLoadsDocumentationThroughTheHttpContract() throws Exception {
    String url = System.getProperty("qip.documentation.test-url");
    assumeTrue(url != null, "Set qip.documentation.test-url to an isolated documentation sidecar");
    var api = RestClientBuilder.newBuilder().baseUri(URI.create(url)).build(KnowledgeSidecarApi.class);
    try {
      var json = new ObjectMapper().findAndRegisterModules();
      var blobs = new InMemoryArtifactBlobStore();
      var tools = new DocumentationTools(new SidecarKnowledgeClient(api),
          new CompilationArtifacts(blobs, json, Clock.systemUTC()),
          new CompilationSessions(blobs, json, Clock.systemUTC()), json);
      String context = tools.context("long-authoring-request", "http trigger " + "details ".repeat(400),
          List.of("http-trigger"));
      assertTrue(context.contains("/doc/"), context);
    } finally {
      ((AutoCloseable) api).close();
    }
  }

  @Test
  void productionClientSearchesAndReadsPinnedPublicCorpusOverHttp() throws Exception {
    String url = System.getProperty("qip.documentation.test-url");
    assumeTrue(url != null, "Set qip.documentation.test-url to an isolated documentation sidecar");
    var api = RestClientBuilder.newBuilder().baseUri(URI.create(url)).build(KnowledgeSidecarApi.class);
    try {
      var client = new SidecarKnowledgeClient(api);
      var manifest = client.documentationManifest();
      var search = client.searchDocumentation(manifest.checksum(), "http trigger", List.of("http-trigger"), 5);
      assertFalse(search.results().isEmpty(), "The public corpus must describe HTTP Trigger");
      var first = search.results().getFirst();
      var page = client.readDocumentation(manifest.checksum(), first.id(), null, 256);
      assertAll(
          () -> assertEquals(manifest.checksum(), page.checksum()),
          () -> assertEquals(first.path(), page.path()),
          () -> assertTrue(page.url().startsWith("/doc/"), page.url()),
          () -> assertEquals(page.endByte() - page.startByte(), page.text().getBytes(StandardCharsets.UTF_8).length));
      if (!page.complete()) {
        var next = client.readDocumentation(manifest.checksum(), first.id(), page.nextCursor(), 256);
        assertEquals(page.endByte(), next.startByte());
      }
    } finally {
      ((AutoCloseable) api).close();
    }
  }
}
