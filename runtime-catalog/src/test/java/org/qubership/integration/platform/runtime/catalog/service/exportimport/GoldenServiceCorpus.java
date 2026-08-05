package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.system.EnvironmentSourceType;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportableObject;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp.MCPSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystemLabel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ContextServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.MCPSystemDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.deserializer.ServiceDeserializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiGroupDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ApiOperationDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.ContextServiceDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.IntegrationSystemDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.MCPServiceDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services.SystemModelDtoMapper;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.FileMigrationService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.mcp.MCPServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.mcp.V100MCPServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.TestRevertMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.versions.strategies.VersionFieldStrategy;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ContextServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ExportableObjectWriterVisitor;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.MCPSystemSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.readInput;

/**
 * The service export/import golden corpus: one fixed set of services, exported by the real serializer chain into
 * {@code src/test/resources/exportimport/golden/<set>}.
 *
 * <p>Three sets, each a full archive tree:
 * <ul>
 *   <li>{@value #PRE553_CURRENT} — the current format as it was before issue #553: {@code .service.} file names, the
 *       plain service {@code $schema}, and {@code content.integrationSystemType} in the document.</li>
 *   <li>{@value #LEGACY_FLAT} — {@code QIP_EXPORT_LEGACY_FORMAT=true}. #553 must not change a byte of meaning here,
 *       which is the whole point of capturing it before the exporter changed.</li>
 *   <li>{@value #POST553} — the current format after #553: per-type file names and {@code $schema}, no type field.</li>
 * </ul>
 *
 * <p>{@link GoldenCorpusCapture} regenerates the sets. The fixtures are deterministic — no timestamps, no users, fixed
 * ids — so a regeneration that changes a file is a real change in the exported format.
 */
public final class GoldenServiceCorpus {

    public static final String PRE553_CURRENT = "pre553-current";
    public static final String LEGACY_FLAT = "legacy-flat";
    public static final String POST553 = "post553";

    public static final String APP_NAME = "qip";

    public static final String EXTERNAL_SERVICE_ID = "svc-external";
    public static final String INTERNAL_SERVICE_ID = "svc-internal";
    public static final String IMPLEMENTED_SERVICE_ID = "svc-implemented";
    public static final String CONTEXT_SERVICE_ID = "ctx-golden";
    public static final String MCP_SERVICE_ID = "mcp-golden";

    public static final String API_GROUP_ID = "grp-orders";
    public static final String API_ID = "api-orders";

    private static final ApplicationJsonSchemaProperties SCHEMAS = new ApplicationJsonSchemaProperties();
    private static final String GOLDEN_RESOURCE_PATH = "exportimport/golden";

    private GoldenServiceCorpus() {
    }

    // --- the fixtures ------------------------------------------------------------------------------------------------

    /** EXTERNAL, two environments, one api group with one api and one specification source. */
    public static IntegrationSystem externalService() {
        IntegrationSystem system = service(EXTERNAL_SERVICE_ID, "Orders service", IntegrationSystemType.EXTERNAL);
        system.addEnvironment(environment("env-orders-dev", "Dev", "http://orders.dev"));
        system.addEnvironment(environment("env-orders-prod", "Prod", "http://orders.prod"));
        system.setActiveEnvironmentId("env-orders-dev");
        system.addApiGroup(ordersApiGroup());
        return system;
    }

    /** INTERNAL, one environment — the limit its schema states. */
    public static IntegrationSystem internalService() {
        IntegrationSystem system = service(INTERNAL_SERVICE_ID, "Billing service", IntegrationSystemType.INTERNAL);
        system.addEnvironment(environment("env-billing", "Default", "http://billing"));
        system.setActiveEnvironmentId("env-billing");
        system.setInternalServiceName("billing");
        return system;
    }

