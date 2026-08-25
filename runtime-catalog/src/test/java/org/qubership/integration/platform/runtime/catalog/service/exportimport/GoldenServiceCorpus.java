package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.qubership.integration.platform.chain.model.EnvironmentSourceType;
import org.qubership.integration.platform.io.model.exportimport.ExportImportConstants;
import org.qubership.integration.platform.io.readers.migrations.FileMigrationService;
import org.qubership.integration.platform.io.readers.migrations.mcp.MCPServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.mcp.V100MCPServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.system.ServiceImportFileMigration;
import org.qubership.integration.platform.io.readers.migrations.versions.VersionsGetterService;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldInContentStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.MigrationFieldStrategy;
import org.qubership.integration.platform.io.readers.migrations.versions.strategies.VersionFieldStrategy;
import org.qubership.integration.platform.io.readers.system.ContextServiceReader;
import org.qubership.integration.platform.io.readers.system.IntegrationSystemReader;
import org.qubership.integration.platform.io.readers.system.McpServiceReader;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.configuration.MapperAutoConfiguration;
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
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert.TestRevertMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system.TestServiceMigrations;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ArchiveWriter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ContextServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ExportableObjectWriterVisitor;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.MCPSystemSerializer;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ServiceSerializer;
import org.qubership.integration.platform.runtime.catalog.service.extractor.ExtractorTestParsers;
import org.qubership.integration.platform.runtime.catalog.service.extractor.OperationSchemaExtractor;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.corpusRoot;
import static org.qubership.integration.platform.runtime.catalog.service.extractor.CorpusTestSupport.readInput;

/**
 * The service export/import golden corpus: one fixed set of services, exported by the real serializer chain into
 * {@code schemas/src/test/resources/exportimport-golden/<set>}.
 *
 * <p>The sets live in the {@code schemas} module because two consumers read them: this module, through a
 * {@code <testResource>} that copies them onto the test classpath as {@code /exportimport-golden}, and the VS Code
 * extension, whose {@code pretest:integration} copies two of them into its integration workspace. Only this module can
 * produce them, so a capture writes across the module boundary — the same arrangement the conformance corpus next to
 * them already uses.
 *
 * <p>Four sets, each a full archive tree:
 * <ul>
 *   <li>{@value #PRE553_CURRENT} — the current format as it was before issue #553: {@code .service.} file names, the
 *       plain service {@code $schema}, and {@code content.integrationSystemType} in the document. Its
 *       {@code content.migrations} was hand-edited down to {@code [100, 101, 102, 103, 104]}: the capture ran after
 *       V105 was registered, and a real pre-#553 archive claims at most 104, so keeping 105 would have left the
 *       forward migration of 105 unexercised over a whole archive.</li>
 *   <li>{@value #LEGACY_FLAT} — {@code QIP_EXPORT_LEGACY_FORMAT=true}. #553 must not change a byte of meaning here,
 *       which is the whole point of capturing it before the exporter changed.</li>
 *   <li>{@value #POST553} — the current format after #553: per-type file names and {@code $schema}, no type field.</li>
 *   <li>{@value #POST553_DOTTED} — the same format over an api group and an api whose ids carry dots, the shape every
 *       real export produces and neither of the other current-format sets has.</li>
 * </ul>
 *
 * <p>{@link GoldenCorpusCapture} regenerates the sets. The fixtures are deterministic — no timestamps, no users, fixed
 * ids — so a regeneration that changes a file is a real change in the exported format.
 */
public final class GoldenServiceCorpus {

    public static final String PRE553_CURRENT = "pre553-current";
    public static final String LEGACY_FLAT = "legacy-flat";
    public static final String POST553 = "post553";
    public static final String POST553_DOTTED = "post553-dotted";

    public static final String APP_NAME = "qip";

