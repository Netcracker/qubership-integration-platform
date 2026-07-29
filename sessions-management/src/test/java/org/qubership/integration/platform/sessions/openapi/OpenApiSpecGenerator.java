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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Generates the OpenAPI specification for this service and writes it to {@code api-spec/}
 * at the module root. Consul is pointed at a local HTTP stub that always answers "not found,"
 * so the real controllers load into the context without any external infrastructure. The
 * OpenSearch client builds a transport lazily and never connects during context startup, so it
 * needs no mock.
 *
 * <p>The class name deliberately doesn't end in {@code Test} so Surefire's default include
 * pattern skips it. To run it manually, see the command in README.md.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "NAMESPACE=local",
        "CONSUL_ADMIN_TOKEN=not-required",
        "CONSUL_URL=http://127.0.0.1:18502"
})
class OpenApiSpecGenerator {

    private static final Path OUTPUT_DIR = Path.of("api-spec");
    private static final HttpServer CONSUL_STUB = startConsulStub();

    @Autowired
    private TestRestTemplate restTemplate;

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
    @DisplayName("generate OpenAPI specification files")
    void generateOpenApiSpecFiles() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        writeJson();
        writeYaml();
    }

    private void writeJson() throws IOException {
        String rawJson = restTemplate.getForObject("/v3/api-docs", String.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Object json = objectMapper.readValue(rawJson, Object.class);
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        Files.writeString(OUTPUT_DIR.resolve("openapi.json"), prettyJson);
    }

    private void writeYaml() throws IOException {
        String yaml = restTemplate.getForObject("/v3/api-docs.yaml", String.class);
        Files.writeString(OUTPUT_DIR.resolve("openapi.yaml"), yaml);
    }
}
