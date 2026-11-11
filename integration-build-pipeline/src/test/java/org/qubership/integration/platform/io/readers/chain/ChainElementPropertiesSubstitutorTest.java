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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @DisplayName("Validation schema files are restored into inline schemas")
    @Test
    void restoresValidationSchemasFromFiles() {
        Map<String, Object> httpAfterEntry = new HashMap<>();
        httpAfterEntry.put("code", "ignored");
        httpAfterEntry.put("validationSchemaFilename", "http-after.json");
        Map<String, Object> httpProperties = new HashMap<>();
        httpProperties.put("validationSchemaFilename", "http-top.json");
        httpProperties.put("after", new ArrayList<>(List.of(httpAfterEntry)));
        ChainElementExternalEntity httpElement = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(httpProperties)
                .build();

        Map<String, Object> asyncFileEntry = new HashMap<>();
        asyncFileEntry.put("code", "c1");
        asyncFileEntry.put("label", "l1");
        asyncFileEntry.put("validationSchemaFilename", "async-after.json");
        Map<String, Object> asyncKeptEntry = new HashMap<>();
        asyncKeptEntry.put("code", "c2");
        asyncKeptEntry.put("label", "l2");
        asyncKeptEntry.put("schema", "keep");
        Map<String, Object> asyncProperties = new HashMap<>();
        asyncProperties.put("validationSchemaFilename", "async-top.json");
        asyncProperties.put("after", new ArrayList<>(List.of(asyncFileEntry, asyncKeptEntry)));
        ChainElementExternalEntity asyncElement = ChainElementExternalEntity.builder()
                .id("at-1")
                .type("async-api-trigger")
                .properties(asyncProperties)
                .build();

        Map<String, Object> validationFileEntry = new HashMap<>();
        validationFileEntry.put("code", "200");
        validationFileEntry.put("type", "responseValidation");
        validationFileEntry.put("validationSchemaFilename", "service-after-validation.json");
        Map<String, Object> validationKeptEntry = new HashMap<>();
        validationKeptEntry.put("code", "404");
        validationKeptEntry.put("type", "responseValidation");
        validationKeptEntry.put("schema", "keep");
        Map<String, Object> serviceProperties = new HashMap<>();
        serviceProperties.put("afterValidation",
                new ArrayList<>(List.of(validationFileEntry, validationKeptEntry)));
        ChainElementExternalEntity serviceElement = ChainElementExternalEntity.builder()
                .id("sc-1")
                .type("service-call")
                .properties(serviceProperties)
                .build();

        Map<String, String> files = Map.of(
                "http-top.json", "{\"type\":\"object\"}",
                "async-top.json", "{\"type\":\"string\"}",
                "async-after.json", "{\"type\":\"number\"}",
                "service-after-validation.json", "{\"type\":\"boolean\"}");
        PropertyFileSource source = files::get;

        substitutor.enrichElementWithFileProperties(httpElement, source);
        substitutor.enrichElementWithFileProperties(asyncElement, source);
        substitutor.enrichElementWithFileProperties(serviceElement, source);

        assertEquals("{\"type\":\"object\"}", httpProperties.get("validationSchema"));
        assertFalse(httpProperties.containsKey("validationSchemaFilename"));
        assertFalse(httpAfterEntry.containsKey("schema"));
        assertEquals("http-after.json", httpAfterEntry.get("validationSchemaFilename"));
        assertEquals("{\"type\":\"string\"}", asyncProperties.get("validationSchema"));
        assertFalse(asyncProperties.containsKey("validationSchemaFilename"));
        assertEquals("{\"type\":\"number\"}", asyncFileEntry.get("schema"));
        assertFalse(asyncFileEntry.containsKey("validationSchemaFilename"));
        assertEquals("keep", asyncKeptEntry.get("schema"));
        assertFalse(asyncKeptEntry.containsKey("validationSchemaFilename"));
        assertEquals("{\"type\":\"boolean\"}", validationFileEntry.get("schema"));
        assertFalse(validationFileEntry.containsKey("validationSchemaFilename"));
        assertEquals("keep", validationKeptEntry.get("schema"));
    }

    @DisplayName("Invalid validation schema references fail the import")
    @Test
    void rejectsInvalidValidationSchemaReferences() {
        Map<String, Object> missingFileProperties = new HashMap<>();
        missingFileProperties.put("validationSchemaFilename", "missing.json");
        ChainElementExternalEntity missingFileElement = ChainElementExternalEntity.builder()
                .id("ht-1")
                .type("http-trigger")
                .properties(missingFileProperties)
                .build();
        IllegalArgumentException missingFileError = assertThrows(IllegalArgumentException.class,
                () -> substitutor.enrichElementWithFileProperties(missingFileElement, name -> null));
        assertTrue(missingFileError.getMessage().contains("missing.json"));

        Map<String, Object> badAfterProperties = new HashMap<>();
        badAfterProperties.put("after", "not-a-list");
        ChainElementExternalEntity badAfterElement = ChainElementExternalEntity.builder()
                .id("at-1")
                .type("async-api-trigger")
                .properties(badAfterProperties)
                .build();
        IllegalArgumentException badAfterError = assertThrows(IllegalArgumentException.class,
                () -> substitutor.enrichElementWithFileProperties(badAfterElement, name -> null));
        assertTrue(badAfterError.getMessage().contains("after"));

        Map<String, Object> badEntryProperties = new HashMap<>();
        badEntryProperties.put("after", new ArrayList<>(List.of("not-a-map")));
        ChainElementExternalEntity badEntryElement = ChainElementExternalEntity.builder()
                .id("at-2")
                .type("async-api-trigger")
                .properties(badEntryProperties)
                .build();
        IllegalArgumentException badEntryError = assertThrows(IllegalArgumentException.class,
                () -> substitutor.enrichElementWithFileProperties(badEntryElement, name -> null));
        assertTrue(badEntryError.getMessage().contains("after"));

        Map<String, Object> badValidationProperties = new HashMap<>();
        badValidationProperties.put("afterValidation", "not-a-list");
        ChainElementExternalEntity badValidationElement = ChainElementExternalEntity.builder()
                .id("sc-1")
                .type("service-call")
                .properties(badValidationProperties)
                .build();
        IllegalArgumentException badValidationError = assertThrows(IllegalArgumentException.class,
                () -> substitutor.enrichElementWithFileProperties(badValidationElement, name -> null));
        assertTrue(badValidationError.getMessage().contains("afterValidation"));

        Map<String, Object> badValidationEntryProperties = new HashMap<>();
        badValidationEntryProperties.put("afterValidation", new ArrayList<>(List.of("not-a-map")));
        ChainElementExternalEntity badValidationEntryElement = ChainElementExternalEntity.builder()
                .id("sc-2")
                .type("service-call")
                .properties(badValidationEntryProperties)
                .build();
        IllegalArgumentException badValidationEntryError = assertThrows(IllegalArgumentException.class,
                () -> substitutor.enrichElementWithFileProperties(badValidationEntryElement, name -> null));
        assertTrue(badValidationEntryError.getMessage().contains("afterValidation"));

        Map<String, Object> noFilenameProperties = new HashMap<>();
        noFilenameProperties.put("validationSchema", "keep");
        ChainElementExternalEntity noFilenameElement = ChainElementExternalEntity.builder()
                .id("ht-2")
                .type("http-trigger")
                .properties(noFilenameProperties)
                .build();
        substitutor.enrichElementWithFileProperties(noFilenameElement, name -> {
            throw new AssertionError("no file should be read");
        });
        assertEquals("keep", noFilenameProperties.get("validationSchema"));

        Map<String, Object> nonStringFilenameProperties = new HashMap<>();
        nonStringFilenameProperties.put("validationSchemaFilename", 42);
        ChainElementExternalEntity nonStringFilenameElement = ChainElementExternalEntity.builder()
                .id("ht-3")
                .type("http-trigger")
                .properties(nonStringFilenameProperties)
                .build();
        substitutor.enrichElementWithFileProperties(nonStringFilenameElement, name -> {
            throw new AssertionError("no file should be read");
        });
        assertEquals(42, nonStringFilenameProperties.get("validationSchemaFilename"));
        assertFalse(nonStringFilenameProperties.containsKey("validationSchema"));
    }
}
