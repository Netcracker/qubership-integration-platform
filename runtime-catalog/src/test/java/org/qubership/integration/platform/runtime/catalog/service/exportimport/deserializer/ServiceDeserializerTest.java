package org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.VersionFieldStrategy;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceImportException;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.SystemModelSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter.ServiceConfigurationsToFilesConverter;
import org.qubership.integration.platform.util.HashUtils;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.SERVICE_YAML_NAME_POSTFIX;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.readInput;

class ServiceDeserializerTest {

    private static final String APP_NAME = "qip";
    private static final String SYSTEM_ID = "system-1";
    private static final String GROUP_ID = "group-1";
    private static final String SPEC_ID = "spec-1";
    private static final ApplicationJsonSchemaProperties SCHEMA_PROPERTIES = new ApplicationJsonSchemaProperties();
    private static final ServiceTypeFiles SERVICE_TYPE_FILES = new ServiceTypeFiles(SCHEMA_PROPERTIES);

    @TempDir
    private Path serviceDirectory;

    private YAMLMapper yamlMapper;
    private ServiceSerializer serializer;
    private ServiceDeserializer deserializer;

    private String fallbackSourceHash;
    private String directSourceHash;

    @BeforeEach
    void setUp() {
        deserializer = buildDeserializer(ExtractorTestParsers.extractor());
    }

    private ServiceDeserializer buildDeserializer(OperationSchemaExtractor extractor) {
        YAMLMapper yamlMapper = new MapperAutoConfiguration().yamlExportImportMapper();

        MigrationFieldStrategy migrationFieldStrategy = new MigrationFieldStrategy();
        VersionsGetterService versionsGetterService = new VersionsGetterService(List.of(
                new MigrationFieldInContentStrategy(migrationFieldStrategy),
                migrationFieldStrategy,
                new VersionFieldStrategy()
        ));

        FileMigrationService fileMigrationService =
                new FileMigrationService(yamlMapper, versionsGetterService, List.of());
        ReflectionTestUtils.setField(fileMigrationService, "isLegacyExport", false);

        List<ServiceImportFileMigration> migrations = TestServiceMigrations.all();

        IntegrationSystemReader reader =
                new IntegrationSystemReader(yamlMapper, fileMigrationService, versionsGetterService, migrations);

        return new ServiceDeserializer(
                yamlMapper,
                reader,
                new IntegrationSystemDtoMapper(SERVICE_TYPE_FILES, migrations),
                new ApiGroupDtoMapper(URI.create("http://qubership.org/schemas/product/qip/api-group")),
                new SystemModelDtoMapper(
                        URI.create("http://qubership.org/schemas/product/qip/api.schema.yaml"),
                        new ApiOperationDtoMapper()),
                extractor,
                SERVICE_TYPE_FILES
        );
    }

    // --- structure -------------------------------------------------------------------------------------------------

    @Test
    void deserializesServiceWithGroupAndSpecification() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", specificationYaml(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(SYSTEM_ID, system.getId());
        assertEquals("Test service", system.getName());
        assertEquals(IntegrationSystemType.EXTERNAL, system.getIntegrationSystemType());
        assertEquals(OperationProtocol.HTTP, system.getProtocol());

        assertEquals(1, system.getApiGroups().size());
        ApiGroup group = system.getApiGroups().get(0);
        assertEquals(GROUP_ID, group.getId());

