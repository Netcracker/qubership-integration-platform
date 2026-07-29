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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.datasource.FlywayInitializer;
import org.qubership.integration.platform.engine.opensearch.ism.converters.StringToTimeValueConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *     <li>{@link ConverterInitializer} replicates a registration that
 *         {@code IntegrationEngineApplication.main} normally performs by calling
 *         {@code SpringApplication.addListeners(...)} directly; {@code @SpringBootTest} never
 *         runs {@code main}, so without it a plain {@code @Value}-injected {@code TimeValue}
 *         field (used by the OpenSearch index rollover configuration) fails to convert from its
 *         string property.
 * </ul>
 *
 * <p>The spec is fetched once as JSON, parsed into a generic {@code Map}/{@code List} tree, and
 * written out as both JSON and YAML from that same tree so the two files stay consistent. Both
 * mappers have {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} enabled, which sorts every
 * nested object's keys alphabetically; {@link #assertKeysSorted} then walks the tree to catch
 * any regression of that behavior.
 *
 * <p>The class name deliberately doesn't end in {@code Test} so Surefire's default include
 * pattern skips it. To run it manually, see the command in README.md.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "NAMESPACE=local",
        "CONSUL_ADMIN_TOKEN=not-required",
        "CONSUL_URL=http://127.0.0.1:18501",
        "spring.flyway.enabled=false",
        "db.hikari.datasources.qrtz-datasource.initialization-fail-timeout=-1",
        "db.hikari.datasources.checkpoints-datasource.initialization-fail-timeout=-1"
})
@ActiveProfiles("development")
@ContextConfiguration(initializers = OpenApiSpecGenerator.ConverterInitializer.class)
class OpenApiSpecGenerator {

    private static final Path OUTPUT_DIR = Path.of("api-spec");
    private static final HttpServer CONSUL_STUB = startConsulStub();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final ObjectMapper YAML_MAPPER = new YAMLMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @MockitoBean
    private FlywayInitializer flywayInitializer;

    @Autowired
    private TestRestTemplate restTemplate;

    static class ConverterInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            applicationContext.getEnvironment().getConversionService()
                    .addConverter(new StringToTimeValueConverter());
        }
    }

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
    @DisplayName("generate OpenAPI specification files with alphabetically sorted object keys")
    void generateOpenApiSpecFiles() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        String rawJson = restTemplate.getForObject("/v3/api-docs", String.class);
        Object spec = JSON_MAPPER.readValue(rawJson, Object.class);

        String prettyJson = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        Files.writeString(OUTPUT_DIR.resolve("openapi.json"), prettyJson);
        Files.writeString(OUTPUT_DIR.resolve("openapi.yaml"), YAML_MAPPER.writeValueAsString(spec));

        // ORDER_MAP_ENTRIES_BY_KEYS only reorders keys while writing, so re-read the file we
        // just wrote rather than the original (still insertion-ordered) parsed object.
        assertKeysSorted(JSON_MAPPER.readValue(prettyJson, Object.class));
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
