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

package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SpecificationGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.V100ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterService;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceDeserializerTest {

    private static final String SYSTEM_ID = "system-1";
    private static final String GROUP_ID = "group-1";
    private static final String MODEL_ID = "model-1";

    @TempDir
    Path tempDir;

    @Mock
    private VersionsGetterService versionsGetterService;

    @Mock
    private FileMigrationService fileMigrationService;

    private ServiceDeserializer deserializer;

    @BeforeEach
    void setUp() throws Exception {
        YAMLMapper yamlMapper = new YAMLMapper();
        List<ServiceImportFileMigration> migrations = List.of(new V100ServiceImportFileMigration());

        when(versionsGetterService.getVersions(any())).thenReturn(List.of(100, 101, 102));
        when(fileMigrationService.migrate(anyString(), anyCollection())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fileMigrationService.migrate(any(ObjectNode.class), anyCollection())).thenAnswer(invocation -> invocation.getArgument(0));

        deserializer = new ServiceDeserializer(
                yamlMapper,
                versionsGetterService,
                new IntegrationSystemDtoMapper(URI.create("http://qubership.org/schemas/product/qip/service"), migrations),
                new SpecificationGroupDtoMapper(URI.create("http://qubership.org/schemas/product/qip/specification-group")),
                new SystemModelDtoMapper(URI.create("http://qubership.org/schemas/product/qip/specification")),
                fileMigrationService,
                migrations
        );
        ReflectionTestUtils.setField(deserializer, "appName", "qip");
    }

    @Test
    @DisplayName("Should deserialize legacy service with embedded specification groups, models and operations")
    void shouldDeserializeLegacyEmbeddedService() throws IOException {
        writeFile(tempDir.resolve("openapi.txt"), "openapi spec content");
        File serviceFile = writeServiceFile(tempDir, legacyServiceYaml("openapi.txt", null));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(SYSTEM_ID, system.getId());
        assertEquals("Legacy Service", system.getName());
        assertEquals(1, system.getSpecificationGroups().size());

        SpecificationGroup group = system.getSpecificationGroups().getFirst();
        assertEquals(GROUP_ID, group.getId());
        assertEquals("API Group", group.getName());
        assertFalse(group.isSynchronization());
        assertEquals(1, group.getSystemModels().size());

        SystemModel model = group.getSystemModels().getFirst();
        assertEquals(MODEL_ID, model.getId());
        assertEquals("1.0", model.getVersion());
        assertEquals(1, model.getOperations().size());
        assertEquals("GET", model.getOperations().getFirst().getMethod());
        assertEquals("/pets", model.getOperations().getFirst().getPath());

        assertEquals(1, model.getSpecificationSources().size());
        SpecificationSource source = model.getSpecificationSources().getFirst();
        assertEquals("src-1", source.getId());
        assertEquals("openapi.txt", source.getName());
        assertEquals("openapi spec content", source.getSource());
        assertTrue(source.isMainSource());
    }

    @Test
    @DisplayName("Should use specification source name when fileName is missing")
    void shouldUseSpecificationSourceNameWhenFileNameMissing() throws IOException {
        writeFile(tempDir.resolve("legacy-spec.txt"), "legacy source body");
        File serviceFile = writeServiceFile(tempDir, legacyServiceYaml("legacy-spec.txt", null));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        SpecificationSource source = system.getSpecificationGroups().getFirst()
                .getSystemModels().getFirst()
                .getSpecificationSources().getFirst();
        assertEquals("legacy source body", source.getSource());
    }

    @Test
    @DisplayName("Should read specification source from resources folder when not found in root")
    void shouldReadSpecificationSourceFromResourcesFolder() throws IOException {
        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        writeFile(resourcesDir.resolve("nested-spec.txt"), "nested source body");
        File serviceFile = writeServiceFile(tempDir, legacyServiceYaml("nested-spec.txt", null));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        SpecificationSource source = system.getSpecificationGroups().getFirst()
                .getSystemModels().getFirst()
                .getSpecificationSources().getFirst();
        assertEquals("nested source body", source.getSource());
    }

    @Test
    @DisplayName("Should resolve specification group by id when multiple groups are embedded")
    void shouldResolveSpecificationGroupById() throws IOException {
        String yaml = """
                id: "%s"
                name: "Multi Group Service"
                content:
                  integrationSystemType: EXTERNAL
                  protocol: HTTP
                  specificationGroups:
                    - id: "group-a"
                      name: "Group A"
                      synchronization: true
                      systemModels:
                        - id: "model-a"
                          name: "1.0"
                          version: "1.0"
                          source: MANUAL
                          operations: []
                          specificationSources: []
                    - id: "group-b"
                      name: "Group B"
                      synchronization: false
                      systemModels:
                        - id: "model-b"
                          name: "2.0"
                          version: "2.0"
                          source: MANUAL
                          operations:
                            - id: "op-b"
                              method: POST
                              path: /items
                          specificationSources: []
                fileVersion: 3
                """.formatted(SYSTEM_ID);
        File serviceFile = writeServiceFile(tempDir, yaml);

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(2, system.getSpecificationGroups().size());
        SpecificationGroup groupB = system.getSpecificationGroups().stream()
                .filter(group -> "group-b".equals(group.getId()))
                .findFirst()
                .orElseThrow();
        assertFalse(groupB.isSynchronization());
        assertEquals(1, groupB.getSystemModels().size());
        assertEquals("POST", groupB.getSystemModels().getFirst().getOperations().getFirst().getMethod());
    }

    @Test
    @DisplayName("Should deserialize service from separate specification group and model files")
    void shouldDeserializeServiceFromSeparateFiles() throws IOException {
        String serviceYaml = """
                id: "%s"
                name: "Modern Service"
                content:
                  integrationSystemType: EXTERNAL
                  protocol: HTTP
                  migrations: "[100, 101]"
                """.formatted(SYSTEM_ID);
        File serviceFile = writeServiceFile(tempDir, serviceYaml);

        String groupYaml = """
                id: "%s"
                name: "Separate Group"
                content:
                  synchronization: true
                  parentId: "%s"
                """.formatted(GROUP_ID, SYSTEM_ID);
        writeFile(tempDir.resolve(GROUP_ID + ".specification-group.qip.yaml"), groupYaml);

        String modelYaml = """
                id: "%s"
                name: "1.0"
                content:
                  version: "1.0"
                  parentId: "%s"
                  operations: []
                  specificationSources: []
                """.formatted(MODEL_ID, GROUP_ID);
        writeFile(tempDir.resolve(MODEL_ID + ".specification.qip.yaml"), modelYaml);

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(SYSTEM_ID, system.getId());
        assertEquals(1, system.getSpecificationGroups().size());
        SpecificationGroup group = system.getSpecificationGroups().getFirst();
        assertEquals(GROUP_ID, group.getId());
        assertTrue(group.isSynchronization());
        assertEquals(1, group.getSystemModels().size());
        assertEquals(MODEL_ID, group.getSystemModels().getFirst().getId());
    }

    @Test
    @DisplayName("Should import specification source using explicit fileName field")
    void shouldImportSpecificationSourceUsingFileName() throws IOException {
        writeFile(tempDir.resolve("explicit-spec.txt"), "explicit source body");
        File serviceFile = writeServiceFile(tempDir, legacyServiceYaml(null, "explicit-spec.txt"));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        SpecificationSource source = system.getSpecificationGroups().getFirst()
                .getSystemModels().getFirst()
                .getSpecificationSources().getFirst();
        assertNotNull(source.getSource());
        assertEquals("explicit source body", source.getSource());
    }

    private static String legacyServiceYaml(String sourceName, String sourceFileName) {
        String sourceBlock;
        if (sourceFileName != null) {
            sourceBlock = """
                      specificationSources:
                        - id: "src-1"
                          fileName: "%s"
                          mainSource: true
                    """.formatted(sourceFileName);
        } else {
            sourceBlock = """
                      specificationSources:
                        - id: "src-1"
                          name: "%s"
                          mainSource: true
                    """.formatted(sourceName);
        }

        return """
                id: "%s"
                name: "Legacy Service"
                content:
                  integrationSystemType: EXTERNAL
                  protocol: HTTP
                  specificationGroups:
                    - id: "%s"
                      name: "API Group"
                      synchronization: false
                      systemModels:
                        - id: "%s"
                          name: "1.0"
                          version: "1.0"
                          source: MANUAL
                          operations:
                            - id: "op-1"
                              method: GET
                              path: /pets
                %s
                fileVersion: 3
                """.formatted(SYSTEM_ID, GROUP_ID, MODEL_ID, sourceBlock);
    }

    private static File writeServiceFile(Path directory, String yaml) throws IOException {
        Path servicePath = directory.resolve("service-" + SYSTEM_ID + ".yaml");
        writeFile(servicePath, yaml);
        return servicePath.toFile();
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }
}
