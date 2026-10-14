package org.qubership.integration.platform.ai.integration.catalog.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogChainPublicationService;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;

@QuarkusTest
@QuarkusTestResource(value = CatalogMutationRetryIT.Endpoint.class, restrictToAnnotatedClass = true)
class CatalogMutationRetryIT {

  @Inject CatalogChainPublicationService publication;
  @Inject @RestClient CatalogRestClient catalog;

  @BeforeEach
  void reset() {
    Endpoint.chainCreates.set(0);
    Endpoint.elementCreates.set(0);
    Endpoint.snapshotCreates.set(0);
    Endpoint.deploymentCreates.set(0);
  }

  @Test
  void committedChainWithFailedResponseIsReadBackBeforeAnySecondCreate() {
    String chainId = publication.resolveOrCreate("unknown-outcome", "Retry fixture", "");

    assertEquals("chain-1", chainId);
    assertEquals(1, Endpoint.chainCreates.get());
  }

  @Test
  void snapshotCreateFailureDoesNotRepeatAnUnresolvedWrite() {
    assertThrows(RuntimeException.class, () -> catalog.createSnapshot("chain-1"));

    assertEquals(1, Endpoint.snapshotCreates.get());
  }

  @Test
  void deploymentCreateFailureDoesNotRepeatAnUnresolvedWrite() {
    assertThrows(RuntimeException.class, () -> catalog.createDeployment("chain-1",
        new CatalogRestClient.CreateDeploymentRequest("default", "snapshot-1")));

    assertEquals(1, Endpoint.deploymentCreates.get());
  }

  @Test
  void elementCreateFailureDoesNotRepeatAnUnresolvedWrite() {
    assertThrows(RuntimeException.class,
        () -> catalog.createElement("chain-1", new CatalogCreateElementRequest("script", null, null)));

    assertEquals(1, Endpoint.elementCreates.get());
  }

  public static class Endpoint implements QuarkusTestResourceLifecycleManager {
    static final AtomicInteger chainCreates = new AtomicInteger();
    static final AtomicInteger elementCreates = new AtomicInteger();
    static final AtomicInteger snapshotCreates = new AtomicInteger();
    static final AtomicInteger deploymentCreates = new AtomicInteger();
    private HttpServer server;

    @Override
    public Map<String, String> start() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot start the catalog fault fixture.", failure);
      }
      server.createContext("/v1/", exchange -> {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        String body;
        int status = 200;
        if (path.equals("/v1/folders/search")) {
          body = chainCreates.get() == 0 ? "[]" : """
              [{"id":"chain-1","name":"Retry fixture","itemType":"CHAIN",
                "labels":[{"name":"qip-ai-publication-attempt:unknown-outcome","technical":true}]}]
              """;
        } else if (path.equals("/v1/chains")) {
          int created = chainCreates.incrementAndGet();
          status = created == 1 ? 503 : 201;
          body = created == 1 ? "{\"errorMessage\":\"Response failed after commit\"}"
              : "{\"id\":\"chain-" + created + "\",\"name\":\"Retry fixture\"}";
        } else if (path.equals("/v1/chains/chain-1/elements")) {
          elementCreates.incrementAndGet();
          status = 503;
          body = "{\"errorMessage\":\"Response failed after commit\"}";
        } else if (path.equals("/v1/catalog/chains/chain-1/snapshots")) {
          snapshotCreates.incrementAndGet();
          status = 503;
          body = "{\"errorMessage\":\"Response failed after commit\"}";
        } else if (path.equals("/v1/catalog/chains/chain-1/deployments")) {
          deploymentCreates.incrementAndGet();
          status = 503;
          body = "{\"errorMessage\":\"Response failed after commit\"}";
        } else {
          status = 404;
          body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
          output.write(bytes);
        }
        exchange.close();
      });
      server.start();
      return Map.of("quarkus.rest-client.catalog-api.url",
          "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void stop() {
      if (server != null) {
        server.stop(0);
      }
    }
  }
}