    public static final String EXTERNAL_SERVICE_ID = "svc-external";
    public static final String INTERNAL_SERVICE_ID = "svc-internal";
    public static final String IMPLEMENTED_SERVICE_ID = "svc-implemented";
    public static final String CONTEXT_SERVICE_ID = "ctx-golden";
    public static final String MCP_SERVICE_ID = "mcp-golden";

    public static final String API_GROUP_ID = "grp-orders";
    public static final String API_ID = "api-orders";

    public static final String DOTTED_SERVICE_ID = "svc-observe";
    public static final String DOTTED_API_GROUP_ID = "grp-helix-observe-3.2";
    public static final String DOTTED_API_ID = "api-helix-observe-3.2-1.0.0";

    static final ApplicationJsonSchemaProperties SCHEMAS = new ApplicationJsonSchemaProperties();

    /**
     * Shared because it is 2.3 ms of every 2.9 ms serializer or deserializer built here — four protocol parsers and
     * several Mockito mocks — and nothing in these tests stubs or verifies it per case. A test that wants to observe
     * the parser calls {@code ExtractorTestParsers.extractor(recordingSwaggerParser())} and is unaffected.
     */
    private static final OperationSchemaExtractor EXTRACTOR = ExtractorTestParsers.extractor();

    /**
     * The fixture set is deterministic, so the archive of each format is built once. Callers hand the bytes to
     * {@code unzipInto} and never mutate them; a copy goes out all the same.
     */
    private static final Map<Boolean, byte[]> ARCHIVES = new ConcurrentHashMap<>();
    private static final String GOLDEN_RESOURCE_PATH = "exportimport-golden";

    private static final List<String> SERVICE_DOCUMENT_PREFIXES = List.of(
            ExportImportConstants.SERVICE_YAML_NAME_PREFIX,
            ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_PREFIX,
            ExportImportConstants.MCP_SERVICE_YAML_NAME_PREFIX);

    // The three per-type postfixes are here because a captured set holds names this version no longer writes.
    private static final List<String> SERVICE_DOCUMENT_POSTFIXES = List.of(
            ExportImportConstants.SERVICE_YAML_NAME_POSTFIX,
            ExportImportConstants.CONTEXT_SERVICE_YAML_NAME_POSTFIX,
            ExportImportConstants.MCP_SERVICE_YAML_NAME_POSTFIX,
            ExportImportConstants.EXTERNAL_SERVICE_YAML_NAME_POSTFIX,
            ExportImportConstants.INTERNAL_SERVICE_YAML_NAME_POSTFIX,
            ExportImportConstants.IMPLEMENTED_SERVICE_YAML_NAME_POSTFIX);

    private GoldenServiceCorpus() {
    }

    // --- the fixtures ------------------------------------------------------------------------------------------------

    /** EXTERNAL, two environments, one api group with one api and one specification source. */
    public static IntegrationSystem externalService() {
        IntegrationSystem system = service(EXTERNAL_SERVICE_ID, "Orders service", IntegrationSystemType.EXTERNAL);
        system.addEnvironment(environment("env-orders-dev", "Dev", "http://orders.dev"));
        system.addEnvironment(environment("env-orders-prod", "Prod", "http://orders.prod"));
        system.setActiveEnvironmentId("env-orders-dev");
        system.addApiGroup(ordersApiGroup(API_GROUP_ID, API_ID));
        return system;
    }

