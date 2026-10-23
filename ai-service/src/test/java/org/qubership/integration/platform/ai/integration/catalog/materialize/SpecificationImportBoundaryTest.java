package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;

/** Exercises production MCP, multipart import, polling, and source readback without a model. */
@QuarkusTest
@QuarkusTestResource(value = SpecificationImportBoundaryTest.Endpoint.class, restrictToAnnotatedClass = true)
class SpecificationImportBoundaryTest {

  private static final String DOCUMENT =
      "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Import fixture\",\"version\":\"2\"},\"paths\":{}}";

  @Inject CatalogSpecificationImporter importer;
  @Inject ApiHubMcpTools apiHub;

  @BeforeEach
  void reset() {
    Endpoint.upload = "";
    Endpoint.query = "";
    Endpoint.contentType = "";
    Endpoint.importPath = "";
    Endpoint.wrongReadback = false;
    Endpoint.mcpArguments = "";
  }

  @Test
  void uploadedFileUsesMultipartAndReturnsTheImportedSourceVersion() {
    var result = importer.importOpenApiDocument("system", "Fixture", null,
        DOCUMENT.getBytes(StandardCharsets.UTF_8), "fixture.json");

    assertEquals("new", result.specificationId());
    assertEquals("group", result.specificationGroupId());
    assertEquals("/v1/specificationGroups/import", Endpoint.importPath);
    assertTrue(Endpoint.query.contains("systemId=system"));
    assertTrue(Endpoint.contentType.startsWith("multipart/form-data"));
    assertTrue(Endpoint.upload.contains("name=\"files\""));
    assertTrue(Endpoint.upload.contains(DOCUMENT));
  }

  @Test
  void reimportUsesTheExistingGroupAndDoesNotSelectTheOlderVersion() {
    var result = importer.importOpenApiDocumentIntoGroup("system", "group",
        DOCUMENT.getBytes(StandardCharsets.UTF_8), "fixture.json");

    assertEquals("new", result.specificationId());
    assertEquals("/v1/import", Endpoint.importPath);
    assertEquals("specificationGroupId=group", Endpoint.query);
  }

  @Test
  void apiHubDocumentTravelsThroughTheRealMcpAndCatalogClients() {
    var document = apiHub.fetchApiHubDocument("fixture.package", "2026.1", "api", "rest");
    var result = importer.importOpenApiDocument("system", "Fixture", null,
        document.content(), document.fileName());

    assertEquals("new", result.specificationId());
    assertTrue(Endpoint.mcpArguments.contains("fixture.package"));
    assertTrue(Endpoint.mcpArguments.contains("2026.1"));
    assertTrue(Endpoint.upload.contains(DOCUMENT));
  }

  @Test
  void acknowledgedImportWithTheWrongSourceCannotPassReadback() {
    Endpoint.wrongReadback = true;

    var error = assertThrows(IllegalStateException.class, () ->
        importer.importOpenApiDocument("system", "Fixture", null,
            DOCUMENT.getBytes(StandardCharsets.UTF_8), "fixture.json"));

    assertTrue(error.getMessage().contains("source was not found"));
  }

  public static class Endpoint implements QuarkusTestResourceLifecycleManager {
    static volatile String upload;
    static volatile String query;
    static volatile String contentType;
    static volatile String importPath;
    static volatile String mcpArguments;
    static volatile boolean wrongReadback;
    private HttpServer server;

    @Override
    public Map<String, String> start() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException error) {
        throw new IllegalStateException("Cannot start the import boundary fixture.", error);
      }
      server.createContext("/", exchange -> {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String body;
        int status = 200;
        if (path.equals("/mcp")) {
          var rpc = new ObjectMapper().readTree(request);
          String result;
          if (rpc.path("method").asText().equals("tools/call")) {
            mcpArguments = rpc.path("params").path("arguments").toString();
            result = "{\"structuredContent\":{\"format\":\"json\",\"documentData\":" + DOCUMENT + "}}";
          } else {
            result = "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
          }
          body = "{\"jsonrpc\":\"2.0\",\"id\":" + rpc.path("id") + ",\"result\":" + result + "}";
          exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session");
        } else if (exchange.getRequestMethod().equals("POST")) {
          upload = request;
          query = exchange.getRequestURI().getRawQuery();
          contentType = exchange.getRequestHeaders().getFirst("Content-Type");
          importPath = path;
          body = "{\"id\":\"import-1\",\"specificationGroupId\":\"group\",\"done\":false}";
          status = 202;
        } else if (path.equals("/v1/import/import-1")) {
          body = "{\"id\":\"import-1\",\"done\":true,\"specificationGroupId\":\"group\"}";
        } else if (path.equals("/v1/models")) {
          body = "[{\"id\":\"old\",\"specificationGroupId\":\"group\"},{\"id\":\"new\",\"specificationGroupId\":\"group\"}]";
        } else if (path.startsWith("/v1/models/")) {
          body = path.contains("/old/") || wrongReadback ? DOCUMENT.replace("\"2\"", "\"1\"") : DOCUMENT;
        } else {
          body = "{}";
          status = 404;
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
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      return Map.of("quarkus.rest-client.catalog-api.url", url,
          "quarkus.rest-client.apihub-mcp.url", url + "/mcp");
    }

    @Override
    public void stop() {
      if (server != null) {
        server.stop(0);
      }
    }
  }
}
