package org.qubership.integration.platform.engine.service.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestingContextTest {

    // Same value and literal as goldenTestingContextHeader in testing-service/internal/model/testing_context_test.go.
    // A change on either side means the engine and the testing service no longer agree on the wire format.
    private static final TestingContext GOLDEN_CONTEXT =
            new TestingContext("chain-1", "element-1", "/orders/{orderId}", "/orders/42");
    private static final String GOLDEN_HEADER = "eyJjaGFpbklkIjoiY2hhaW4tMSIsImVsZW1lbnRJZCI6ImVsZW1lbnQtMSIsIm9wZXJ"
            + "hdGlvblBhdGgiOiIvb3JkZXJzL3tvcmRlcklkfSIsInBhdGgiOiIvb3JkZXJzLzQyIn0=";

    // The golden header above encodes the same under the standard and the URL-safe alphabets, so it cannot catch
    // Base64.getUrlEncoder(). This one contains + and /, and the same Go test file decodes it.
    private static final TestingContext ALPHABET_CONTEXT = new TestingContext(
            "chain-1", "element-1", "/orders/{orderId}", "/orders/7?status=NEW&filter=price>100");
    private static final String ALPHABET_HEADER = "eyJjaGFpbklkIjoiY2hhaW4tMSIsImVsZW1lbnRJZCI6ImVsZW1lbnQtMSIsIm9wZ"
            + "XJhdGlvblBhdGgiOiIvb3JkZXJzL3tvcmRlcklkfSIsInBhdGgiOiIvb3JkZXJzLzc/c3RhdHVzPU5FVyZmaWx0ZXI9cHJpY2U+MTAwIn0=";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Pinned as a literal, as TestTestingContextHeaderNameIsFixed pins it on the Go side: a rename here would
    // leave every suite green while the testing service answers 404 for every call.
    @Test
    void namesTheHeaderTheTestingServiceReads() {
        assertEquals("Testing-Service-Context", TestingContext.HEADER_NAME);
    }

    @Test
    void encodesTheGoldenHeader() {
        assertEquals(GOLDEN_HEADER, GOLDEN_CONTEXT.encode());
    }

    @Test
    void encodesWithTheStandardPaddedAlphabet() {
        String encoded = ALPHABET_CONTEXT.encode();

        assertEquals(ALPHABET_HEADER, encoded);
        assertTrue(encoded.contains("+"), "expected the standard alphabet, got " + encoded);
        assertTrue(encoded.contains("/"), "expected the standard alphabet, got " + encoded);
        assertTrue(encoded.endsWith("="), "expected padding, got " + encoded);
        assertEquals(1, encoded.lines().count(), "expected a single line, got " + encoded);
    }

    @Test
    void writesTheFieldsInTheOrderTheGoSideDeclaresThem() {
        assertEquals(
                "{\"chainId\":\"chain-1\",\"elementId\":\"element-1\","
                        + "\"operationPath\":\"/orders/{orderId}\",\"path\":\"/orders/42\"}",
                decode(GOLDEN_CONTEXT.encode()));
    }

    @Test
    void keepsTheQueryStringInThePathField() {
        String path = "/orders/42?status=NEW&limit=10";

        assertEquals(path, field(new TestingContext("chain-1", "element-1", "/orders/{orderId}", path), "path"));
    }

    @Test
    void keepsTheTemplateInTheOperationPathField() {
        String operationPath = "/orders/{orderId}/items/{itemId}";

        assertEquals(operationPath,
                field(new TestingContext("chain-1", "element-1", operationPath, "/orders/42/items/7"), "operationPath"));
    }

    @Test
    void writesNullFieldsAsJsonNull() {
        assertEquals("{\"chainId\":null,\"elementId\":null,\"operationPath\":null,\"path\":null}",
                decode(new TestingContext(null, null, null, null).encode()));
    }

    @Test
    void writesEmptyFieldsAsEmptyStrings() {
        assertEquals("{\"chainId\":\"\",\"elementId\":\"\",\"operationPath\":\"\",\"path\":\"\"}",
                decode(new TestingContext("", "", "", "").encode()));
    }

    @Test
    void escapesCharactersThatJsonCannotCarryVerbatim() {
        TestingContext context = new TestingContext(
                "chain \"one\"", "element\\1", "/orders/{order\tId}", "/orders/42?q=a\nb");

        assertEquals("chain \"one\"", field(context, "chainId"));
        assertEquals("element\\1", field(context, "elementId"));
        assertEquals("/orders/{order\tId}", field(context, "operationPath"));
        assertEquals("/orders/42?q=a\nb", field(context, "path"));
    }

    @Test
    void encodesNonAsciiAsUtf8() {
        String path = "/commandes/42?q=café";

        assertEquals(path, field(new TestingContext("chain-1", "element-1", "/commandes/{orderId}", path), "path"));
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String field(TestingContext context, String name) {
        try {
            JsonNode node = MAPPER.readTree(decode(context.encode()));
            return node.get(name).asText();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
