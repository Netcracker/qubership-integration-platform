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

package org.qubership.integration.platform.engine.openapi;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.datasource.FlywayInitializer;
import org.qubership.integration.platform.engine.configuration.opensearch.OpenSearchInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Generates the OpenAPI specification for this service and writes it to {@code api-spec/}
 * at the module root. Every external dependency is mocked or stubbed:
 *
 * <ul>
 *     <li>Consul is pointed at a local HTTP stub that always answers "not found."
 *     <li>The {@code development} Spring profile is active, the same one this service's own
 *         README uses to run it locally with minimum dependencies. Among other things, it
 *         excludes OpenTelemetry's JDBC instrumentation, which otherwise wraps the checkpoint
 *         and Quartz datasources in a proxy that breaks Camel's Quartz configuration (it reads
 *         the JDBC URL directly off the datasource instance, so it needs to see a real
 *         {@code HikariDataSource}, not a proxy or a mock). It also deactivates the
 *         {@code BlueGreenStatePublisher} bean, which otherwise blocks startup for 30 seconds
 *         waiting for a real Consul to report blue-green readiness.
 *     <li>The checkpoint and Quartz datasources still need their fail-fast connection check
 *         disabled, since they're real, unconnected {@code HikariDataSource} instances.
 *     <li>{@link FlywayInitializer} is a Mockito mock, so it never tries to migrate those
 *         datasources.
 *     <li>{@link OpenSearchInitializer} is a Mockito mock, so startup does not wait for an
 *         OpenSearch that is not there.
 * </ul>
 *
 * <p>Runs as part of the normal test suite, so every {@code mvn test} keeps
 * {@code api-spec/openapi.yaml} up to date. See README.md for the command to run just this test.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "NAMESPACE=local",
        "CONSUL_ADMIN_TOKEN=not-required",
        "CONSUL_URL=http://127.0.0.1:18501",
        "spring.flyway.enabled=false",
        "db.hikari.datasources.qrtz-datasource.initialization-fail-timeout=-1",
        "db.hikari.datasources.checkpoints-datasource.initialization-fail-timeout=-1",
        "springdoc.writer-with-order-by-keys=true"
})
@ActiveProfiles("development")
class OpenApiSpecGeneratorTest {

    private static final Path OUTPUT_DIR = Path.of("api-spec");
    @SuppressWarnings("unused")
    private static final HttpServer CONSUL_STUB = startConsulStub();

    @MockitoBean
    private FlywayInitializer flywayInitializer;

    @MockitoBean
    private OpenSearchInitializer openSearchInitializer;

    @Autowired
    private TestRestTemplate restTemplate;

    private static HttpServer startConsulStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18501), 0);
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
        String rawYaml = restTemplate.getForObject("/v3/api-docs.yaml", String.class);
        Files.writeString(OUTPUT_DIR.resolve("openapi.yaml"), rawYaml);
    }
}
