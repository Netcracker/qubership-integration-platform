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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @DisplayName("A handler container script is restored even when there is no top-level properties file")
    @Test
    void restoresHandlerContainerScriptWithoutTopLevelFile() {
        Map<String, Object> handlerContainer = new HashMap<>();
        handlerContainer.put("propertiesFilename", "handler-h1.groovy");

        Map<String, Object> properties = new HashMap<>();
        properties.put("handlerContainer", handlerContainer);

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(properties)
                .build();
        PropertyFileSource source = name ->
                "handler-h1.groovy".equals(name) ? "handle validation" : null;

        substitutor.enrichElementWithFileProperties(element, source);

        assertEquals("handle validation", handlerContainer.get("script"));
        assertFalse(handlerContainer.containsKey("propertiesFilename"));
    }

    @DisplayName("A chain failure handler container script is restored from its file")
    @Test
    void restoresChainFailureHandlerContainerScript() {
        Map<String, Object> failureContainer = new HashMap<>();
        failureContainer.put("propertiesFilename", "failure-h1.groovy");

        Map<String, Object> properties = new HashMap<>();
        properties.put("chainFailureHandlerContainer", failureContainer);

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(properties)
                .build();
        PropertyFileSource source = name ->
                "failure-h1.groovy".equals(name) ? "handle failure" : null;

        substitutor.enrichElementWithFileProperties(element, source);

        assertEquals("handle failure", failureContainer.get("script"));
        assertFalse(failureContainer.containsKey("propertiesFilename"));
    }

    @DisplayName("A handler container mapping file restores only the mapping description")
    @Test
    void restoresHandlerContainerMappingDescriptionOnly() {
        Map<String, Object> handlerContainer = new HashMap<>();
        handlerContainer.put("propertiesFilename", "handler-h1.json");

        Map<String, Object> properties = new HashMap<>();
        properties.put("handlerContainer", handlerContainer);

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(properties)
                .build();
        PropertyFileSource source = name ->
                "handler-h1.json".equals(name)
                        ? "{\"mappingDescription\": {\"mapping\": \"m\"}, \"ignored\": \"x\"}"
                        : null;

        substitutor.enrichElementWithFileProperties(element, source);

        assertEquals(Map.of("mapping", "m"), handlerContainer.get("mappingDescription"));
        assertFalse(handlerContainer.containsKey("ignored"));
        assertFalse(handlerContainer.containsKey("propertiesFilename"));
    }

    @DisplayName("A handler container json file without mapping description is merged as-is")
    @Test
    void restoresHandlerContainerMapWithoutMappingDescription() {
        Map<String, Object> handlerContainer = new HashMap<>();
        handlerContainer.put("propertiesFilename", "handler-h1.json");

        Map<String, Object> properties = new HashMap<>();
        properties.put("handlerContainer", handlerContainer);

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(properties)
                .build();
        PropertyFileSource source = name ->
                "handler-h1.json".equals(name) ? "{\"throwException\": true}" : null;

        substitutor.enrichElementWithFileProperties(element, source);

        assertEquals(true, handlerContainer.get("throwException"));
        assertFalse(handlerContainer.containsKey("propertiesFilename"));
    }

    @DisplayName("A handler container without a file reference is left untouched")
    @Test
    void skipsHandlerContainerWithoutFilename() {
        Map<String, Object> handlerContainer = new HashMap<>();
        handlerContainer.put("script", "keep me");

        Map<String, Object> properties = new HashMap<>();
        properties.put("handlerContainer", handlerContainer);

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(properties)
                .build();

        substitutor.enrichElementWithFileProperties(element, name -> {
            throw new AssertionError("no file should be read");
        });

        assertEquals("keep me", handlerContainer.get("script"));
        assertFalse(handlerContainer.containsKey("propertiesFilename"));
    }

    @DisplayName("A non-map handler container is ignored")
    @Test
    void skipsNonMapHandlerContainer() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("handlerContainer", "not-a-map");

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(properties)
                .build();

        substitutor.enrichElementWithFileProperties(element, name -> {
            throw new AssertionError("no file should be read");
        });

        assertEquals("not-a-map", element.getProperties().get("handlerContainer"));
    }

    @DisplayName("A service-call handler container is restored even when before has no file")
    @Test
    void restoresServiceCallHandlerContainerWithoutBeforeFile() {
        Map<String, Object> handlerContainer = new HashMap<>();
        handlerContainer.put("propertiesFilename", "handler-sc1.groovy");

        Map<String, Object> properties = new HashMap<>();
        properties.put("before", new HashMap<>(Map.of("type", "script")));
        properties.put("handlerContainer", handlerContainer);

        ChainElementExternalEntity element = ChainElementExternalEntity.builder()
                .id("sc-1")
                .type("service-call")
                .properties(properties)
                .build();
        PropertyFileSource source = name ->
                "handler-sc1.groovy".equals(name) ? "handle validation" : null;

        substitutor.enrichElementWithFileProperties(element, source);

        assertEquals("handle validation", handlerContainer.get("script"));
        assertFalse(handlerContainer.containsKey("propertiesFilename"));
        assertTrue(((Map<?, ?>) element.getProperties().get("before")).containsKey("type"));
    }
}
