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
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.ImportChain;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.configuration.ElementDescriptorProperties;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementType;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainReaderTest {

    @TempDir
    Path chainDir;

    private ChainReader reader;

    @BeforeEach
    void setUp() throws Exception {
        LibraryElementsService libraryService = new LibraryElementsService(
                new YAMLMapper(),
                new PropertyPlaceholderHelper("${", "}"),
                new ElementDescriptorProperties(new Properties()));
        ElementDescriptor sender = new ElementDescriptor();
        sender.setName("http-sender");
        sender.setType(ElementType.MODULE);
        libraryService.registerElement(sender);

        ChainModelMapper mapper = new ChainModelMapper(libraryService, new ChainElementPropertiesSubstitutor(new ObjectMapper()));

        // Migration is exercised by its own tests; here it passes the document through unchanged.
        FileMigrationService fileMigrationService = mock(FileMigrationService.class);
        when(fileMigrationService.migrate(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        reader = new ChainReader(new YAMLMapper(), fileMigrationService, List.of(), mapper);
    }

    @DisplayName("read locates the chain YAML, maps it, and restores file-backed element properties")
    @Test
    void readsChainDirectoryIntoModel() throws IOException {
        String chainYaml = """
                id: chain-1
                name: Payments
                content:
                  elements:
                    - id: e-1
                      element-type: http-sender
                      properties-filename: config.json
                """;
        Files.writeString(chainDir.resolve("chain-1.chain.qip.yaml"), chainYaml);
        Files.writeString(chainDir.resolve("config.json"), "{\"host\":\"db\",\"port\":5432}");

        ImportChain result = reader.read(chainDir.toFile());

        assertEquals("chain-1", result.getId());
        assertEquals("Payments", result.getName());
        Element element = result.getElements().iterator().next();
        assertEquals("e-1", element.getId());
        Map<String, Object> properties = element.getProperties();
        assertEquals("db", properties.get("host"));
        assertEquals(5432, properties.get("port"));
    }
}
