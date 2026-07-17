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

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.V100ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.VersionFieldStrategy;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedIntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSpecificationSource;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportedSystemObject;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SpecificationGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.RESOURCES_FOLDER_PREFIX;

/**
 * Exercises {@link ServiceDeserializer#deserializeSystem(File)} against a real exported archive.
 *
 * <p>The test builds a fully-populated system graph, serializes it with the production
 * {@link ServiceSerializer}, lays the resulting YAML and source files out on disk the way an export
 * archive is structured, and reads it back. It asserts the whole reconstructed graph: system,
 * specification group, system model, operation, and both specification sources. One source is placed
 * only under the {@code resources/} subfolder to drive the resources fallback in
 * {@code readSpecificationSource}; the other is placed directly to drive the primary lookup. It also
 * asserts each source's stored hash survives the round trip.
 */
class ServiceDeserializerTest {

    private static final String APP_NAME = "qip";

    private static final String SYSTEM_ID = "system-1";
    private static final String GROUP_ID = "group-1";
    private static final String MODEL_ID = "model-1";

    private static final String FALLBACK_SOURCE_NAME = "main.wsdl";
    private static final String DIRECT_SOURCE_NAME = "types.xsd";
    private static final String FALLBACK_SOURCE_CONTENT = "<wsdl>main</wsdl>";
    private static final String DIRECT_SOURCE_CONTENT = "<xsd>types</xsd>";

    private YAMLMapper yamlMapper;
    private ServiceSerializer serializer;
    private ServiceDeserializer deserializer;

    private String fallbackSourceHash;
    private String directSourceHash;

    @BeforeEach
    void setUp() {
        yamlMapper = new MapperAutoConfiguration().yamlExportImportMapper();

        VersionsGetterService versionsGetterService = buildVersionsGetterService();
        FileMigrationService fileMigrationService =
                new FileMigrationService(yamlMapper, versionsGetterService, List.of());
        List<ServiceImportFileMigration> serviceMigrations = List.of(new V100ServiceImportFileMigration());

        IntegrationSystemDtoMapper systemMapper = new IntegrationSystemDtoMapper(
                URI.create("http://qubership.org/schemas/product/qip/service"), serviceMigrations);
        SpecificationGroupDtoMapper groupMapper = new SpecificationGroupDtoMapper(
                URI.create("http://qubership.org/schemas/product/qip/specification-group"));
        SystemModelDtoMapper modelMapper = new SystemModelDtoMapper(
                URI.create("http://qubership.org/schemas/product/qip/specification"));

        serializer = new ServiceSerializer(yamlMapper, systemMapper, groupMapper, modelMapper, fileMigrationService);
        deserializer = new ServiceDeserializer(
                yamlMapper, versionsGetterService, systemMapper, groupMapper, modelMapper,
                fileMigrationService, serviceMigrations);
        ReflectionTestUtils.setField(deserializer, "appName", APP_NAME);
    }

    @Test
    void deserializeSystemRebuildsGraphAndResolvesSourcesFromDisk(@TempDir Path archiveDir) throws Exception {
        IntegrationSystem system = buildSystemGraph();
        File serviceFile = writeArchive(system, archiveDir);

        IntegrationSystem result = deserializer.deserializeSystem(serviceFile);

        assertEquals(SYSTEM_ID, result.getId());
        assertEquals("Payment Service", result.getName());
        assertEquals("Handles payments", result.getDescription());
        assertEquals(OperationProtocol.SOAP, result.getProtocol());
        assertEquals(IntegrationSystemType.EXTERNAL, result.getIntegrationSystemType());
        assertEquals("payment-internal", result.getInternalServiceName());

        assertEquals(1, result.getSpecificationGroups().size());
        SpecificationGroup group = result.getSpecificationGroups().get(0);
        assertEquals(GROUP_ID, group.getId());
        assertEquals("Payment API", group.getName());
        assertEquals("Payment API group", group.getDescription());
        assertEquals("https://payments.example.com/wsdl", group.getUrl());
        assertTrue(group.isSynchronization());

        assertEquals(1, group.getSystemModels().size());
        SystemModel model = group.getSystemModels().get(0);
        assertEquals(MODEL_ID, model.getId());
        assertEquals("1.0.0", model.getName());
        assertEquals("1.0.0", model.getVersion());
        assertEquals("First revision", model.getDescription());
        assertEquals(SystemModelSource.DISCOVERED, model.getSource());
        assertFalse(model.isDeprecated());

        assertEquals(1, model.getOperations().size());
        Operation operation = model.getOperations().get(0);
        assertEquals("model-1-createPayment", operation.getId());
        assertEquals("createPayment", operation.getName());
        assertEquals("POST", operation.getMethod());
        assertEquals("/pay", operation.getPath());

        assertEquals(2, model.getSpecificationSources().size());
        SpecificationSource fallbackSource = findSource(model, FALLBACK_SOURCE_NAME);
        SpecificationSource directSource = findSource(model, DIRECT_SOURCE_NAME);

        assertTrue(fallbackSource.isMainSource());
        assertEquals(FALLBACK_SOURCE_CONTENT, fallbackSource.getSource());
        assertEquals(fallbackSourceHash, fallbackSource.getSourceHash());

        assertFalse(directSource.isMainSource());
        assertEquals(DIRECT_SOURCE_CONTENT, directSource.getSource());
        assertEquals(directSourceHash, directSource.getSourceHash());
    }

