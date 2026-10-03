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
@TestConfigProperty(key = "consul.keys.prefix", value = "config/test-env")
@TestConfigProperty(key = "consul.keys.engine-config-root", value = "/qip-engine-configurations")
@TestConfigProperty(key = "consul.keys.common-variables-v2", value = "/variables/common")
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CommonVariablesUpdateParserTest {

    private static final String COMMON_VARIABLES_PATH =
            "config/test-env/qip-engine-configurations/variables/common";

    @Inject
    CommonVariablesUpdateParser parser;

    @Test
    void shouldReturnVariablesMappedByLastPathSegmentWhenEntriesContainL1Keys() {
        KeyValue firstEntry = entry(COMMON_VARIABLES_PATH + "/customerId", "123");
        KeyValue secondEntry = entry(COMMON_VARIABLES_PATH + "/orderId", "456");

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
        KeyValue firstEntry = entry(COMMON_VARIABLES_PATH + "/customerId", "   ");
        KeyValue secondEntry = entry(COMMON_VARIABLES_PATH + "/orderId", null);

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
        KeyValue validEntry = entry(COMMON_VARIABLES_PATH + "/customerId", "123");
        KeyValue nestedEntry = entry(COMMON_VARIABLES_PATH + "/customer/id", "456");
        KeyValue emptyEntry = entry(COMMON_VARIABLES_PATH + "/", "789");

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

    @Test
    void shouldExtractVariableNameContainingDotsAndDashes() {
        // regression case: a user-defined variable such as #{bss.integration.cloud-graphql-url}
        // must resolve the same way a plain name like #{customerId} does
        KeyValue variableEntry = entry(
                COMMON_VARIABLES_PATH + "/bss.integration.cloud-graphql-url", "https://example.com");

        Map<String, String> result = parser.apply(List.of(variableEntry));

        assertEquals(Map.of("bss.integration.cloud-graphql-url", "https://example.com"), result);
    }

    @Test
    void shouldIgnoreEntryWhenKeyEqualsCommonVariablesPathExactly() {
        KeyValue rootEntry = entry(COMMON_VARIABLES_PATH, "789");

        Map<String, String> result = parser.apply(List.of(rootEntry));

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldIgnoreDeeplyNestedEntryWhileKeepingSiblingVariables() {
        KeyValue firstValidEntry = entry(COMMON_VARIABLES_PATH + "/customerId", "123");
        KeyValue secondValidEntry = entry(COMMON_VARIABLES_PATH + "/orderId", "456");
        KeyValue deeplyNestedEntry = entry(COMMON_VARIABLES_PATH + "/group/nested/id", "789");

        Map<String, String> result = parser.apply(List.of(firstValidEntry, secondValidEntry, deeplyNestedEntry));

        assertEquals(
                Map.of(
                        "customerId", "123",
                        "orderId", "456"
                ),
                result
        );
    }

    @Test
    @TestConfigProperty(key = "consul.keys.common-variables-v2", value = "/variables/common/")
    void shouldStillExtractVariableWhenConfiguredPathHasTrailingSlash() {
        KeyValue variableEntry = entry(COMMON_VARIABLES_PATH + "/customerId", "123");

        Map<String, String> result = parser.apply(List.of(variableEntry));

        assertEquals(Map.of("customerId", "123"), result);
    }

    private static KeyValue entry(String key, String value) {
        return new KeyValue()
                .setKey(key)
                .setValue(value);
    }
}
