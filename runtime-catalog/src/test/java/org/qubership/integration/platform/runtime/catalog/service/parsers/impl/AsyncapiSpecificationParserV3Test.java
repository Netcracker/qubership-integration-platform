package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.service.parsers.asyncapi.AsyncApiV3Normalizer;
import org.qubership.integration.platform.runtime.catalog.service.parsers.preprocessing.SpecificationPreprocessing;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSchemaResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.AsyncApiSpecificationResolver;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.async.impl.KafkaSpecificationResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AsyncapiSpecificationParserV3Test {

    private AsyncapiSpecificationParser parser;

    @BeforeEach
    void setUp() {
        ObjectMapper jsonMapper = new ObjectMapper();
        YAMLMapper yamlMapper = new YAMLMapper();
        AsyncApiV3Normalizer normalizer = new AsyncApiV3Normalizer(jsonMapper);
        SpecificationPreprocessing preprocessing = new SpecificationPreprocessing(normalizer, jsonMapper, yamlMapper);
        parser = new AsyncapiSpecificationParser(
                null, null, null, preprocessing, Collections.emptyList());
    }

    @Test
    void readV3YamlSpec() throws Exception {
        String data = readResource("asyncapi/v3/kafka-v3-simple.yaml");
        AsyncapiSpecification spec = parser.read(data);

        assertNotNull(spec.getChannels());
        assertTrue(spec.getChannels().containsKey("user/signedup"));
        assertNotNull(spec.getChannels().get("user/signedup").getPublish());
    }

    @Test
    void readV3JsonSpec() throws Exception {
        String data = readResource("asyncapi/v3/kafka-v3-no-servers.json");
        AsyncapiSpecification spec = parser.read(data);

        assertNotNull(spec.getChannels());
        assertTrue(spec.getChannels().containsKey("events/all"));
    }

    @Test
    void readV2SpecStillWorks() throws Exception {
        String v2Yaml = """
                asyncapi: 2.6.0
                info:
                  title: Test
                  version: 1.0.0
                channels:
                  test/topic:
                    publish:
                      operationId: testOp
                """;
        AsyncapiSpecification spec = parser.read(v2Yaml);

        assertNotNull(spec.getChannels());
        assertTrue(spec.getChannels().containsKey("test/topic"));
        Channel channel = spec.getChannels().get("test/topic");
        assertNotNull(channel.getPublish());
        assertEquals("testOp", channel.getPublish().getOperationId());
    }

    @Test
    void readV3MultiOperationSpec() throws Exception {
        String data = readResource("asyncapi/v3/kafka-v3-multi-operation.yaml");
        AsyncapiSpecification spec = parser.read(data);

        Channel channel = spec.getChannels().get("user/events");
        assertNotNull(channel);
        assertNotNull(channel.getPublish());
        assertNotNull(channel.getSubscribe());
    }

    @Test
    void readV3RequestReplySpec() throws Exception {
        String data = readResource("asyncapi/v3/kafka-v3-request-reply.yaml");
        AsyncapiSpecification spec = parser.read(data);

        assertTrue(spec.getChannels().containsKey("order/request"));
        assertTrue(spec.getChannels().containsKey("order/reply"));
    }

    @Test
    void resolveSpecificationResolverRejectsNullProtocol() {
        SpecificationImportException ex = assertThrows(SpecificationImportException.class,
                () -> parser.resolveSpecificationResolver(null));
        assertTrue(ex.getMessage().toLowerCase().contains("protocol is not set"));
    }

    @Test
    void resolveSpecificationResolverRejectsUnsupportedProtocol() {
        SpecificationImportException ex = assertThrows(SpecificationImportException.class,
                () -> parser.resolveSpecificationResolver(OperationProtocol.HTTP));
        assertTrue(ex.getMessage().contains("'http'"));
        assertTrue(ex.getMessage().toLowerCase().contains("not supported"));
    }

    @Test
    void resolveSpecificationResolverReturnsRegisteredResolver() {
        KafkaSpecificationResolver kafkaResolver = new KafkaSpecificationResolver(null);
        AsyncapiSpecificationParser parserWithResolvers = parserWith(kafkaResolver);

        assertSame(kafkaResolver, parserWithResolvers.resolveSpecificationResolver(OperationProtocol.KAFKA));
    }

    @Test
    void resolveSpecificationResolverErrorListsKnownProtocols() {
        AsyncapiSpecificationParser parserWithResolvers = parserWith(new KafkaSpecificationResolver(null));

        SpecificationImportException ex = assertThrows(SpecificationImportException.class,
                () -> parserWithResolvers.resolveSpecificationResolver(OperationProtocol.HTTP));
        assertTrue(ex.getMessage().contains("kafka"));
    }

    @Test
    void parseOperationsWithSchemasPopulatesMessageSchemas() throws Exception {
        AsyncapiSpecificationParser parserWithResolver =
                parserWith(new KafkaSpecificationResolver(new AsyncApiSchemaResolver()));
        String data = readResource("asyncapi/v3/kafka-v3-simple.yaml");

        List<Operation> operations = parserWithResolver.parseOperations(data, OperationProtocol.KAFKA, true);

        assertFalse(operations.isEmpty());
        operations.forEach(operation -> {
            assertNotNull(operation.getRequestSchema());
            assertNotNull(operation.getResponseSchemas());
        });
    }

    @Test
    void parseOperationsWithoutSchemasKeepsStructureOnly() throws Exception {
        AsyncapiSpecificationParser parserWithResolver =
                parserWith(new KafkaSpecificationResolver(new AsyncApiSchemaResolver()));
        String data = readResource("asyncapi/v3/kafka-v3-simple.yaml");

        List<Operation> withSchemas = parserWithResolver.parseOperations(data, OperationProtocol.KAFKA, true);
        List<Operation> withoutSchemas = parserWithResolver.parseOperations(data, OperationProtocol.KAFKA, false);

        assertEquals(withSchemas.size(), withoutSchemas.size());
        assertFalse(withoutSchemas.isEmpty());
        for (int i = 0; i < withoutSchemas.size(); i++) {
            Operation full = withSchemas.get(i);
            Operation structural = withoutSchemas.get(i);
            assertEquals(full.getPath(), structural.getPath());
            assertEquals(full.getMethod(), structural.getMethod());
            assertEquals(full.getName(), structural.getName());
            assertEquals(full.getSpecification(), structural.getSpecification());
            assertNull(structural.getRequestSchema());
            assertNull(structural.getResponseSchemas());
        }
    }

    @Test
    void parseOperationsPopulatesTypedAsyncapiOperation() throws Exception {
        AsyncapiSpecificationParser parserWithResolver =
                parserWith(new KafkaSpecificationResolver(new AsyncApiSchemaResolver()));
        String data = readResource("asyncapi/v3/kafka-v3-simple.yaml");

        List<Operation> operations = parserWithResolver.parseOperations(data, OperationProtocol.KAFKA, false);

        assertEquals(1, operations.size());
        Operation operation = operations.getFirst();

        AsyncapiOperation typed = assertInstanceOf(AsyncapiOperation.class, operation.getTyped());
        assertEquals("user/signedup", typed.channel());
        assertEquals("Publish user signed up event", typed.summary());

        // Anti-regression: derived path is the channel and derived method equals the resolver method.
        assertEquals("user/signedup", operation.getPath());
        assertEquals(typed.channel(), operation.getPath());
        assertEquals(typed.method(), operation.getMethod());
    }

    private AsyncapiSpecificationParser parserWith(AsyncApiSpecificationResolver... resolvers) {
        ObjectMapper jsonMapper = new ObjectMapper();
        YAMLMapper yamlMapper = new YAMLMapper();
        AsyncApiV3Normalizer normalizer = new AsyncApiV3Normalizer(jsonMapper);
        SpecificationPreprocessing preprocessing = new SpecificationPreprocessing(normalizer, jsonMapper, yamlMapper);
        return new AsyncapiSpecificationParser(
                null, null, null, preprocessing, List.of(resolvers));
    }

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
