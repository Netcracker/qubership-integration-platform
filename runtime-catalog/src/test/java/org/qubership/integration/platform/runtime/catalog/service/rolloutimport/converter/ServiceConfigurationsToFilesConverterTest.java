package org.qubership.integration.platform.runtime.catalog.service.rolloutimport.converter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.model.ImportConfig;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.rolloutimport.RolloutImportConfigurationItem;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.GoldenServiceCorpus;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.ServiceTypeFiles;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.V103ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.V104ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.rolloutimport.ImportConfigFactory;
import org.qubership.integration.platform.runtime.catalog.util.ExportImportUtils;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceConfigurationsToFilesConverterTest {

    private static final String APP_PREFIX = "qip";
    private static final String SERVICE_ID = "svc-abc";
    private static final String SPEC_GROUP_ID = "specgroup-xyz";
    private static final String SPEC_ID = "spec-001";

    @TempDir Path packageRoot;

    private ObjectMapper objectMapper;
    private ServiceTypeFiles serviceTypeFiles;
    private ServiceConfigurationsToFilesConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        serviceTypeFiles = new ServiceTypeFiles(new ApplicationJsonSchemaProperties());
        converter = new ServiceConfigurationsToFilesConverter(objectMapper, APP_PREFIX, Collections.emptyList());
    }

    @Test
    @DisplayName("All empty inputs return empty map")
    void allEmptyInputsReturnEmptyMap() throws JsonProcessingException {
        Map<Path, byte[]> result = converter.convert(
                emptyConfigMap(),
                emptyConfigMap(),
                emptyConfigMap(),
                emptyConfigMap(),
                emptyResourceMap()
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Single service creates {serviceId}/{serviceId}.service.{appPrefix}.yaml")
    void singleServiceCreatesCorrectFilePath() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Path expected = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    /**
     * The name states no type in either direction, so a package stating one in {@code content} and a package stating
     * one in {@code $schema} produce the same file. What travels is the document, which carries both.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("A service stating its type is still written under the type-less name")
    void serviceStatingItsTypeIsWrittenUnderTheTypelessName(IntegrationSystemType type)
            throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode().put("integrationSystemType", type.name());
        RolloutImportConfigurationItem stated = item(SERVICE_ID, objectMapper.createObjectNode());
        stated.setSchema(serviceTypeFiles.schemaUri(type));
        Path expected = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");

        assertThat(converter.convert(Map.of(SERVICE_ID, item(SERVICE_ID, content)),
                emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap())).containsKey(expected);
        assertThat(converter.convert(Map.of(SERVICE_ID, stated),
                emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap())).containsKey(expected);
    }

    /**
     * The classify → write → import chain a rollout package runs end to end. Each half was green on its own while the
     * two contradicted each other: the classifier routed a per-type {@code $schema} into the service bucket, and the
     * converter then wrote a type-less {@code .service.} name that the importer refuses.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("A post-#553 package item keeps its type from the classifier through to the imported service")
    void post553PackageItemKeepsItsTypeThroughTheWholeChain(IntegrationSystemType type) throws IOException {
        RolloutImportConfigurationItem item = item(SERVICE_ID, objectMapper.createObjectNode());
        item.setSchema(serviceTypeFiles.schemaUri(type));
        ImportConfig config = new ImportConfigFactory(new ApplicationJsonSchemaProperties(), serviceTypeFiles)
                .fromConfigurationsAndResources(List.of(item), null);
        assertThat(config.getServices()).containsKey(SERVICE_ID);

        Map<Path, byte[]> files = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_PREFIX, TestServiceMigrations.all())
                .convert(config.getServices(), emptyConfigMap(), emptyConfigMap(), emptyConfigMap(),
                        emptyResourceMap());

        IntegrationSystem imported = GoldenServiceCorpus.deserializer().deserializeSystem(write(files));

        assertThat(imported.getIntegrationSystemType()).isEqualTo(type);
    }

    /**
     * The converter builds its names on its own path, so the export-side refusal of an id the current format cannot
     * state does not reach it. It writes the legacy flat name instead, which states the id whole. The type rides the
     * document either way; a current-format name would come back as another id, or not be discovered at all.
     */
    @ParameterizedTest
    @EnumSource(IntegrationSystemType.class)
    @DisplayName("An id the current format cannot state is written under the legacy flat name, type and all")
    void idTheCurrentFormatCannotStateIsWrittenUnderTheLegacyFlatName(IntegrationSystemType type) throws IOException {
        String serviceId = "svc." + type.name().toLowerCase();
        RolloutImportConfigurationItem item = item(serviceId, objectMapper.createObjectNode());
        item.setSchema(serviceTypeFiles.schemaUri(type));

        Map<Path, byte[]> files = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_PREFIX, TestServiceMigrations.all())
                .convert(Map.of(serviceId, item), emptyConfigMap(), emptyConfigMap(), emptyConfigMap(),
                        emptyResourceMap());

        assertThat(files).containsOnlyKeys(Path.of(serviceId).resolve("service-" + serviceId + ".yaml"));
        File written = write(files);
        assertThat(ExportImportUtils.extractSystemIdFromFileName(written)).isEqualTo(serviceId);
        assertThat(GoldenServiceCorpus.deserializer().deserializeSystem(written).getIntegrationSystemType())
                .isEqualTo(type);
    }

    /**
     * The postfix tells the two name formats apart, so an id wearing the flat prefix is written like any other. Such
     * an id is what autodiscovery mints from a Kubernetes service name, so the current format has to state it.
     */
    @Test
    @DisplayName("An id wearing the legacy flat prefix is written under the current-format name")
    void idWearingTheLegacyFlatPrefixIsWrittenUnderTheCurrentFormatName() throws JsonProcessingException {
        String serviceId = "service-abc";

        Map<Path, byte[]> result = converter.convert(
                Map.of(serviceId, item(serviceId, objectMapper.createObjectNode())),
                emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsOnlyKeys(
                Path.of(serviceId).resolve(serviceId + ".service." + APP_PREFIX + ".yaml"));
    }

    /**
     * The one id shape neither name states: its flat name is also the current-format name of another service. Writing
     * either would hand the import another id and another type, so the converter skips the service and says so.
     */
    @ParameterizedTest
    @ValueSource(strings = {".service.", ".external-service.", ".internal-service.", ".implemented-service."})
    @DisplayName("A plain service id neither name can state is skipped, not written unreadable")
    void plainServiceIdNeitherNameCanStateIsSkipped(String postfix) throws Exception {
        String serviceId = "svc" + postfix + "1";
        Map<String, RolloutImportConfigurationItem> services =
                Map.of(serviceId, item(serviceId, objectMapper.createObjectNode()));

        List<ILoggingEvent> events = new ArrayList<>();
        Map<Path, byte[]> result = capture(events, () -> converter.convert(
                services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap()));

        assertThat(result).isEmpty();
        assertThat(events).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(serviceId));
    }

    @Test
    @DisplayName("A service stating an unknown type keeps the plain service file name")
    void serviceStatingAnUnknownTypeKeepsThePlainName() throws JsonProcessingException {
        ObjectNode content = objectMapper.createObjectNode().put("integrationSystemType", "NOT_A_TYPE");
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, content));
        Path expected = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(
                services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Single contextService creates {serviceId}/{serviceId}.context-service.{appPrefix}.yaml")
    void singleContextServiceCreatesCorrectFilePath() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> contextServices = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Path expected = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".context-service." + APP_PREFIX + ".yaml");

        Map<Path, byte[]> result = converter.convert(emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), contextServices, emptyResourceMap());

        assertThat(result).containsKey(expected);
    }

    /**
     * A context service has no legacy flat name any import discovers, so an id the current format cannot state leaves
     * the converter with no readable name to write. It skips the service and says so, rather than writing a name the
     * anchored discovery walks straight past.
     */
    @Test
    @DisplayName("A context service id the current format cannot state is skipped, not written unreadable")
    void contextServiceIdTheCurrentFormatCannotStateIsSkipped() throws Exception {
        String serviceId = "ctx.part";
        Map<String, RolloutImportConfigurationItem> contextServices =
                Map.of(serviceId, item(serviceId, objectMapper.createObjectNode()));

        List<ILoggingEvent> events = new ArrayList<>();
        Map<Path, byte[]> result = capture(events, () -> converter.convert(
                emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), contextServices, emptyResourceMap()));

        assertThat(result).isEmpty();
        assertThat(events).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(serviceId));
    }

    /** A context id wearing the flat prefix is written like any other: nothing about it is the flat format. */
    @Test
    @DisplayName("A context service id wearing the legacy flat prefix is written under its own name")
    void contextServiceIdWearingTheLegacyFlatPrefixIsWritten() throws JsonProcessingException {
        String serviceId = "service-ctx";
        Map<String, RolloutImportConfigurationItem> contextServices =
                Map.of(serviceId, item(serviceId, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = converter.convert(
                emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), contextServices, emptyResourceMap());

        assertThat(result).containsOnlyKeys(
                Path.of(serviceId).resolve(serviceId + ".context-service." + APP_PREFIX + ".yaml"));
    }

    @Test
    @DisplayName("SpecGroup without parentId in content is skipped")
    void specGroupWithoutParentIdIsSkipped() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = converter.convert(services, emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        Path servicePath = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");
        assertThat(result).containsOnlyKeys(servicePath);
    }

    @Test
    @DisplayName("SpecGroup with parentId pointing to non-existing service is skipped")
    void specGroupWithNonExistingServiceIsSkipped() throws JsonProcessingException {
        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", "non-existing-service");
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        Map<Path, byte[]> result = converter.convert(emptyConfigMap(), emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("SpecGroup with valid service parentId creates file under service directory")
    void specGroupWithValidParentCreatesFileUnderServiceDir() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        Map<Path, byte[]> result = converter.convert(services, emptyConfigMap(), specGroups, emptyConfigMap(), emptyResourceMap());

        Path expected = Path.of(SERVICE_ID).resolve(SPEC_GROUP_ID + ".api-group." + APP_PREFIX + ".yaml");
        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Specification without parentId is skipped")
    void specificationWithoutParentIdIsSkipped() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = converter.convert(services, specs, emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        Path specPath = Path.of(SERVICE_ID).resolve(SPEC_ID + ".api." + APP_PREFIX + ".yaml");
        assertThat(result).doesNotContainKey(specPath);
    }

    @Test
    @DisplayName("Specification with valid specGroup/service chain creates api file in service directory")
    void specificationWithValidChainCreatesSpecFile() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), emptyResourceMap());

        Path expected = Path.of(SERVICE_ID).resolve(SPEC_ID + ".api." + APP_PREFIX + ".yaml");
        assertThat(result).containsKey(expected);
    }

    @Test
    @DisplayName("Specification referencing an existing resource by filePath adds resource bytes to result")
    void specificationWithExistingResourceIncludesResourceBytes() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        specContent.put("filePath", "openapi.json");
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        String resourceContent = "{\"openapi\": \"3.0\"}";
        Map<String, String> resources = Map.of("openapi.json", resourceContent);

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), resources);

        Path expectedResource = Path.of(SERVICE_ID).resolve("openapi.json");
        assertThat(result).containsKey(expectedResource);
        assertThat(result.get(expectedResource)).isEqualTo(resourceContent.getBytes());
    }

    @Test
    @DisplayName("Specification still resolves a resource referenced by the legacy fileName field")
    void specificationWithLegacyFileNameIncludesResourceBytes() throws JsonProcessingException {
        Map<String, RolloutImportConfigurationItem> services = Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        ObjectNode sgContent = objectMapper.createObjectNode();
        sgContent.put("parentId", SERVICE_ID);
        Map<String, RolloutImportConfigurationItem> specGroups = Map.of(SPEC_GROUP_ID, item(SPEC_GROUP_ID, sgContent));

        ObjectNode specContent = objectMapper.createObjectNode();
        specContent.put("parentId", SPEC_GROUP_ID);
        specContent.put("fileName", "legacy.json");
        Map<String, RolloutImportConfigurationItem> specs = Map.of(SPEC_ID, item(SPEC_ID, specContent));

        Map<String, String> resources = Map.of("legacy.json", "{}");

        Map<Path, byte[]> result = converter.convert(services, specs, specGroups, emptyConfigMap(), resources);

        assertThat(result).containsKey(Path.of(SERVICE_ID).resolve("legacy.json"));
    }

    /**
     * A package carries no version data, so the converter writes the list itself. Claiming a version it never applied
     * disables that migration for the whole rollout path, which is how the V104 group rename got skipped there.
     */
    @Test
    @DisplayName("Stamped migration versions leave out an idempotent migration, so it still runs on import")
    void stampedVersionsLeaveOutIdempotentMigrations() throws IOException {
        ServiceConfigurationsToFilesConverter stampingConverter = new ServiceConfigurationsToFilesConverter(
                objectMapper, APP_PREFIX,
                List.of(new V103ServiceImportFileMigration(new ApiOperationDtoMapper()),
                        new V104ServiceImportFileMigration()));
        Map<String, RolloutImportConfigurationItem> services =
                Map.of(SERVICE_ID, item(SERVICE_ID, objectMapper.createObjectNode()));

        Map<Path, byte[]> result = stampingConverter.convert(
                services, emptyConfigMap(), emptyConfigMap(), emptyConfigMap(), emptyResourceMap());

        Path servicePath = Path.of(SERVICE_ID).resolve(SERVICE_ID + ".service." + APP_PREFIX + ".yaml");
        JsonNode written = objectMapper.readTree(result.get(servicePath));
        assertThat(written.path("content").path("migrations").asText()).isEqualTo("[103]");
    }

    /** Runs {@code action} with everything the converter logs collected into {@code events}. */
    private static <T> T capture(List<ILoggingEvent> events, Callable<T> action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ServiceConfigurationsToFilesConverter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            return action.call();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            events.addAll(appender.list);
        }
    }

    /** Writes the converted package under the temp root and answers the single service file it holds. */
    private File write(Map<Path, byte[]> files) throws IOException {
        File serviceFile = null;
        for (Map.Entry<Path, byte[]> file : files.entrySet()) {
            Path path = packageRoot.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, file.getValue());
            serviceFile = path.toFile();
        }
        return requireNonNull(serviceFile, "the converter wrote no file");
    }

    private Map<String, RolloutImportConfigurationItem> emptyConfigMap() {
        return Collections.emptyMap();
    }

    private Map<String, String> emptyResourceMap() {
        return Collections.emptyMap();
    }

    private RolloutImportConfigurationItem item(String id, ObjectNode content) {
        RolloutImportConfigurationItem item = new RolloutImportConfigurationItem();
        item.setId(id);
        item.setContent(content);
        return item;
    }
}
