package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class HttpSenderSnapshotPayloads {
    static final String BODY_CHARSET = "bodyCharset";
    static final String EXPECTED_REQUEST_BODY_HEX = "expectedRequestBodyHex";
    private static final ObjectMapper OBJECT_MAPPER = ObjectMappers.getObjectMapper();

    private HttpSenderSnapshotPayloads() {
    }

    static void validate(SnapshotFixtureResponse response, String description) {
        Map<String, Object> properties = response.getProperties();
        if (properties.containsKey(EXPECTED_REQUEST_BODY_HEX)) {
            expectedBytes(properties.get(EXPECTED_REQUEST_BODY_HEX), description);
        }
        if (properties.containsKey(BODY_CHARSET)) {
            if (!(response.getBody() instanceof String) || !(properties.get(BODY_CHARSET) instanceof String)) {
                throw new IllegalArgumentException(description + " bodyCharset requires a charset name and a string response body.");
            }
            try {
                Charset.forName((String) properties.get(BODY_CHARSET));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(description + " bodyCharset must name a supported charset.", exception);
            }
        }
    }

    static byte[] responseBody(SnapshotFixtureResponse response) throws IOException {
        Object body = response.getBody();
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof String string) {
            String charset = (String) response.getProperties().getOrDefault(BODY_CHARSET, StandardCharsets.UTF_8.name());
            return string.getBytes(Charset.forName(charset));
        }
        return OBJECT_MAPPER.writeValueAsBytes(body);
    }

    static void assertRequestBody(Map<String, Object> properties, byte[] actual, String description) {
        if (properties.containsKey(EXPECTED_REQUEST_BODY_HEX)) {
            assertArrayEquals(expectedBytes(properties.get(EXPECTED_REQUEST_BODY_HEX), description), actual,
                    description + " has unexpected request body bytes.");
        }
    }

    private static byte[] expectedBytes(Object value, String description) {
        if (!(value instanceof String hexadecimal)) {
            throw new IllegalArgumentException(description + " expectedRequestBodyHex must be a hexadecimal string.");
        }
        try {
            return HexFormat.of().parseHex(hexadecimal);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(description + " expectedRequestBodyHex must contain complete hexadecimal byte pairs.",
                    exception);
        }
    }
}
