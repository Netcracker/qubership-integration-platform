package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.Channel;
import org.qubership.integration.platform.runtime.catalog.service.parsers.asyncapi.AsyncApiV3Normalizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AsyncapiSpecificationParserV3Test {

    private AsyncapiSpecificationParser parser;

    @BeforeEach
    void setUp() {
        ObjectMapper jsonMapper = new ObjectMapper();
        YAMLMapper yamlMapper = new YAMLMapper();
        AsyncApiV3Normalizer normalizer = new AsyncApiV3Normalizer(jsonMapper);
        parser = new AsyncapiSpecificationParser(
                null, null, null, normalizer, jsonMapper, yamlMapper, Collections.emptyList());
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

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
