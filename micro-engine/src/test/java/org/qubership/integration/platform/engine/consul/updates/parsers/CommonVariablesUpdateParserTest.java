package org.qubership.integration.platform.engine.consul.updates.parsers;

import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;
import io.vertx.ext.consul.KeyValue;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusComponentTest
@TestConfigProperty(key = "consul.keys.prefix", value = "config/test-env/")
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CommonVariablesUpdateParserTest {

    @Inject
    CommonVariablesUpdateParser parser;

    @Test
    void shouldReturnVariablesMappedByLastPathSegmentWhenEntriesContainL1Keys() {
        KeyValue firstEntry = entry("config/test-env/customerId", "123");
        KeyValue secondEntry = entry("config/test-env/orderId", "456");

        Map<String, String> result = parser.apply(List.of(firstEntry, secondEntry));

        assertEquals(
                Map.of(
                        "customerId", "123",
                        "orderId", "456"
                ),
                result
        );
    }

    @Test
    void shouldConvertBlankValueToEmptyStringWhenVariableValueBlank() {
        KeyValue firstEntry = entry("config/test-env/customerId", "   ");
        KeyValue secondEntry = entry("config/test-env/orderId", null);

        Map<String, String> result = parser.apply(List.of(firstEntry, secondEntry));

        assertEquals(
                Map.of(
                        "customerId", "",
                        "orderId", ""
                ),
                result
        );
    }

    @Test
    void shouldIgnoreEntriesWhenPathIsNestedOrEmpty() {
        KeyValue validEntry = entry("config/test-env/customerId", "123");
        KeyValue nestedEntry = entry("config/test-env/customer/id", "456");
        KeyValue emptyEntry = entry("config/test-env/", "789");

        Map<String, String> result = parser.apply(List.of(validEntry, nestedEntry, emptyEntry));

        assertEquals(1, result.size());
        assertEquals("123", result.get("customerId"));
        assertTrue(result.containsKey("customerId"));
    }

    @Test
    void shouldReturnEmptyMapWhenEntriesEmpty() {
        Map<String, String> result = parser.apply(List.of());

        assertTrue(result.isEmpty());
    }

    private static KeyValue entry(String key, String value) {
        return new KeyValue()
                .setKey(key)
                .setValue(value);
    }
}
