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

package org.qubership.integration.platform.runtime.catalog.service.deployment.properties.builders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpTriggerPropertiesBuilderTest {

    private McpTriggerPropertiesBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new McpTriggerPropertiesBuilder();
    }

    // applicableTo tests

    @Test
    @DisplayName("applicableTo returns true for mcp-trigger element type")
    void applicableToMcpTriggerTypeReturnsTrue() {
        ChainElement element = ChainElement.builder().type("mcp-trigger").build();
        assertTrue(builder.applicableTo(element));
    }

    @Test
    @DisplayName("applicableTo returns false for non-mcp-trigger element type")
    void applicableToOtherTypeReturnsFalse() {
        ChainElement element = ChainElement.builder().type("http-trigger").build();
        assertFalse(builder.applicableTo(element));
    }

    @Test
    @DisplayName("applicableTo returns false for null element type")
    void applicableToNullTypeReturnsFalse() {
        ChainElement element = ChainElement.builder().build();
        assertFalse(builder.applicableTo(element));
    }

    // build tests

    @Test
    @DisplayName("build returns all expected keys")
    void buildAllExpectedKeysPresent() {
        ChainElement element = ChainElement.builder().type("mcp-trigger").build();

        Map<String, String> result = builder.build(element);

        assertThat(result.keySet(), containsInAnyOrder(
                "mcpServiceIds", "name", "title", "description",
                "inputSchema", "outputSchema", "readOnly", "destructive",
                "idempotent", "openWorld", "requiresLocal"
        ));
    }

    @Test
    @DisplayName("build maps element properties to string values")
    void buildWithAllPropertiesMapsCorrectly() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("mcpServiceIds", "service-123");
        properties.put("name", "my-tool");
        properties.put("title", "My Tool");
        properties.put("description", "Does something");
        properties.put("inputSchema", "{\"type\":\"object\"}");
        properties.put("outputSchema", "{\"type\":\"string\"}");
        properties.put("readOnly", true);
        properties.put("destructive", false);
        properties.put("idempotent", true);
        properties.put("openWorld", false);
        properties.put("requiresLocal", true);

        ChainElement element = ChainElement.builder()
                .type("mcp-trigger")
                .properties(properties)
                .build();

        Map<String, String> result = builder.build(element);

        assertThat(result, allOf(
                hasEntry("mcpServiceIds", "service-123"),
                hasEntry("name", "my-tool"),
                hasEntry("title", "My Tool"),
                hasEntry("description", "Does something"),
                hasEntry("inputSchema", "{\"type\":\"object\"}"),
                hasEntry("outputSchema", "{\"type\":\"string\"}"),
                hasEntry("readOnly", "true"),
                hasEntry("destructive", "false"),
                hasEntry("idempotent", "true"),
                hasEntry("openWorld", "false"),
                hasEntry("requiresLocal", "true")
        ));
    }

    @Test
    @DisplayName("build returns empty string for missing properties")
    void buildWithNoPropertiesReturnsEmptyStrings() {
        ChainElement element = ChainElement.builder().type("mcp-trigger").build();

        Map<String, String> result = builder.build(element);

        assertThat(result.values(), everyItem(equalTo("")));
    }

    @Test
    @DisplayName("build returns empty string for null property value")
    void buildWithNullPropertyValueReturnsEmptyString() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", null);

        ChainElement element = ChainElement.builder()
                .type("mcp-trigger")
                .properties(properties)
                .build();

        Map<String, String> result = builder.build(element);

        assertThat(result, hasEntry("name", ""));
    }

    @Test
    @DisplayName("build converts non-string property values using toString")
    void buildWithIntegerPropertyValueConvertsToString() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("mcpServiceIds", 42);

        ChainElement element = ChainElement.builder()
                .type("mcp-trigger")
                .properties(properties)
                .build();

        Map<String, String> result = builder.build(element);

        assertThat(result, hasEntry("mcpServiceIds", "42"));
    }

    @Test
    @DisplayName("build returns exactly 11 entries")
    void buildReturnsExactly11Entries() {
        ChainElement element = ChainElement.builder().type("mcp-trigger").build();

        Map<String, String> result = builder.build(element);

        assertThat(result.size(), equalTo(11));
    }
}
