package org.qubership.integration.platform.engine.routes.fixture;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HttpSenderSnapshotPayloadsTest {
    @Test
    void shouldRejectDifferentEncodingWhenRequestBodyBytesAreExpected() {
        Map<String, Object> properties = Map.of("expectedRequestBodyHex", "610062006300");

        HttpSenderSnapshotPayloads.assertRequestBody(properties, new byte[]{97, 0, 98, 0, 99, 0}, "UTF-16LE request");

        assertThrows(AssertionError.class, () -> HttpSenderSnapshotPayloads.assertRequestBody(
                properties, "abc".getBytes(StandardCharsets.UTF_8), "UTF-16LE request"));
        assertThrows(AssertionError.class, () -> HttpSenderSnapshotPayloads.assertRequestBody(
                properties, new byte[]{97, 0, 98, 0, 100, 0}, "UTF-16LE request"));
    }

    @Test
    void shouldRequireEmptyBodyWhenExpectedHexIsEmpty() {
        Map<String, Object> properties = Map.of("expectedRequestBodyHex", "");

        HttpSenderSnapshotPayloads.assertRequestBody(properties, new byte[0], "Empty request");

        assertThrows(AssertionError.class, () -> HttpSenderSnapshotPayloads.assertRequestBody(
                properties, new byte[]{0}, "Empty request"));
    }

    @Test
    void shouldEncodeResponseWhenBodyCharsetIsSpecified() throws Exception {
        SnapshotFixtureResponse response = response("abc", Map.of("bodyCharset", "UTF-16LE"));

        assertArrayEquals(new byte[]{97, 0, 98, 0, 99, 0}, HttpSenderSnapshotPayloads.responseBody(response));
    }

    @Test
    void shouldKeepDefaultEncodingWhenBodyCharsetIsOmitted() throws Exception {
        assertArrayEquals(new byte[]{97, 98, 99}, HttpSenderSnapshotPayloads.responseBody(response("abc", Map.of())));
        assertEquals(Map.of("value", "abc"), ObjectMappers.getObjectMapper().readValue(
                HttpSenderSnapshotPayloads.responseBody(response(Map.of("value", "abc"), Map.of())), Map.class));
        assertArrayEquals(new byte[0], HttpSenderSnapshotPayloads.responseBody(response(null, Map.of())));
    }

    @Test
    void shouldRejectInvalidHexWhenFixtureIsCreated() {
        for (Object hexadecimal : List.of("610", "xx", 610062006300L)) {
            assertThrows(IllegalArgumentException.class,
                    () -> createFixture(response("abc", Map.of("expectedRequestBodyHex", hexadecimal))));
        }
    }

    @Test
    void shouldRejectInvalidResponseCharsetWhenFixtureIsCreated() {
        for (Object charset : List.of("unknown-charset", "UTF 16LE", 16)) {
            assertThrows(IllegalArgumentException.class,
                    () -> createFixture(response("abc", Map.of("bodyCharset", charset))));
        }
        assertThrows(IllegalArgumentException.class,
                () -> createFixture(response(Map.of("value", "abc"), Map.of("bodyCharset", "UTF-16LE"))));
    }

    private static SnapshotFixtureResponse response(Object body, Map<String, Object> properties) {
        return new SnapshotFixtureResponse(200, null, body, Map.of(), properties);
    }

    private static SnapshotFixture createFixture(SnapshotFixtureResponse response) {
        SnapshotFixtureDefinition definition = new SnapshotFixtureDefinition("http", "http-sender-http", "test", "sender", null);
        SnapshotFixtureRequestExpectation expected = new SnapshotFixtureRequestExpectation(1, "POST", "/", null,
                null, null, null, Map.of(), Map.of());
        SnapshotFixtureInteraction interaction = new SnapshotFixtureInteraction("http", response, expected,
                null, null, null, null, null);
        return new HttpSenderSnapshotFixtureProvider().create("test",
                List.of(new SnapshotFixtureBinding(definition, Map.of("request", interaction))));
    }
}
