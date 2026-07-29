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

package org.qubership.integration.platform.runtime.catalog.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.configuration.datasource.FlywayInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Generates the OpenAPI specification for this service and writes it to {@code api-spec/}
 * at the module root. Every external dependency is mocked or stubbed: the datasource and the
 * Flyway initializer are Mockito mocks, and Consul is pointed at a local HTTP stub that always
 * answers "not found," so the real controllers load into the context without any external
 * infrastructure.
 *
 * <p>The spec is fetched as YAML, parsed into a generic {@code Map}/{@code List} tree, and
 * written back out from that tree with
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} enabled, which sorts every nested
 * object's keys alphabetically; {@link #assertKeysSorted} then walks the tree to catch any
 * regression of that behavior.
 *
 * <p>The class name deliberately doesn't end in {@code Test} so Surefire's default include
 * pattern skips it. To run it manually, see the command in README.md.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "NAMESPACE=local",
        "CONSUL_ADMIN_TOKEN=not-required",
        "CONSUL_URL=http://127.0.0.1:18500",
        "spring.flyway.enabled=false"
})
class OpenApiSpecGenerator {

    private static final Path OUTPUT_DIR = Path.of("api-spec");
    private static final HttpServer CONSUL_STUB = startConsulStub();
    private static final ObjectMapper YAML_MAPPER = new YAMLMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private FlywayInitializer flywayInitializer;

    @Autowired
    private TestRestTemplate restTemplate;

    private static HttpServer startConsulStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18500), 0);
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
    @DisplayName("generate OpenAPI specification file with alphabetically sorted object keys")
    void generateOpenApiSpecFile() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        String rawYaml = restTemplate.getForObject("/v3/api-docs.yaml", String.class);
        Object spec = YAML_MAPPER.readValue(rawYaml, Object.class);

        String sortedYaml = YAML_MAPPER.writeValueAsString(spec);
        Files.writeString(OUTPUT_DIR.resolve("openapi.yaml"), sortedYaml);

        // ORDER_MAP_ENTRIES_BY_KEYS only reorders keys while writing, so re-read the file we
        // just wrote rather than the original (still insertion-ordered) parsed object.
        assertKeysSorted(YAML_MAPPER.readValue(sortedYaml, Object.class));
    }

    private static void assertKeysSorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            List<String> keys = map.keySet().stream().map(Object::toString).toList();
            assertEquals(keys.stream().sorted().toList(), keys, () -> "unsorted keys: " + keys);
            map.values().forEach(OpenApiSpecGenerator::assertKeysSorted);
        } else if (node instanceof List<?> list) {
            list.forEach(OpenApiSpecGenerator::assertKeysSorted);
        }
    }
}
