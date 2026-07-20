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

package org.qubership.integration.platform.parsers.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.parsers.model.ParsedEnvironment;
import org.qubership.integration.platform.parsers.model.ParsedSystemModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins how the parser turns declared servers into environments. These assertions are the regression
 * oracle for the URL-placeholder stripping and server-name extraction that moved out of the catalog
 * wrapper: address and name must match what the wrapper's {@code getUrlWithoutPlaceHolders} produced.
 */
class SwaggerSpecificationParserEnvironmentTest extends AbstractSwaggerSpecificationParserTest {

    @Test
    @DisplayName("Server variables resolve to their defaults and surrounding slashes are stripped")
    void resolvesServerVariablesAndStripsSlashes() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Env test", "version": "1.0.0"},
                  "servers": [
                    {
                      "url": "https://{host}/api/v1/",
                      "description": "Primary environment",
                      "variables": {"host": {"default": "example.com"}}
                    },
                    {"url": "https://plain.example.com/"}
                  ],
                  "paths": {}
                }
                """;

        ParsedSystemModel model = parse(spec);

        assertEquals(2, model.getEnvironments().size());

        ParsedEnvironment primary = model.getEnvironments().get(0);
        assertEquals("Primary environment", primary.getName());
        assertEquals("https://example.com/api/v1", primary.getAddress());

        // A server without a description carries no name; the catalog wrapper fills the fallback.
        ParsedEnvironment plain = model.getEnvironments().get(1);
        assertNull(plain.getName());
        assertEquals("https://plain.example.com", plain.getAddress());
    }

    @Test
    @DisplayName("A specification with no servers maps the parser's default server to a blank address")
    void noServersMapDefaultServerToBlankAddress() {
        // OpenAPI injects a default server of "/" when the document declares none, so the model
        // always holds one environment; stripping the slash leaves a blank address.
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "No servers", "version": "1.0.0"},
                  "paths": {}
                }
                """;

        ParsedSystemModel model = parse(spec);

        assertEquals(1, model.getEnvironments().size());
        ParsedEnvironment environment = model.getEnvironments().get(0);
        assertNull(environment.getName());
        assertEquals("", environment.getAddress());
    }
}