        assertEquals(1, group.getSystemModels().size());
        SystemModel model = group.getSystemModels().get(0);
        assertEquals(SPEC_ID, model.getId());
        assertEquals("1.0.0", model.getVersion());
    }

    @Test
    void discoversApiFormatModelFiles() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  specificationType: "openapi"
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  specifications:
                  - filePath: "source-%s/api.yaml"
                    isRoot: true
                  operations:
                  - id: "op-1"
                    type: "openapi"
                    method: "get"
                    path: "/pets"
                """.formatted(SPEC_ID, GROUP_ID, SPEC_ID));
        writeFile("source-" + SPEC_ID + "/api.yaml", "openapi: 3.0.0");

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size());
        SystemModel model = system.getApiGroups().get(0).getSystemModels().get(0);
        assertEquals(SPEC_ID, model.getId());
        assertEquals("openapi", model.getSpecificationType());

        SpecificationSource source = onlySource(system);
        assertEquals("openapi: 3.0.0", source.getSource());
        assertTrue(source.isMainSource(), "isRoot maps to the entity main-source flag");
    }

    /**
     * The file pair the current backend writes. Losing this discovery arm imports a service with zero API groups and
     * no error at all, so it needs its own test rather than riding on the pre-rename postfix everywhere else.
     */
    @Test
    void discoversApiGroupFormatGroupFiles() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".api-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  specificationType: "openapi"
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                """.formatted(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size(), "the .api-group. file must be discovered");
        assertEquals(GROUP_ID, system.getApiGroups().get(0).getId());
        assertEquals(1, system.getApiGroups().get(0).getSystemModels().size());
        assertEquals(SPEC_ID, system.getApiGroups().get(0).getSystemModels().get(0).getId());
    }

    /** An archive edited across the rename holds both spellings side by side; neither arm may shadow the other. */
    @Test
    void discoversGroupFilesUnderBothPostfixesInOneDirectory() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile("a.api-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile("b.specification-group." + APP_NAME + ".yaml", groupYaml("group-2", SYSTEM_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(2, system.getApiGroups().size());
        assertTrue(system.getApiGroups().stream().map(ApiGroup::getId).toList().containsAll(List.of(GROUP_ID, "group-2")));
    }

    @Test
    void readsGroupAndSpecificationFromLegacyFileNames() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile("specGroup-" + GROUP_ID + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile("specification-" + SPEC_ID + ".yaml", specificationYaml(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size());
        assertEquals(1, system.getApiGroups().get(0).getSystemModels().size());
    }

    @Test
    void attachesGroupOnlyWhenParentIdMatchesTheService() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile("a.specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile("b.specification-group." + APP_NAME + ".yaml", groupYaml("group-2", "another-system"));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size());
        assertEquals(GROUP_ID, system.getApiGroups().get(0).getId());
    }

    @Test
    void leavesSpecificationUnattachedWhenParentIdMatchesNoGroup() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", specificationYaml(SPEC_ID, "missing-group"));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size());
        assertTrue(system.getApiGroups().get(0).getSystemModels().isEmpty());
    }

    /**
     * apis[] is a derived convenience field: every writer emits it, no reader consumes it. parentId is the sole source
     * of truth for the API to group link, so a group whose apis[] is stale still gets its real models bound through
     * their parentId, and the wrong ids listed in apis[] produce no models.
     */
    @Test
    void bindsModelsByParentIdAndIgnoresTheGroupApisList() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "Test group"
                content:
                  synchronization: false
                  parentId: "%s"
                  apis:
                  - "stale-api-id"
                  - "another-wrong-id"
                """.formatted(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", specificationYaml(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        ApiGroup group = system.getApiGroups().get(0);
        assertEquals(1, group.getSystemModels().size(),
                "the model binds through its parentId even though apis[] names different ids");
        assertEquals(SPEC_ID, group.getSystemModels().get(0).getId());
    }

    @Test
    void importsEnvironmentsAndLabels() throws IOException {
        File serviceFile = writeService("""
                ---
                id: "%s"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  activeEnvironmentId: "env-1"
                  environments:
                  - id: "env-1"
                    name: "Default"
                    address: "http://example.com"
                    sourceType: "MANUAL"
                  labels:
                  - "team-a"
                  migrations: "[100, 101, 102]"
                """.formatted(SYSTEM_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getEnvironments().size());
        assertEquals("env-1", system.getEnvironments().get(0).getId());
        assertEquals(system, system.getEnvironments().get(0).getSystem());
        assertEquals(1, system.getLabels().size());
        assertEquals("team-a", system.getLabels().iterator().next().getName());
    }

    // --- removed enum values ---------------------------------------------------------------------------------------

    @Test
    void importsRetiredCustomerManualSourceAsManual() throws IOException {
        // CUSTOMER_MANUAL left SystemModelSource in this release. An archive written before that still carries it,
        // and without the @JsonAlias on MANUAL Jackson rejects the value outright.
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  deprecated: false
                  version: "1.0.0"
                  source: "CUSTOMER_MANUAL"
                  parentId: "%s"
                """.formatted(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        SystemModel model = system.getApiGroups().get(0).getSystemModels().get(0);
        assertEquals(SystemModelSource.MANUAL, model.getSource());
    }

    @Test
    void importsRetiredCustomerManualSourceAsManualForAnInlineModel() throws IOException {
        // A model inlined in a legacy service file reaches the pipeline through processLegacyService, never through
        // buildAndAddSpecification, so it needs its own coverage.
        File serviceFile = writeService(inlineServiceYaml("""
                    - id: "%s"
                      name: "1.0.0"
                      content:
                        deprecated: false
                        version: "1.0.0"
                        source: "CUSTOMER_MANUAL"
                """.formatted(SPEC_ID)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        SystemModel model = system.getApiGroups().get(0).getSystemModels().get(0);
        assertEquals(SystemModelSource.MANUAL, model.getSource());
    }

    // --- specification sources -------------------------------------------------------------------------------------

    @Test
    void readsSourceFromResourcesFolderWhenFileNameOmitsThePrefix() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml",
                specificationYaml(SPEC_ID, GROUP_ID, "source-" + SPEC_ID + "/api.yaml"));
        writeFile("resources/source-" + SPEC_ID + "/api.yaml", "openapi: 3.0.0");

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        SpecificationSource source = onlySource(system);
        assertEquals("openapi: 3.0.0", source.getSource());
        assertNotNull(source.getSourceHash(), "hash is recomputed by the setter");
    }

    @Test
    void readsSourceWhenFileNameAlreadyCarriesTheResourcesPrefix() throws IOException {
        String fileName = "resources/source-" + SPEC_ID + "/api.yaml";
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml",
                specificationYaml(SPEC_ID, GROUP_ID, fileName));
        writeFile(fileName, "openapi: 3.0.0");

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals("openapi: 3.0.0", onlySource(system).getSource());
    }

    /**
     * A model whose every declared source file is missing has no source to import. Left as a warning it would export an
     * empty {@code specifications} list that violates {@code minItems: 1} in the api schema, so the import fails instead.
     */
    @Test
    void failsWhenEveryDeclaredSourceFileIsMissing() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml",
                specificationYaml(SPEC_ID, GROUP_ID, "source-" + SPEC_ID + "/gone.yaml"));

        ServiceImportException exception = assertThrows(ServiceImportException.class,
                () -> deserializer.deserializeSystem(serviceFile));
        assertEquals(SPEC_ID, exception.getServiceId());
        assertTrue(exception.getMessage().contains(SPEC_ID), "the message names the offending model id");
    }

    /**
     * One missing file among several sources stays a warning: the remaining source resolves, so the model still has
     * content to import and does not fail.
     */
    @Test
    void keepsImportingWhenOnlyOneOfSeveralSourceFilesIsMissing() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  specificationSources:
                  - id: "source-present"
                    name: "api.yaml"
                    fileName: "source-%s/api.yaml"
                    mainSource: true
                  - id: "source-gone"
                    name: "extra.yaml"
                    fileName: "source-%s/gone.yaml"
                    mainSource: false
                """.formatted(SPEC_ID, GROUP_ID, SPEC_ID, SPEC_ID));
        writeFile("source-" + SPEC_ID + "/api.yaml", "openapi: 3.0.0");

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        List<SpecificationSource> sources = system.getApiGroups().get(0)
                .getSystemModels().get(0).getSpecificationSources();
        assertEquals(2, sources.size(), "both sources are attached, the missing one with a null body");
        assertTrue(sources.stream().anyMatch(source -> "openapi: 3.0.0".equals(source.getSource())));
        assertTrue(sources.stream().anyMatch(source -> source.getSource() == null));
    }

    /**
     * Pre-api archives carry a {@code sourceHash} key. It must still parse, and the value must lose: the hash belongs
     * to the source file the archive ships, and only that file says what the content is.
     */
    @Test
    void importAcceptsTheOldSourceHashKeyAndRecomputesTheHash() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".api-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  specificationType: "openapi"
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  specifications:
                  - id: "src-1"
                    name: "api.yaml"
                    filePath: "source-%s/api.yaml"
                    isRoot: true
                    sourceHash: "0ff1ce"
                """.formatted(SPEC_ID, GROUP_ID, SPEC_ID));
        writeFile("source-" + SPEC_ID + "/api.yaml", "openapi: 3.0.0");

        IntegrationSystem system = assertDoesNotThrow(() -> deserializer.deserializeSystem(serviceFile));

        SpecificationSource source = onlySource(system);
        assertEquals(HashUtils.sha256hex("openapi: 3.0.0"), source.getSourceHash(),
                "the hash comes from the file, not from the archive");
    }

    /** A source whose file never arrived has no content, so it must record no hash either. */
    @Test
    void importRecordsNoHashForASourceWhoseFileIsMissing() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".api-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  specificationType: "openapi"
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  specifications:
                  - id: "source-present"
                    name: "api.yaml"
                    filePath: "source-%s/api.yaml"
                    isRoot: true
                  - id: "source-gone"
                    name: "extra.yaml"
                    filePath: "source-%s/gone.yaml"
                    isRoot: false
                    sourceHash: "0ff1ce"
                """.formatted(SPEC_ID, GROUP_ID, SPEC_ID, SPEC_ID));
        writeFile("source-" + SPEC_ID + "/api.yaml", "openapi: 3.0.0");

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        SpecificationSource missing = system.getApiGroups().get(0).getSystemModels().get(0)
                .getSpecificationSources().stream()
                .filter(source -> source.getSource() == null)
                .findFirst()
                .orElseThrow();
        assertNull(missing.getSourceHash(), "a hash without content describes nothing");
    }

    // --- operation specification -----------------------------------------------------------------------------------

    /**
     * The api format no longer writes the per-operation specification, so import re-derives it from the source the
     * archive ships. Kafka is the case at risk: the MaaS classifier lives only in that slice.
     */
    @Test
    void derivesTheOperationSpecificationWhenTheFileCarriesNone() throws IOException {
        writeKafkaModel("""
                  - id: "op-1"
                    type: "asyncapi"
                    method: "send"
                    channel: "orders.commands"
                """);

        IntegrationSystem system = deserializer.deserializeSystem(
                writeService(serviceYaml("KAFKA", "[100, 101, 102]")));

        JsonNode specification = onlyOperation(system).getSpecification();
        assertNotNull(specification, "the column is repopulated from the raw source");
        assertEquals("orders.commands", specification.path("topic").asText());
        assertEquals("order-commands", specification.path("maasClassifierName").asText(),
                "the MaaS classifier the async resolver stores in the slice survives the round trip");
    }

    /** Legacy files still carry the field, and the file is authoritative over anything re-derived. */
    @Test
    void keepsTheOperationSpecificationTheFileAlreadyCarries() throws IOException {
        writeKafkaModel("""
                  - id: "op-1"
                    type: "asyncapi"
                    method: "send"
                    channel: "orders.commands"
                    specification:
                      topic: "orders.commands"
                      maasClassifierName: "from-the-file"
                """);

        IntegrationSystem system = deserializer.deserializeSystem(
                writeService(serviceYaml("KAFKA", "[100, 101, 102]")));

        assertEquals("from-the-file", onlyOperation(system).getSpecification().path("maasClassifierName").asText(),
                "derivation never overwrites an explicit value");
    }

    @Test
    void importsAModelWhoseSourceCannotBeParsedAndLeavesTheSpecificationNull() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", apiModelYaml("openapi", """
                  - id: "op-1"
                    type: "openapi"
                    method: "get"
                    path: "/pets"
                """));
        writeFile("source-" + SPEC_ID + "/source.yaml", "this is not a specification");

        Logger logger = (Logger) LoggerFactory.getLogger(ServiceDeserializer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            IntegrationSystem system = assertDoesNotThrow(() -> deserializer.deserializeSystem(serviceFile));

            assertNull(onlyOperation(system).getSpecification(), "a failed parse leaves the column null");
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(SPEC_ID)),
                    "the import reports the offending model id rather than failing");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * Every parser core wraps its failures as {@code SpecificationImportException} with one fixed message, so the
     * cause is the only thing that says what actually broke. It has to reach the log.
     */
    @Test
    void importLogsTheCauseOfAParseFailure() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", apiModelYaml("openapi", """
                  - id: "op-1"
                    type: "openapi"
                    method: "get"
                    path: "/pets"
                """));
        // Truncated YAML: the swagger deserializer throws, and the core wraps that throw as the cause.
        writeFile("source-" + SPEC_ID + "/source.yaml", "openapi: 3.0.0\ninfo: {title: x");

        List<ILoggingEvent> events = capture(() -> deserializer.deserializeSystem(serviceFile));

        ILoggingEvent event = onlyEventContaining(events,
                "Cannot derive operation specifications for imported model " + SPEC_ID);
        assertNotNull(event.getThrowableProxy(), "the throwable is attached, so the stack trace survives");
        assertEquals(SpecificationImportException.class.getName(), event.getThrowableProxy().getClassName());
        assertNotNull(event.getThrowableProxy().getCause(), "the wrapped cause is what names the real failure");
    }

    /**
     * A key miss is reachable — a source edited after its operations were created, a parser change altering
     * normalization — and leaves exactly the column this derivation exists to fill. One line per model, not per
     * operation.
     */
    @Test
    void importReportsUnmatchedOperationsOnce() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", apiModelYaml("openapi", """
                  - id: "op-1"
                    type: "openapi"
                    method: "get"
                    path: "/gone"
                """));
        writeFile("source-" + SPEC_ID + "/source.yaml", readInput(corpusRoot().resolve("openapi30-orders")));

        List<ILoggingEvent> events = capture(() -> deserializer.deserializeSystem(serviceFile));

        ILoggingEvent event = onlyEventContaining(events, "Import of specification " + SPEC_ID
                + ": 1 of 1 operations did not match the parsed source and keep a null specification");
        assertTrue(event.getFormattedMessage().contains("GET /gone"),
                "the unmatched key is named, not just counted: " + event.getFormattedMessage());
    }

    /**
     * Import stores the specification slice and nothing else. The library parsers always build request and
     * response schemas now, so the guarantee moved from "do not ask for them" to "do not keep them": they are
     * rebuilt from the source on read, and a stored copy would be a second source of truth.
     */
    @Test
    void importKeepsNoMaterializedSchemas() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", apiModelYaml("openapi", """
                  - id: "op-1"
                    type: "openapi"
                    method: "get"
                    path: "/orders"
                """));
        writeFile("source-" + SPEC_ID + "/source.yaml", readInput(corpusRoot().resolve("openapi30-orders")));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        Operation operation = onlyOperation(system);
        assertNotNull(operation.getSpecification(), "the slice is still derived");
        assertNull(operation.getRequestSchema(), "request schemas are rebuilt on read, never stored");
        assertNull(operation.getResponseSchemas(), "response schemas are rebuilt on read, never stored");
    }

    // --- migrations ------------------------------------------------------------------------------------------------

    @Test
    void appliesMissingMigrationsToNodesWithoutContent() throws IOException {
        // A pre-V101 archive: every field sits at the root, so V101 moves them under `content` and V102 follows.
        File serviceFile = writeService("""
                ---
                id: "%s"
                name: "Test service"
                integrationSystemType: "EXTERNAL"
                protocol: "HTTP"
                migrations: "[100]"
                """.formatted(SYSTEM_ID));
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "Test group"
                synchronization: false
                parentId: "%s"
                """.formatted(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                deprecated: false
                version: "1.0.0"
                source: "MANUAL"
                parentId: "%s"
                operations:
                - id: "op-1"
                  method: "GET"
                  path: "/pets"
                """.formatted(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        Operation operation = onlyOperation(system);
        assertEquals("op-1-GET-/pets", operation.getName(), "V102 fills in the missing operation name");
    }

    /**
     * A {@code content} node is not a version marker: every export since V101 writes one, so its presence must not
     * hold the migrations back. This archive stops at V101, and V102 has to fill in the blank operation name.
     */
    @Test
    void appliesMissingMigrationsToNodesThatAlreadyHaveContent() throws IOException {
        File serviceFile = writeService(serviceYaml("[100, 101]"));
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  operations:
                  - id: "op-1"
                    method: "GET"
                    path: "/pets"
                """.formatted(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals("op-1-GET-/pets", onlyOperation(system).getName(),
                "V102 runs even though the node already has a content node");
    }

    /**
     * {@code content.specificationType} marks an api-format document, which is already current. Running the
     * specification migrations over it again would rewrite fields it owns, so the guard has to let it through
     * untouched. Here V102 must leave the blank operation name alone.
     */
    @Test
    void skipsMigrationsForApiFormatNodes() throws IOException {
        File serviceFile = writeService(serviceYaml("[100, 101]"));
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  specificationType: "openapi"
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  operations:
                  - id: "op-1"
                    method: "GET"
                    path: "/pets"
                """.formatted(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertNull(onlyOperation(system).getName(), "an api-format node is imported as written");
    }

    /**
     * V103 needs the service protocol to type an operation, and the file-based route reaches it through
     * {@code buildAndAddSpecification}. Without the protocol stamp the operation would import with a null typed shape.
     */
    @Test
    void typesOperationsFromTheProtocolOnTheFileBasedRoute() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  operations:
                  - id: "op-1"
                    method: "GET"
                    path: "/pets"
                """.formatted(SPEC_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        Operation operation = onlyOperation(system);
        assertEquals("openapi", operation.getOperationKind(), "V103 typed the operation from the service protocol");
        assertEquals("GET", operation.getMethod());
        assertEquals("/pets", operation.getPath());
    }

    /**
     * The inline legacy route reaches the migration through {@code processSystemModels}, never
     * {@code buildAndAddSpecification}, so it has to stamp the protocol on its own. This is the route the legacy
     * archives V103 exists for actually take.
     */
    @Test
    void typesOperationsFromTheProtocolOnTheInlineLegacyRoute() throws IOException {
        File serviceFile = writeService(inlineServiceYaml("""
                    - id: "%s"
                      name: "1.0.0"
                      content:
                        version: "1.0.0"
                        source: "MANUAL"
                        operations:
                        - id: "op-1"
                          method: "GET"
                          path: "/pets"
                """.formatted(SPEC_ID)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        Operation operation = onlyOperation(system);
        assertEquals("openapi", operation.getOperationKind(),
                "the inline route stamps the protocol too, so V103 types the operation");
        assertEquals("GET", operation.getMethod());
        assertEquals("/pets", operation.getPath());
    }

    // --- legacy inline content -------------------------------------------------------------------------------------

    @Test
    void createsGroupsFromInlineLegacyContent() throws IOException {
        File serviceFile = writeService(legacyInlineServiceYaml());

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size());
        assertEquals(GROUP_ID, system.getApiGroups().get(0).getId());
    }

    /**
     * The group carries {@code synchronization} and {@code systemModels} at its root, which is what the two branches
     * of {@code processSpecificationGroup} read off the per-group node.
     */
    @Test
    void importsInlineSystemModelsAndRootLevelSynchronization() throws IOException {
        File serviceFile = writeService("""
                ---
                id: "%s"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102]"
                  specificationGroups:
                  - id: "%s"
                    name: "Test group"
                    synchronization: true
                    systemModels:
                    - id: "%s"
                      name: "1.0.0"
                      content:
                        deprecated: false
                        version: "1.0.0"
                        source: "MANUAL"
                    content:
                      parentId: "%s"
                """.formatted(SYSTEM_ID, GROUP_ID, SPEC_ID, SYSTEM_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        ApiGroup group = system.getApiGroups().get(0);
        assertTrue(group.isSynchronization(), "the root-level synchronization value reaches the entity");
        assertEquals(1, group.getSystemModels().size());

        SystemModel model = group.getSystemModels().get(0);
        assertEquals(SPEC_ID, model.getId());
        assertEquals("1.0.0", model.getVersion());
    }

    /**
     * The shape a real pre-V101 export has: every field sits at the root, in the service file and in each inlined
     * group and model alike. The group and model nodes must be migrated from that raw shape, so a group whose
     * {@code parentId} the deserializer supplies still matches the service and still carries its models.
     */
    @Test
    void importsInlineGroupsAndModelsFromAPreV101Archive() throws IOException {
        File serviceFile = writeService("""
                ---
                id: "%s"
                name: "Test service"
                integrationSystemType: "EXTERNAL"
                protocol: "HTTP"
                migrations: "[100]"
                specificationGroups:
                - id: "%s"
                  name: "Test group"
                  synchronization: true
                  systemModels:
                  - id: "%s"
                    name: "1.0.0"
                    deprecated: false
                    version: "1.0.0"
                    source: "MANUAL"
                    operations:
                    - id: "op-1"
                      method: "GET"
                      path: "/pets"
                """.formatted(SYSTEM_ID, GROUP_ID, SPEC_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size(), "the inline group is imported");
        ApiGroup group = system.getApiGroups().get(0);
        assertEquals(GROUP_ID, group.getId());
        assertTrue(group.isSynchronization());

        assertEquals(1, group.getSystemModels().size(), "the inline model is attached to its group");
        SystemModel model = group.getSystemModels().get(0);
        assertEquals(SPEC_ID, model.getId());
        assertEquals("1.0.0", model.getVersion());
        assertEquals("op-1-GET-/pets", onlyOperation(system).getName(), "V102 still runs on the inline model");
    }

    /**
     * An inline model with no {@code content} of its own is the common legacy shape: the deserializer has to create
     * the node and point it at the enclosing group.
     */
    @Test
    void createsContentForAnInlineModelThatHasNone() throws IOException {
        File serviceFile = writeService(inlineServiceYaml("""
                    - id: "%s"
                      name: "1.0.0"
                """.formatted(SPEC_ID)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().get(0).getSystemModels().size());
        assertEquals(SPEC_ID, system.getApiGroups().get(0).getSystemModels().get(0).getId());
    }

    @Test
    void keepsTheParentIdAnInlineModelAlreadyDeclares() throws IOException {
        File serviceFile = writeService(inlineServiceYaml("""
                    - id: "%s"
                      name: "1.0.0"
                      content:
                        version: "1.0.0"
                        source: "MANUAL"
                        parentId: "another-group"
                """.formatted(SPEC_ID)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertTrue(system.getApiGroups().get(0).getSystemModels().isEmpty(),
                "the declared parentId is not overwritten, so the model stays with the group it names");
    }

    @Test
    void skipsEmptySystemModelEntriesAndImportsTheRest() throws IOException {
        File serviceFile = writeService(inlineServiceYaml("""
                    - ~
                    - id: "%s"
                      name: "1.0.0"
                      content:
                        version: "1.0.0"
                        source: "MANUAL"
                """.formatted(SPEC_ID)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        List<SystemModel> models = system.getApiGroups().get(0).getSystemModels();
        assertEquals(1, models.size(), "an empty entry does not cut the loop short");
        assertEquals(SPEC_ID, models.get(0).getId());
    }

    /**
     * A service file with no {@code id} leaves the deserializer nothing to set as the group parent. That used to be
     * enough to dereference a null content node.
     */
    @Test
    void importsAnInlineGroupWhenTheServiceFileHasNoId() throws IOException {
        File serviceFile = writeService("""
                ---
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102]"
                  specificationGroups:
                  - id: "%s"
                    name: "Test group"
                    synchronization: true
                """.formatted(GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size());
        assertTrue(system.getApiGroups().get(0).isSynchronization());
    }

    /**
     * A document whose stamp already claims 104: the migration does not fire, so the inline list keeps its pre-rename
     * name all the way into the raw-node loop. The DTO alias only picks the legacy branch — finding the list under the
     * old name is the loop's own job.
     */
    @Test
    void importsInlineGroupsLeftUnderTheOldNameByAClaimedV104() throws IOException {
        File serviceFile = writeService("""
                ---
                id: "%s"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102, 104]"
                  specificationGroups:
                  - id: "%s"
                    name: "Test group"
                    content:
                      synchronization: true
                """.formatted(SYSTEM_ID, GROUP_ID));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size(),
                "the raw-node loop has to read the inline list under the pre-V104 name too");
        assertEquals(GROUP_ID, system.getApiGroups().get(0).getId());
        assertTrue(system.getApiGroups().get(0).isSynchronization());
    }

    /**
     * The rollout path never migrates the package it receives: the converter writes the files and stamps the version
     * list itself. A package whose service content still carries the pre-V104 inline group list has to import its
     * groups all the same, so this test runs the converter too.
     */
    @Test
    void importsInlineGroupsOfAServiceWrittenByTheRolloutConverter() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        RolloutImportConfigurationItem item = new RolloutImportConfigurationItem();
        item.setId(SYSTEM_ID);
        item.setName("Test service");
        item.setContent(new YAMLMapper().readTree("""
                integrationSystemType: "EXTERNAL"
                protocol: "HTTP"
                specificationGroups:
                - id: "%s"
                  name: "Test group"
                  content:
                    synchronization: true
                    parentId: "%s"
                """.formatted(GROUP_ID, SYSTEM_ID)));

        Map<Path, byte[]> files = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_NAME, TestServiceMigrations.all())
                .convert(Map.of(SYSTEM_ID, item), Map.of(), Map.of(), Map.of(), Map.of());

        File serviceFile = null;
        for (Map.Entry<Path, byte[]> file : files.entrySet()) {
            serviceFile = writeFile(file.getKey().toString(), new String(file.getValue()));
        }

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(1, system.getApiGroups().size(),
                "a rollout package must not lose its inline groups to the field rename");
        assertEquals(GROUP_ID, system.getApiGroups().get(0).getId());
        assertTrue(system.getApiGroups().get(0).isSynchronization());
    }

    // --- failures --------------------------------------------------------------------------------------------------

    @Test
    void failsWhenAnInlineSystemModelIsNotAnObject() throws IOException {
        File serviceFile = writeService(inlineServiceYaml("    - \"not an object\"\n"));

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> deserializer.deserializeSystem(serviceFile));
        assertTrue(exception.getMessage().contains("Expected object node"),
                "unexpected message: " + exception.getMessage());
    }

    @Test
    void failsWhenSpecificationFileHoldsAnArray() throws IOException {
        File serviceFile = writeService(serviceYaml());
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".specification." + APP_NAME + ".yaml", "- not: an object\n");

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> deserializer.deserializeSystem(serviceFile));
        assertTrue(exception.getMessage().contains("Expected object node")
                        || exception.getCause().getMessage().contains("Expected object node"),
                "unexpected message: " + exception.getMessage());
    }

    @Test
    void failsWhenTheServiceFileCarriesNoVersionInformation() throws IOException {
        File serviceFile = writeService("""
                ---
                id: "%s"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                """.formatted(SYSTEM_ID));

        assertThrows(RuntimeException.class, () -> deserializer.deserializeSystem(serviceFile));
    }

    // --- service type ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void resolvesTheTypeFromTheSchemaWhenTheDocumentStatesNone(IntegrationSystemType type) throws IOException {
        File serviceFile = writeService(schemaStatingYaml(SERVICE_TYPE_FILES.schemaUri(type)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(type, system.getIntegrationSystemType());
    }

    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void keepsTheTypeWhenTheSchemaAndTheDocumentAgree(IntegrationSystemType type) throws IOException {
        File serviceFile = writeService(typedServiceYaml(type.name(), SERVICE_TYPE_FILES.schemaUri(type)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(type, system.getIntegrationSystemType());
    }

    /** A pre-#553 archive states the type only in the document and carries the plain service schema. */
    @Test
    void resolvesTheTypeFromTheDocumentForAPre553File() throws IOException {
        File serviceFile = writeService(typedServiceYaml("INTERNAL", SCHEMA_PROPERTIES.getService()));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(IntegrationSystemType.INTERNAL, system.getIntegrationSystemType());
    }

    /** The legacy flat name carries no schema of its own either, which is what keeps the document field a source. */
    @Test
    void resolvesTheTypeFromTheDocumentForALegacyFlatFileName() throws IOException {
        File serviceFile = writeFile("service-" + SYSTEM_ID + ".yaml", typedServiceYaml("IMPLEMENTED", null));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(IntegrationSystemType.IMPLEMENTED, system.getIntegrationSystemType());
    }

    /**
     * The per-type file names are a format this version reads and no longer writes, and they are not a type source:
     * an archive exported under one still types from the {@code $schema} it carries alongside.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    void resolvesTheTypeOfAFileStillWearingItsPerTypeName(IntegrationSystemType type) throws IOException {
        File serviceFile = writeFile(SYSTEM_ID + "." + typeSlug(type) + "-service." + APP_NAME + ".yaml",
                schemaStatingYaml(SERVICE_TYPE_FILES.schemaUri(type)));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(type, system.getIntegrationSystemType());
    }

    /** A per-type name over a document that states nothing states nothing: the name is not read. */
    @Test
    void refusesAPerTypeNameOverADocumentStatingNoType() throws IOException {
        File serviceFile = writeFile(SYSTEM_ID + ".external-service." + APP_NAME + ".yaml", typelessServiceYaml());

        ServiceImportException exception =
                assertThrows(ServiceImportException.class, () -> deserializer.deserializeSystem(serviceFile));
        assertEquals(SYSTEM_ID, exception.getServiceId());
    }

    /**
     * The {@code $schema} of an installation configured differently, which the schema's own file name still types.
     * Nothing else in this suite reads that second layer through the deserializer.
     */
    @Test
    void resolvesTheTypeFromAForeignlyHostedSchema() throws IOException {
        File serviceFile = writeService(
                schemaStatingYaml("https://schemas.acme.internal/qip/implemented-service.schema.yaml"));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(IntegrationSystemType.IMPLEMENTED, system.getIntegrationSystemType());
    }

    /**
     * A renamed schema file resolves through neither layer, so the {@code $schema} states nothing and there is nothing
     * for the document to disagree with: the field answers alone, exactly as it does for a file carrying no schema.
     */
    @Test
    void resolvesTheTypeFromTheDocumentWhenTheSchemaStatesNoType() throws IOException {
        File serviceFile = writeService(
                typedServiceYaml("EXTERNAL", "https://schemas.acme.internal/qip/renamed.schema.yaml"));

        IntegrationSystem system = deserializer.deserializeSystem(serviceFile);

        assertEquals(IntegrationSystemType.EXTERNAL, system.getIntegrationSystemType());
    }

    @Test
    void failsWhenTheSchemaAndTheDocumentStateDifferentTypes() throws IOException {
        File serviceFile = writeService(
                typedServiceYaml("EXTERNAL", SERVICE_TYPE_FILES.schemaUri(IntegrationSystemType.INTERNAL)));

        ServiceImportException exception =
                assertThrows(ServiceImportException.class, () -> deserializer.deserializeSystem(serviceFile));
        assertEquals(SYSTEM_ID, exception.getServiceId());
        assertTrue(exception.getMessage().contains("INTERNAL") && exception.getMessage().contains("EXTERNAL"),
                "the message has to name both states so the reader knows which one to correct: "
                        + exception.getMessage());
    }

    @Test
    void failsWhenNeitherTheSchemaNorTheDocumentStatesAType() throws IOException {
        File serviceFile = writeService(typelessServiceYaml());

        ServiceImportException exception =
                assertThrows(ServiceImportException.class, () -> deserializer.deserializeSystem(serviceFile));
        assertEquals(SYSTEM_ID, exception.getServiceId());
        assertTrue(exception.getMessage().contains("integrationSystemType"),
                "unexpected message: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("external-service.schema.yaml"),
                "the message has to name the schemas the reader can set: " + exception.getMessage());
    }

    /**
     * The one name shape both formats spell alike. {@code service-ctx.context-service.qip.yaml} is the context name of
     * {@code service-ctx} and the flat name of {@code ctx.context-service.qip}, so the plain-service scan claims it too
     * and finds no type. The row has to name the import that already has the file, or it reads as a lost service.
     */
    @ParameterizedTest
    @CsvSource({".context-service., a context service file", ".mcp-service., an MCP service file"})
    void namesTheOtherImportWhenTheFileNameAlsoReadsAsAnotherKind(String postfix, String phrase) throws IOException {
        File serviceFile = writeFile("service-ctx" + postfix + APP_NAME + ".yaml", typelessServiceYaml());

        ServiceImportException exception =
                assertThrows(ServiceImportException.class, () -> deserializer.deserializeSystem(serviceFile));
        assertTrue(exception.getMessage().contains("also reads as " + phrase),
                "the message has to say which import already handles the file: " + exception.getMessage());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** The current format, whose name states no type: an exported plain service is {@code <id>.service.<app>.yaml}. */
    private File writeService(String yaml) throws IOException {
        return writeService(SERVICE_YAML_NAME_POSTFIX, yaml);
    }

    private File writeService(String namePostfix, String yaml) throws IOException {
        return writeFile(SYSTEM_ID + namePostfix + APP_NAME + ".yaml", yaml);
    }

    /** The type as its schema file spells it, which is how the two sides agree without sharing a constant. */
    private static String typeSlug(IntegrationSystemType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private File writeFile(String relativePath, String content) throws IOException {
        Path path = serviceDirectory.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path.toFile();
    }

    private static String serviceYaml() {
        return serviceYaml("[100, 101, 102]");
    }

    private static String serviceYaml(String migrations) {
        return serviceYaml("HTTP", migrations);
    }

    private static String serviceYaml(String protocol, String migrations) {
        return """
                ---
                id: "%s"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "%s"
                  migrations: "%s"
                """.formatted(SYSTEM_ID, protocol, migrations);
    }

    /** A service document that states its type nowhere: no {@code $schema}, and no field under {@code content}. */
    private static String typelessServiceYaml() {
        return """
                ---
                id: "%s"
                name: "Test service"
                content:
                  protocol: "HTTP"
                  migrations: "[100, 101, 102]"
                """.formatted(SYSTEM_ID);
    }

    private static String typedServiceYaml(String integrationSystemType, String schemaUri) {
        return """
                ---
                id: "%s"
                %sname: "Test service"
                content:
                  integrationSystemType: "%s"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102]"
                """.formatted(SYSTEM_ID, schemaLine(schemaUri), integrationSystemType);
    }

    /** A service document that states its type only through {@code $schema}, the current format. */
    private static String schemaStatingYaml(String schemaUri) {
        return """
                ---
                id: "%s"
                %sname: "Test service"
                content:
                  protocol: "HTTP"
                  migrations: "[100, 101, 102]"
                """.formatted(SYSTEM_ID, schemaLine(schemaUri));
    }

    private static String schemaLine(String schemaUri) {
        return schemaUri == null ? "" : "$schema: \"" + schemaUri + "\"\n";
    }

    /** An api-format model file with one source resource and the operations given verbatim. */
    private static String apiModelYaml(String specificationType, String operations) {
        return """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  specificationType: "%s"
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  specifications:
                  - filePath: "source-%s/source.yaml"
                    isRoot: true
                  operations:
                %s""".formatted(SPEC_ID, specificationType, GROUP_ID, SPEC_ID, operations);
    }

    /** Writes the group and api files for the {@code asyncapi30-kafka-comprehensive} corpus specification. */
    private void writeKafkaModel(String operations) throws IOException {
        writeFile(GROUP_ID + ".specification-group." + APP_NAME + ".yaml", groupYaml(GROUP_ID, SYSTEM_ID));
        writeFile(SPEC_ID + ".api." + APP_NAME + ".yaml", apiModelYaml("asyncapi", operations));
        writeFile("source-" + SPEC_ID + "/source.yaml",
                readInput(corpusRoot().resolve("asyncapi30-kafka-comprehensive")));
    }

    private static String groupYaml(String groupId, String parentId) {
        return """
                ---
                id: "%s"
                name: "Test group"
                content:
                  synchronization: false
                  parentId: "%s"
                """.formatted(groupId, parentId);
    }

    private static String specificationYaml(String specId, String parentId) {
        return """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                """.formatted(specId, parentId);
    }

    private static String specificationYaml(String specId, String parentId, String sourceFileName) {
        return """
                ---
                id: "%s"
                name: "1.0.0"
                content:
                  deprecated: false
                  version: "1.0.0"
                  source: "MANUAL"
                  parentId: "%s"
                  specificationSources:
                  - id: "source-1"
                    name: "api.yaml"
                    fileName: "%s"
                    mainSource: true
                """.formatted(specId, parentId, sourceFileName);
    }

    /** A current-format service file with one inline group whose {@code systemModels} entries are given verbatim. */
    private static String inlineServiceYaml(String systemModelEntries) {
        return """
                ---
                id: "%s"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102]"
                  specificationGroups:
                  - id: "%s"
                    name: "Test group"
                    content:
                      synchronization: true
                      systemModels:
                %s""".formatted(SYSTEM_ID, GROUP_ID, systemModelEntries.indent(4));
    }

    private static String legacyInlineServiceYaml() {
        return """
                ---
                id: "%s"
                name: "Test service"
                content:
                  integrationSystemType: "EXTERNAL"
                  protocol: "HTTP"
                  migrations: "[100, 101, 102]"
                  specificationGroups:
                  - id: "%s"
                    name: "Test group"
                    content:
                      synchronization: true
                      systemModels:
                      - id: "%s"
                        name: "1.0.0"
                        content:
                          deprecated: false
                          version: "1.0.0"
                          source: "MANUAL"
                """.formatted(SYSTEM_ID, GROUP_ID, SPEC_ID);
    }

    private static SpecificationSource onlySource(IntegrationSystem system) {
        List<SpecificationSource> sources = system.getApiGroups().get(0)
                .getSystemModels().get(0).getSpecificationSources();
        assertEquals(1, sources.size());
        return sources.get(0);
    }

    private static List<ILoggingEvent> capture(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ServiceDeserializer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    // Matches on the message text, not on the model id alone: an unrelated warning can carry the same id.
    private static ILoggingEvent onlyEventContaining(List<ILoggingEvent> events, String text) {
        List<ILoggingEvent> matching = events.stream()
                .filter(event -> event.getFormattedMessage().contains(text))
                .toList();
        assertEquals(1, matching.size(), () -> "expected exactly one log event containing \"" + text + "\", got "
                + events.stream().map(ILoggingEvent::getFormattedMessage).toList());
        return matching.get(0);
    }

    private static Operation onlyOperation(IntegrationSystem system) {
        List<Operation> operations = system.getApiGroups().get(0)
                .getSystemModels().get(0).getOperations();
        assertEquals(1, operations.size());
        return operations.get(0);
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