    private static VersionsGetterService buildVersionsGetterService() {
        MigrationFieldStrategy migrationFieldStrategy = new MigrationFieldStrategy();
        return new VersionsGetterService(List.of(
                new MigrationFieldInContentStrategy(migrationFieldStrategy),
                migrationFieldStrategy,
                new VersionFieldStrategy()));
    }

    private IntegrationSystem buildSystemGraph() {
        IntegrationSystem system = IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name("Payment Service")
                .description("Handles payments")
                .protocol(OperationProtocol.SOAP)
                .integrationSystemType(IntegrationSystemType.EXTERNAL)
                .internalServiceName("payment-internal")
                .build();

        SpecificationGroup group = SpecificationGroup.builder()
                .id(GROUP_ID)
                .name("Payment API")
                .description("Payment API group")
                .url("https://payments.example.com/wsdl")
                .synchronization(true)
                .system(system)
                .build();
        system.addSpecificationGroup(group);

        SystemModel model = SystemModel.builder()
                .id(MODEL_ID)
                .name("1.0.0")
                .description("First revision")
                .version("1.0.0")
                .deprecated(false)
                .source(SystemModelSource.DISCOVERED)
                .build();
        group.addSystemModel(model);

        Operation operation = Operation.builder()
                .id("model-1-createPayment")
                .name("createPayment")
                .description("Create a payment")
                .method("POST")
                .path("/pay")
                .build();
        operation.setSystemModel(model);
        model.addProvidedOperation(operation);

        SpecificationSource fallbackSource = SpecificationSource.builder()
                .id("source-main")
                .name(FALLBACK_SOURCE_NAME)
                .isMainSource(true)
                .source(FALLBACK_SOURCE_CONTENT)
                .build();
        fallbackSource.setSystemModel(model);
        model.addProvidedSpecificationSource(fallbackSource);
        fallbackSourceHash = fallbackSource.getSourceHash();

        SpecificationSource directSource = SpecificationSource.builder()
                .id("source-types")
                .name(DIRECT_SOURCE_NAME)
                .isMainSource(false)
                .source(DIRECT_SOURCE_CONTENT)
                .build();
        directSource.setSystemModel(model);
        model.addProvidedSpecificationSource(directSource);
        directSourceHash = directSource.getSourceHash();

        return system;
    }

    /**
     * Writes the exported YAML documents and their source files into {@code archiveDir}, mirroring
     * the export archive layout. The fallback source lands only under {@code resources/}; the direct
     * source lands at its recorded path.
     */
    private File writeArchive(IntegrationSystem system, Path archiveDir) throws Exception {
        ExportedIntegrationSystem exportedSystem = (ExportedIntegrationSystem) serializer.serialize(system);

        Path serviceFile = archiveDir.resolve(
                ExportImportUtils.generateMainSystemFileExportName(exportedSystem.getId(), APP_NAME, false));
        writeYaml(serviceFile, exportedSystem);

        for (ExportedSpecificationGroup exportedGroup : exportedSystem.getSpecificationGroups()) {
            Path groupFile = archiveDir.resolve(
                    ExportImportUtils.generateSpecificationGroupFileExportName(exportedGroup.getId(), APP_NAME, false));
            writeYaml(groupFile, exportedGroup);

            for (ExportedSpecification exportedSpecification : exportedGroup.getSpecifications()) {
                Path specFile = archiveDir.resolve(ExportImportUtils.generateSpecificationFileExportName(
                        exportedSpecification.getId(), APP_NAME, false));
                writeYaml(specFile, exportedSpecification);

                for (ExportedSpecificationSource source : exportedSpecification.getSpecificationSources()) {
                    Path target = FALLBACK_SOURCE_NAME.equals(fileNameOf(source.getName()))
                            ? archiveDir.resolve(RESOURCES_FOLDER_PREFIX + source.getName())
                            : archiveDir.resolve(source.getName());
                    writeFile(target, source.getSource());
                }
            }
        }
        return serviceFile.toFile();
    }

    private void writeYaml(Path path, ExportedSystemObject exportedObject) throws Exception {
        writeFile(path, yamlMapper.writeValueAsString(exportedObject.getObjectNode()));
    }

    private static void writeFile(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String fileNameOf(String recordedName) {
        return Path.of(recordedName).getFileName().toString();
    }

    private static SpecificationSource findSource(SystemModel model, String name) {
        SpecificationSource source = model.getSpecificationSources().stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(source, "Expected a specification source named " + name);
        return source;
    }
}