    /** IMPLEMENTED, one environment; deploy-time resolution reads its {@code activeEnvironmentId}. */
    public static IntegrationSystem implementedService() {
        IntegrationSystem system =
                service(IMPLEMENTED_SERVICE_ID, "Shipping service", IntegrationSystemType.IMPLEMENTED);
        system.addEnvironment(environment("env-shipping", "Default", "http://shipping"));
        system.setActiveEnvironmentId("env-shipping");
        return system;
    }

    public static ContextSystem contextService() {
        return ContextSystem.builder()
                .id(CONTEXT_SERVICE_ID)
                .name("Golden context service")
                .description("Stamped from the service migration list, so V105 revert has to reach it")
                .build();
    }

    public static MCPSystem mcpService() {
        return MCPSystem.builder()
                .id(MCP_SERVICE_ID)
                .name("Golden MCP service")
                .description("Discovered by its own postfix, next to the plain services")
                .identifier("golden-mcp")
                .instructions("Answer with the order status")
                .labels(new LinkedHashSet<>())
                .build();
    }

    private static IntegrationSystem service(String id, String name, IntegrationSystemType type) {
        IntegrationSystem system = IntegrationSystem.builder()
                .id(id)
                .name(name)
                .description("Golden corpus fixture for " + type.name().toLowerCase() + " services")
                .integrationSystemType(type)
                .protocol(OperationProtocol.HTTP)
                .environments(new ArrayList<>())
                .apiGroups(new ArrayList<>())
                .build();
        system.setLabels(new LinkedHashSet<>(List.of(new IntegrationSystemLabel("golden", system))));
        return system;
    }

    private static Environment environment(String id, String name, String address) {
        return Environment.builder()
                .id(id)
                .name(name)
                .address(address)
                .sourceType(EnvironmentSourceType.MANUAL)
                .build();
    }

    /** The corpus {@code openapi30-orders} case, so the exported api carries real operations and a real source. */
    private static ApiGroup ordersApiGroup() {
        SpecificationSource source = SpecificationSource.builder()
                .id("src-orders")
                .name("orders.yaml")
                .isMainSource(true)
                .source(readInput(corpusRoot().resolve("openapi30-orders")))
                .build();
        SystemModel model = SystemModel.builder()
                .id(API_ID)
                .name("Orders API")
                .version("v1")
                .specificationType("openapi")
                .specificationVersion("3.0.0")
                .operations(new ArrayList<>(List.of(
                        operation("op-list-orders", "listOrders", "/orders", "get"),
                        operation("op-create-order", "createOrder", "/orders", "post"),
                        operation("op-get-order", "getOrder", "/orders/{orderId}", "get"))))
                .specificationSources(new ArrayList<>(List.of(source)))
                .build();
        source.setSystemModel(model);

        ApiGroup group = ApiGroup.builder()
                .id(API_GROUP_ID)
                .name("Orders")
                .synchronization(false)
                .systemModels(new ArrayList<>())
                .build();
        group.addSystemModel(model);
        return group;
    }

    // setTyped, not the builder: only the setter derives method and path, as every parser does.
    private static Operation operation(String id, String name, String path, String method) {
        Operation operation = Operation.builder().id(id).name(name).build();
        operation.setTyped(new OpenapiOperation(name, path, method, false));
        return operation;
    }

    // --- the export --------------------------------------------------------------------------------------------------

