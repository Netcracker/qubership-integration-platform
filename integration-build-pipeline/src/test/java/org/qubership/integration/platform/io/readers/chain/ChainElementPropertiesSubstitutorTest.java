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

package org.qubership.integration.platform.io.readers.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainElementExternalEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChainElementPropertiesSubstitutorTest {

    private ChainElementPropertiesSubstitutor substitutor;

    @BeforeEach
    void setUp() {
        substitutor = new ChainElementPropertiesSubstitutor(new ObjectMapper());
    }

    @DisplayName("A null source leaves the element properties untouched")
    @Test
    void skipsWhenSourceIsNull() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("propertiesFilename", "config.json");
        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("e-1").type("http-sender").properties(properties).build();

        substitutor.enrichElementWithFileProperties(element, null);

        assertEquals("config.json", element.getProperties().get("propertiesFilename"));
    }

    @DisplayName("A groovy file is restored verbatim under the exported property name")
    @Test
    void restoresGroovyPropertyVerbatim() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("propertiesToExportInSeparateFile", "script");
        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("e-1")
                .type("http-sender")
                .propertiesFilename("script-e-1.groovy")
                .properties(properties)
                .build();
        PropertyFileSource source = name ->
                "script-e-1.groovy".equals(name) ? "return body" : null;

        substitutor.enrichElementWithFileProperties(element, source);

        assertEquals("return body", element.getProperties().get("script"));
        assertFalse(element.getProperties().containsKey("propertiesFilename"));
    }

    @DisplayName("A service-call after-script handler is restored from its file into the script property")
    @Test
    void restoresServiceCallAfterScript() {
        Map<String, Object> afterHandler = new HashMap<>();
        afterHandler.put("type", "script");
        afterHandler.put("id", "h1");
        afterHandler.put("propertiesFilename", "script-h1-sc.groovy");

        List<Map<String, Object>> after = new ArrayList<>();
        after.add(afterHandler);

        Map<String, Object> properties = new HashMap<>();
        properties.put("after", after);

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("sc-1")
                .type("service-call")
                .properties(properties)
                .build();
        PropertyFileSource source = name ->
                "script-h1-sc.groovy".equals(name) ? "log.info('done')" : null;

        substitutor.enrichElementWithFileProperties(element, source);

        assertEquals("log.info('done')", afterHandler.get("script"));
        assertFalse(afterHandler.containsKey("propertiesFilename"));
    }
}
