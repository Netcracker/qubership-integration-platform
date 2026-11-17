/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.sessions.openapi;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Generates the OpenAPI specification for this service and writes it to {@code api-spec/}
 * at the module root. Consul is pointed at a local HTTP stub that always answers "not found,"
 * so the real controllers load into the context without any external infrastructure. The
 * OpenSearch client builds a transport lazily and never connects during context startup, so it
 * needs no mock.
 *
 * <p>Runs as part of the normal test suite, so every {@code mvn test} keeps
 * {@code api-spec/openapi.yaml} up to date. See README.md for the command to run just this test.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "NAMESPACE=local",
        "CONSUL_ADMIN_TOKEN=not-required",
        "CONSUL_URL=http://127.0.0.1:18502",
        "springdoc.writer-with-order-by-keys=true"
})
class OpenApiSpecGeneratorTest {

    private static final Path OUTPUT_DIR = Path.of("api-spec");
    @SuppressWarnings("unused")
    private static final HttpServer CONSUL_STUB = startConsulStub();

    @LocalServerPort
    private int port;

    private static HttpServer startConsulStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18502), 0);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("generate OpenAPI specification")
    void generateOpenApiSpecFile() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("openapi.yaml"), fetchApiDocsYaml());
    }

    @Test
    @DisplayName("document the canonical GET /v1/sessions/{sessionId} operation")
    void documentsCanonicalOperation() {
        Map<String, Object> sessions = documentedPaths().get("/v1/sessions/{sessionId}");
        assertThat(sessions).as("the /v1/sessions/{sessionId} path").isNotNull();
        assertThat(sessions.get("get"))
                .as("GET /v1/sessions/{sessionId} in the generated document")
                .isNotNull();
    }

    @Test
    @DisplayName("hide the proxy aliases")
    void hidesProxyAliases() {
        assertThat(documentedPaths().keySet().stream()
                .filter(path -> path.startsWith("/api") || path.startsWith("/cip"))
                .toList())
                .as("proxy alias paths in the generated document")
                .isEmpty();
    }

    private String fetchApiDocsYaml() {
        return RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri("/v3/api-docs.yaml")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> documentedPaths() {
        Map<String, Object> document = new Yaml().load(fetchApiDocsYaml());
        return (Map<String, Map<String, Object>>) document.get("paths");
    }
}