    /**
     * The same service under an api group and an api whose ids carry dots, which a real export does routinely: only
     * the <b>service</b> id has to be one dot-free segment, and no name generator refuses a dotted group or api id.
     *
     * <p>Its own set, {@value #POST553_DOTTED}, rather than a fourth service in {@value #POST553}: that set is
     * compared file for file against a fresh export in both formats, and {@value #PRE553_CURRENT} and
     * {@value #LEGACY_FLAT} can no longer be regenerated to match.
     */
    public static IntegrationSystem dottedApiService() {
        IntegrationSystem system = service(DOTTED_SERVICE_ID, "Observability service", IntegrationSystemType.EXTERNAL);
        system.addEnvironment(environment("env-observe", "Default", "http://observe"));
        system.setActiveEnvironmentId("env-observe");
        system.addApiGroup(ordersApiGroup(DOTTED_API_GROUP_ID, DOTTED_API_ID));
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

    private static ContextSystem contextService() {
        return ContextSystem.builder()
                .id(CONTEXT_SERVICE_ID)
                .name("Golden context service")
                .description("Stamped from the service migration list, so V105 revert has to reach it")
                .build();
    }

    private static MCPSystem mcpService() {
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
    private static ApiGroup ordersApiGroup(String groupId, String apiId) {
        SpecificationSource source = SpecificationSource.builder()
                .id("src-orders")
                .name("orders.yaml")
                .isMainSource(true)
                .source(readInput(corpusRoot().resolve("openapi30-orders")))
                .build();
        SystemModel model = SystemModel.builder()
                .id(apiId)
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
                .id(groupId)
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
    private static List<ExportableObject> exportAll(boolean legacy) {
        ContextServiceSerializer contextSerializer = contextServiceSerializer(legacy);
        MCPSystemSerializer mcpSerializer = mcpSystemSerializer(legacy);
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

    /**
     * The MCP half of the same chain. {@link #exportAll} builds this inline, which left MCP as the one kind a test
     * could not export on its own — the plain and context factories were already public. Widening {@code exportAll}
     * instead would let a caller re-export the whole fixture set by accident.
     */
    public static MCPSystemSerializer mcpSystemSerializer(boolean legacy) {
        return new MCPSystemSerializer(
                mapper(),
                new MCPServiceDtoMapper(
                        URI.create(SCHEMAS.getMcpService()), List.of(new V100MCPServiceImportFileMigration())),
                migrationService(legacy));
    }

    /** The context-service half of the same chain, for a round trip that exports one context service of its own. */
    public static ContextServiceSerializer contextServiceSerializer(boolean legacy) {
        return new ContextServiceSerializer(
                mapper(),
                new ContextServiceDtoMapper(URI.create(SCHEMAS.getContextService()), TestServiceMigrations.all()),
                migrationService(legacy));
    }

    public static ServiceSerializer serviceSerializer(boolean legacy) {
        return new ServiceSerializer(
                mapper(),
                new IntegrationSystemDtoMapper(serviceTypeFiles(), TestServiceMigrations.all()),
                new ApiGroupDtoMapper(URI.create(SCHEMAS.getApiGroup())),
                new SystemModelDtoMapper(URI.create(SCHEMAS.getApi()), new ApiOperationDtoMapper()),
                migrationService(legacy),
                EXTRACTOR);
    }

    /** Any set of services, serialized in either format: the export half of a cross-format round trip. */
    public static List<ExportableObject> exportServices(List<IntegrationSystem> services, boolean legacy) {
        ServiceSerializer serializer = serviceSerializer(legacy);
        return services.stream().map(serializer::serialize).map(ExportableObject.class::cast).toList();
    }

    /** The archive bytes {@code exportSystemsRequest} would return for the fixture set. */
    public static byte[] archive(boolean legacy) {
        return ARCHIVES.computeIfAbsent(legacy, flag -> archive(exportAll(flag), flag)).clone();
    }

    /** The same archive writer over an arbitrary exported set. */
    public static byte[] archive(List<ExportableObject> exported, boolean legacy) {
        return archiveWriter(legacy).writeArchive(exported);
    }

    /** The production writer, with the two properties the application injects. */
    public static ArchiveWriter archiveWriter(boolean legacy) {
        ExportableObjectWriterVisitor visitor = new ExportableObjectWriterVisitor(mapper());
        ReflectionTestUtils.setField(visitor, "appName", APP_NAME);
        ReflectionTestUtils.setField(visitor, "isLegacyExport", legacy);
        return new ArchiveWriter(visitor);
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
        new IntegrationSystemReader(mapper(), forwardMigrationService(), versionsGetterService(), migrations),
        new IntegrationSystemDtoMapper(serviceTypeFiles(), migrations),
        new ApiGroupDtoMapper(URI.create(SCHEMAS.getApiGroup())),
        new SystemModelDtoMapper(URI.create(SCHEMAS.getApi()), new ApiOperationDtoMapper()),
        EXTRACTOR,
        serviceTypeFiles());
        return deserializer;
    }

    public static ContextServiceDeserializer contextServiceDeserializer() {
        return contextServiceDeserializer(TestServiceMigrations.all());
    }

    /** Context services are stamped from the service migration list, so they take the same registry. */
    public static ContextServiceDeserializer contextServiceDeserializer(List<ServiceImportFileMigration> migrations) {
        return new ContextServiceDeserializer(
                new ContextServiceReader(mapper(), forwardMigrationService(), migrations),
                new ContextServiceDtoMapper(URI.create(SCHEMAS.getContextService()), migrations));
    }

    public static MCPSystemDeserializer mcpSystemDeserializer() {
        List<MCPServiceImportFileMigration> migrations = List.of(new V100MCPServiceImportFileMigration());
        return new MCPSystemDeserializer(
                new McpServiceReader(mapper(), forwardMigrationService(), migrations),
                new MCPServiceDtoMapper(URI.create(SCHEMAS.getMcpService()), migrations));
    }

    /** The import-side migration service: forward only, so no revert migration runs while reading a document. */
    public static FileMigrationService forwardMigrationService() {
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

    /**
     * Where {@link GoldenCorpusCapture} writes a set. The sets live in the {@code schemas} module, which both this
     * module and the VS Code extension read, so a capture writes across the module boundary. Surefire runs with this
     * module directory as its working dir.
     */
    public static Path sourceSet(String name) {
        Path resources = Paths.get("..", "schemas", "src", "test", "resources");
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
                    .filter(path -> isServiceDocument(path.getFileName().toString()))
                    .sorted()
                    .toList();
            assertEquals(1, matching.size(),
                    () -> "Expected one service document in " + directory + ", found " + matching);
            return matching.get(0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Every name a service document of any kind can carry, so an api-group or api file next to it never matches. */
    private static boolean isServiceDocument(String fileName) {
        return SERVICE_DOCUMENT_PREFIXES.stream().anyMatch(fileName::startsWith)
                || SERVICE_DOCUMENT_POSTFIXES.stream().anyMatch(fileName::contains);
    }

    public static ObjectNode read(Path file) {
        try {
            return requireObject(mapper().readTree(file.toFile()), file.toString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The same, over a document written inline in a test. */
    public static ObjectNode read(String yaml) {
        try {
            return requireObject(mapper().readTree(yaml), "the inline document");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ObjectNode requireObject(JsonNode node, String description) {
        assertTrue(node.isObject(), () -> description + " is not an object document");
        return (ObjectNode) node;
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

    /**
     * Asserts two archive trees are the same: the file names, then every document. Byte equality is unattainable —
     * {@code ObjectNode} is insertion-ordered and a revert migration appends restored keys last — so the comparison is
     * per document and order-insensitive. One statement of the rule, because loosening it in one caller and not the
     * others is how a fixed point stops being one.
     */
    public static void assertSameTree(Path expected, Path actual, String namesMessage) {
        assertEquals(relativeFileNames(expected), relativeFileNames(actual), namesMessage);
        Map<String, ObjectNode> after = documentsOf(actual);
        documentsOf(expected).forEach((name, document) -> assertEquals(document, after.get(name), name + " changed"));
    }

    /** The same, against a recorded set. */
    public static void assertMatchesRecordedSet(String setName, Path actual, String namesMessage) {
        assertSameTree(set(setName), actual, namesMessage);
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