    /** The five fixture systems, serialized the way {@code SystemExportImportService} serializes them. */
    public static List<ExportableObject> exportAll(boolean legacy) {
        FileMigrationService migrations = migrationService(legacy);
        ContextServiceSerializer contextSerializer = new ContextServiceSerializer(
                mapper(),
                new ContextServiceDtoMapper(URI.create(SCHEMAS.getContextService()), TestServiceMigrations.all()),
                migrations);
        MCPSystemSerializer mcpSerializer = new MCPSystemSerializer(
                mapper(),
                new MCPServiceDtoMapper(
                        URI.create(SCHEMAS.getMcpService()), List.of(new V100MCPServiceImportFileMigration())),
                migrations);
        List<ExportableObject> exported = new ArrayList<>(
                exportServices(List.of(externalService(), internalService(), implementedService()), legacy));
        try {
            exported.add(contextSerializer.serialize(contextService()));
            exported.add(mcpSerializer.serialize(mcpService()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return List.copyOf(exported);
    }

    public static ServiceSerializer serviceSerializer(boolean legacy) {
        return new ServiceSerializer(
                mapper(),
                new IntegrationSystemDtoMapper(serviceTypeFiles(), TestServiceMigrations.all()),
                new ApiGroupDtoMapper(URI.create(SCHEMAS.getApiGroup())),
                new SystemModelDtoMapper(URI.create(SCHEMAS.getApi()), new ApiOperationDtoMapper()),
                migrationService(legacy),
                ExtractorTestParsers.extractor());
    }

    /** Any set of services, serialized in either format: the export half of a cross-format round trip. */
    public static List<ExportableObject> exportServices(List<IntegrationSystem> services, boolean legacy) {
        ServiceSerializer serializer = serviceSerializer(legacy);
        return services.stream().map(serializer::serialize).map(ExportableObject.class::cast).toList();
    }

    /** The archive bytes {@code exportSystemsRequest} would return for the fixture set. */
    public static byte[] archive(boolean legacy) {
        return archive(exportAll(legacy), legacy);
    }

    /** The same archive writer over an arbitrary exported set. */
    public static byte[] archive(List<ExportableObject> exported, boolean legacy) {
        ExportableObjectWriterVisitor visitor = new ExportableObjectWriterVisitor(mapper());
        ReflectionTestUtils.setField(visitor, "appName", APP_NAME);
        ReflectionTestUtils.setField(visitor, "isLegacyExport", legacy);
        return new ArchiveWriter(visitor).writeArchive(exported);
    }

    /** The real deserializer, wired as the application wires it, for reading a captured set back in. */
    public static ServiceDeserializer deserializer() {
        return deserializer(TestServiceMigrations.all());
    }

    /**
     * The same deserializer over a chosen migration registry. A shorter list is the registry of an older QIP, so a
     * legacy export can be read here the way the version it has to stay importable by would read it.
     */
    public static ServiceDeserializer deserializer(List<ServiceImportFileMigration> migrations) {
        ServiceDeserializer deserializer = new ServiceDeserializer(
                mapper(),
                versionsGetterService(),
                new IntegrationSystemDtoMapper(serviceTypeFiles(), migrations),
                new ApiGroupDtoMapper(URI.create(SCHEMAS.getApiGroup())),
                new SystemModelDtoMapper(URI.create(SCHEMAS.getApi()), new ApiOperationDtoMapper()),
                forwardMigrationService(),
                migrations,
                ExtractorTestParsers.extractor(),
                serviceTypeFiles());
        ReflectionTestUtils.setField(deserializer, "appName", APP_NAME);
        return deserializer;
    }

    public static ContextServiceDeserializer contextServiceDeserializer() {
        return contextServiceDeserializer(TestServiceMigrations.all());
    }

    /** Context services are stamped from the service migration list, so they take the same registry. */
    public static ContextServiceDeserializer contextServiceDeserializer(List<ServiceImportFileMigration> migrations) {
        return new ContextServiceDeserializer(
                mapper(),
                forwardMigrationService(),
                migrations,
                new ContextServiceDtoMapper(URI.create(SCHEMAS.getContextService()), migrations));
    }

    public static MCPSystemDeserializer mcpSystemDeserializer() {
        List<MCPServiceImportFileMigration> migrations = List.of(new V100MCPServiceImportFileMigration());
        return new MCPSystemDeserializer(
                mapper(),
                forwardMigrationService(),
                migrations,
                new MCPServiceDtoMapper(URI.create(SCHEMAS.getMcpService()), migrations));
    }

    /** The import-side migration service: forward only, so no revert migration runs while reading a document. */
    private static FileMigrationService forwardMigrationService() {
        FileMigrationService service = new FileMigrationService(mapper(), versionsGetterService(), List.of());
        ReflectionTestUtils.setField(service, "isLegacyExport", false);
        return service;
    }

    public static YAMLMapper mapper() {
        return new MapperAutoConfiguration().yamlExportImportMapper();
    }

    public static ServiceTypeFiles serviceTypeFiles() {
        return new ServiceTypeFiles(SCHEMAS);
    }

    public static ApplicationJsonSchemaProperties schemas() {
        return SCHEMAS;
    }

    public static VersionsGetterService versionsGetterService() {
        MigrationFieldStrategy migrationFieldStrategy = new MigrationFieldStrategy();
        return new VersionsGetterService(List.of(
                new MigrationFieldInContentStrategy(migrationFieldStrategy),
                migrationFieldStrategy,
                new VersionFieldStrategy()));
    }

    public static FileMigrationService migrationService(boolean legacy) {
        FileMigrationService service = new FileMigrationService(
                mapper(), versionsGetterService(), TestRevertMigrations.all(URI.create(SCHEMAS.getSpecification())));
        ReflectionTestUtils.setField(service, "isLegacyExport", legacy);
        return service;
    }

    // --- reading a captured set --------------------------------------------------------------------------------------

    /** Root of a captured set on the test classpath. */
    public static Path set(String name) {
        URL url = GoldenServiceCorpus.class.getResource("/" + GOLDEN_RESOURCE_PATH + "/" + name);
        assertNotNull(url, () -> "Golden set " + name + " is not on the test classpath. Regenerate it with "
                + GoldenCorpusCapture.class.getSimpleName() + ".");
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Where {@link GoldenCorpusCapture} writes a set. Surefire runs with the module directory as its working dir. */
    public static Path sourceSet(String name) {
        Path resources = Paths.get("src", "test", "resources");
        assertTrue(Files.isDirectory(resources),
                () -> "Expected the module directory as the working directory, got " + Paths.get("").toAbsolutePath());
        return resources.resolve(GOLDEN_RESOURCE_PATH).resolve(name);
    }

    /** The one service document of {@code serviceId} in a captured set, whatever the set names it. */
    public static Path serviceFile(String setName, String serviceId) {
        return serviceFileIn(set(setName), serviceId);
    }

    /** The same, in an archive tree unzipped anywhere, for reading a live export back. */
    public static Path serviceFileIn(Path root, String serviceId) {
        Path directory = root.resolve(ExportImportConstants.ARCH_PARENT_DIR).resolve(serviceId);
        try (var files = Files.list(directory)) {
            List<Path> matching = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("service"))
                    .sorted()
                    .toList();
            assertTrue(matching.size() == 1,
                    () -> "Expected one service document in " + directory + ", found " + matching);
            return matching.get(0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static ObjectNode read(Path file) {
        try {
            JsonNode node = mapper().readTree(file.toFile());
            assertTrue(node.isObject(), () -> file + " is not an object document");
            return (ObjectNode) node;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Every file of a set, as paths relative to the set root, sorted. */
    public static List<String> fileNames(String setName) {
        return relativeFileNames(set(setName));
    }

    public static List<String> relativeFileNames(Path root) {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Every document of a directory tree, keyed by its path relative to the root. */
    public static Map<String, ObjectNode> documentsOf(Path root) {
        Map<String, ObjectNode> documents = new LinkedHashMap<>();
        for (String name : relativeFileNames(root)) {
            documents.put(name, read(root.resolve(name)));
        }
        return documents;
    }

    // --- writing a set -----------------------------------------------------------------------------------------------

    public static void unzipInto(byte[] archive, Path target) throws IOException {
        if (Files.exists(target)) {
            try (var walk = Files.walk(target)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(target);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                Path resolved = target.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(target)) {
                    throw new IOException("Archive entry escapes the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Files.createDirectories(resolved.getParent());
                Files.copy(zip, resolved);
            }
        }
    }
}
